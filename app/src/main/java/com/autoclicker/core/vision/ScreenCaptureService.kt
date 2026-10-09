package com.autoclicker.core.vision

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autoclicker.R

/**
 * 识图截屏服务：以前台服务持有 MediaProjection，通过 VirtualDisplay + ImageReader 取帧。
 *
 * 使用流程：先由 CapturePermissionActivity 申请授权，再 start 本服务，随后即可在后台线程
 * 调用 [capture] 抓取屏幕位图。所有异常均被吞掉，不会崩溃。
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "capture_channel"
        const val NOTIFICATION_ID = 1003
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        @Volatile
        var isReady: Boolean = false
            private set

        @Volatile
        private var instance: ScreenCaptureService? = null

        /** 启动截屏服务（前台）。 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenCaptureService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data)
            )
        }

        /** 停止截屏服务。 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ScreenCaptureService::class.java))
            } catch (e: Exception) {
                // 忽略
            }
        }

        /**
         * 抓取当前屏幕。
         *
         * 必须在后台线程调用（acquireLatestImage 会阻塞主线程）。
         *
         * @param maxWidth 大于 0 时等比缩放到宽度不超过该值。
         */
        fun capture(maxWidth: Int = 0): Bitmap? {
            val service = instance ?: return null
            return service.captureInternal(maxWidth)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须首行调用，避免前台服务超时崩溃。
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (data == null) {
                stopSelf()
                return Service.START_NOT_STICKY
            }

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data)
            if (projection == null) {
                stopSelf()
                return Service.START_NOT_STICKY
            }
            mediaProjection = projection

            val size = screenSize()
            val width = size[0]
            val height = size[1]
            val density = resources.displayMetrics.densityDpi

            val thread = HandlerThread("screen-capture").also { it.start() }
            handlerThread = thread
            val threadHandler = Handler(thread.looper)
            handler = threadHandler

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            virtualDisplay = projection.createVirtualDisplay(
                "auto-clicker-capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                threadHandler
            )

            instance = this
            isReady = true
        } catch (e: Exception) {
            isReady = false
            instance = null
            stopSelf()
        }

        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isReady = false
        instance = null
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            // 忽略
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (e: Exception) {
            // 忽略
        }
        imageReader = null
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            // 忽略
        }
        mediaProjection = null
        try {
            handlerThread?.quitSafely()
        } catch (e: Exception) {
            // 忽略
        }
        handlerThread = null
        handler = null
        super.onDestroy()
    }

    private fun captureInternal(maxWidth: Int): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride <= 0 || rowStride <= 0) return null

            // rowStride 可能大于 width * pixelStride，按行跨距计算实际位图宽度。
            val bitmapWidth = rowStride / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            if (maxWidth > 0 && bitmap.width > maxWidth) {
                val targetHeight = (bitmap.height.toLong() * maxWidth / bitmap.width)
                    .toInt()
                    .coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
                if (scaled !== bitmap) {
                    bitmap.recycle()
                }
                return scaled
            }
            return bitmap
        } catch (e: Exception) {
            return null
        } finally {
            try {
                image?.close()
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    /** 屏幕尺寸：API 30+ 用 currentWindowMetrics，低于 30 用 displayMetrics。 */
    private fun screenSize(): IntArray {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val bounds = wm.currentWindowMetrics.bounds
                intArrayOf(bounds.width(), bounds.height())
            } else {
                val metrics = resources.displayMetrics
                intArrayOf(metrics.widthPixels, metrics.heightPixels)
            }
        } catch (e: Exception) {
            val metrics = resources.displayMetrics
            intArrayOf(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "识图截屏", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("自动点击助手")
            .setContentText("识图截屏就绪")
            .setOngoing(true)
            .build()
    }
}
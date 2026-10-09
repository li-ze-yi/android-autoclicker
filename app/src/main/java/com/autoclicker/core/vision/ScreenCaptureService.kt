package com.autoclicker.core.vision

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 识图截屏服务：以前台服务持有 MediaProjection，通过 VirtualDisplay + ImageReader 取帧。
 *
 * 使用流程：先由 CapturePermissionActivity 申请授权，再 start 本服务，随后即可在后台线程
 * 调用 [capture] 抓取屏幕位图。失败原因通过 [ready] / [lastError] 两个可观察通道对外暴露，
 * 不再静默吞异常。
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

        /** 截屏通道是否就绪（可观察，供 UI/悬浮窗订阅）。 */
        private val _ready = MutableStateFlow(false)
        val ready: StateFlow<Boolean> = _ready.asStateFlow()

        /** 最近一次失败原因；成功时置 null。 */
        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

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

        /**
         * 挂起等待截屏通道就绪，最多等 timeoutMs。已就绪立即返回 true。
         * 应在协程中调用。
         */
        suspend fun awaitReady(timeoutMs: Long = 4000L): Boolean {
            if (_ready.value) return true
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                delay(100L)
                if (_ready.value) return true
            }
            return _ready.value
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    /** 通道就绪：清空历史错误。 */
    private fun markReady() {
        isReady = true
        _ready.value = true
        _lastError.value = null
    }

    /** 启动失败：通道不可用并记录原因。 */
    private fun markStartFailed(message: String) {
        isReady = false
        _ready.value = false
        _lastError.value = message
    }

    /** 取帧失败：仅记录原因，不影响通道就绪状态。 */
    private fun markCaptureFailed(message: String) {
        _lastError.value = message
    }

    /** 安全拼接启动失败描述，避免读取 message 时二次异常。 */
    private fun describeStartFailure(e: Exception): String {
        val message = try {
            e.message
        } catch (t: Throwable) {
            null
        }
        return "启动截屏失败：${e::class.java.simpleName}: $message"
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须首行调用，避免前台服务超时崩溃；显式指定前台服务类型（Android 10+）。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            markStartFailed(describeStartFailure(e))
            stopSelf()
            return Service.START_NOT_STICKY
        }

        // 已在运行：忽略重复启动，复用现有投影/虚拟显示/ImageReader。
        // 否则每次重复 start 都会新建一套并覆盖旧字段，旧的永不释放（Android 14 上
        // 同一 MediaProjection 重复注册回调还会抛异常）。
        if (imageReader != null) {
            return Service.START_NOT_STICKY
        }

        try {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (data == null) {
                markStartFailed("启动截屏失败：授权数据为空")
                stopSelf()
                return Service.START_NOT_STICKY
            }

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data)
            if (projection == null) {
                markStartFailed("启动截屏失败：MediaProjection 为空（可能未授权）")
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

            // Android 14：投影被系统终止时必须清理并停止服务。
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    markStartFailed("截屏投影已被系统终止")
                    stopSelf()
                }
            }, threadHandler)

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
            markReady()
        } catch (e: Exception) {
            markStartFailed(describeStartFailure(e))
            stopSelf()
        }

        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isReady = false
        _ready.value = false
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
        val reader = imageReader
        if (reader == null) {
            markCaptureFailed("取帧失败：截屏通道未就绪")
            return null
        }
        val maxAttempts = 8
        val retryIntervalMs = 120L
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image == null) {
                    // VirtualDisplay 刚建好可能还没有新帧，等待后重试。
                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(retryIntervalMs)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                    continue
                }

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                if (pixelStride <= 0 || rowStride <= 0) {
                    markCaptureFailed("取帧异常：像素跨距非法")
                    return null
                }

                // rowStride 可能大于 width * pixelStride，按行跨距计算实际位图宽度。
                val bitmapWidth = rowStride / pixelStride
                val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                _lastError.value = null
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
                lastException = e
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(retryIntervalMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } finally {
                try {
                    image?.close()
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }

        if (lastException != null) {
            val message = try {
                lastException.message
            } catch (t: Throwable) {
                null
            }
            markCaptureFailed("取帧异常: ${lastException::class.java.simpleName}: $message")
        } else {
            markCaptureFailed("取帧超时（可能未授权或屏幕无变化）")
        }
        return null
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
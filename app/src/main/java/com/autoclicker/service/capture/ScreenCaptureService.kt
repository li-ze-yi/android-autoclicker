package com.autoclicker.service.capture

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
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
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autoclicker.MainActivity
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.di.NotificationChannels
import com.autoclicker.di.ServiceLocator
import com.autoclicker.platform.ScreenFrame
import com.autoclicker.platform.ScreenSource
import com.autoclicker.service.accessibility.AccessibilityScreenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaProjection 截屏前台服务：建立 [ImageReader]/[VirtualDisplay]，提供一次性截帧能力，
 * 封装为 [MediaProjectionScreenSource] 并在无障碍截屏不可用时注册到 [ServiceLocator.screenSource]。
 */
class ScreenCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var source: MediaProjectionScreenSource? = null
    private var releasing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundInternal()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = extractResultData(intent)
        if (data != null && resultCode != Activity.RESULT_CANCELED) {
            startProjection(resultCode, data)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun extractResultData(intent: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    private fun startForegroundInternal() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (projection != null) return
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            RuntimeBus.log(LogLevel.ERROR, "屏幕采集不可用：MediaProjectionManager 缺失")
            return
        }
        val mediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "屏幕采集授权失败：${t.message}")
            null
        } ?: return

        try {
            mediaProjection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        RuntimeBus.log(LogLevel.WARN, "屏幕采集会话已停止")
                        releaseProjection()
                    }
                },
                mainHandler,
            )

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val display = mediaProjection.createVirtualDisplay(
                "autoclicker-capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler,
            )

            projection = mediaProjection
            imageReader = reader
            virtualDisplay = display
            val newSource = MediaProjectionScreenSource(reader, width, height) { projection != null }
            source = newSource
            // 无障碍截屏可用时优先保留无障碍方案。
            if (ServiceLocator.screenSource?.isReady() != true) {
                ServiceLocator.screenSource = newSource
            }
            RuntimeBus.log("屏幕采集已启动：${width}x${height}")
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "屏幕采集初始化失败：${t.message}")
            releaseProjection()
        }
    }

    override fun onDestroy() {
        releaseProjection()
        super.onDestroy()
    }

    private fun releaseProjection() {
        if (releasing) return
        releasing = true
        try {
            val current = source
            try {
                virtualDisplay?.release()
            } catch (t: Throwable) {
                // 忽略释放异常。
            }
            virtualDisplay = null
            try {
                imageReader?.close()
            } catch (t: Throwable) {
                // 忽略关闭异常。
            }
            imageReader = null
            val mediaProjection = projection
            projection = null
            try {
                mediaProjection?.stop()
            } catch (t: Throwable) {
                // 忽略停止异常。
            }
            source = null
            if (current != null && ServiceLocator.screenSource === current) {
                ServiceLocator.screenSource = AccessibilityScreenSource.instance?.takeIf { it.isReady() }
            }
        } finally {
            releasing = false
        }
    }

    private fun buildNotification(): Notification {
        NotificationChannels.ensure(this)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannels.CHANNEL_RUNTIME)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("屏幕采集运行中")
            .setContentText("正在为找图/找色/OCR 提供屏幕画面")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val NOTIFICATION_ID = 4202

        fun createIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }

        fun start(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(context, createIntent(context, resultCode, data))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}

/**
 * 基于已建立的 [ImageReader] 抓取单帧。非线程安全地读取最新一帧，失败返回 null。
 */
class MediaProjectionScreenSource(
    private val reader: ImageReader,
    private val width: Int,
    private val height: Int,
    private val readyCheck: () -> Boolean,
) : ScreenSource {

    override fun isReady(): Boolean = readyCheck()

    override suspend fun capture(): ScreenFrame? = withContext(Dispatchers.IO) {
        val image = acquire() ?: return@withContext null
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val rawWidth = (width + rowPadding / pixelStride).coerceAtLeast(width)

            val bitmap = Bitmap.createBitmap(rawWidth, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            val result = if (rawWidth == width) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }
            }
            ScreenFrame(result, result.width, result.height)
        } catch (t: Throwable) {
            null
        } finally {
            image.close()
        }
    }

    override fun release() {
        // 由 ScreenCaptureService 统一释放底层资源。
    }

    private fun acquire(): Image? {
        val deadline = SystemClock.uptimeMillis() + ACQUIRE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val image = try {
                reader.acquireLatestImage()
            } catch (t: Throwable) {
                null
            }
            if (image != null) return image
            try {
                Thread.sleep(FRAME_INTERVAL_MS)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private companion object {
        const val ACQUIRE_TIMEOUT_MS = 1500L
        const val FRAME_INTERVAL_MS = 16L
    }
}
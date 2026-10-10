package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.platform.ScreenFrame
import com.autoclicker.platform.ScreenSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 无障碍截屏来源（API 30+ 的 [AccessibilityService.takeScreenshot]）。
 * 作为首选 [ScreenSource]；进程内单例便于断线重连与恢复。
 */
class AccessibilityScreenSource private constructor() : ScreenSource {

    @Volatile
    private var provider: (() -> AccessibilityService?)? = null

    /** 上次成功调用截屏的时间，用于满足系统的最小调用间隔。 */
    @Volatile
    private var lastCaptureAt = 0L

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "a11y-screenshot").apply { isDaemon = true }
    }

    fun attach(serviceProvider: () -> AccessibilityService?) {
        provider = serviceProvider
    }

    fun detach() {
        provider = null
    }

    override fun isReady(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && provider?.invoke() != null

    override suspend fun capture(): ScreenFrame? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // 系统对 takeScreenshot 有调用间隔限制，连续调用会直接失败（返回旧帧或报错）。
        // 这里主动节流到最小间隔，避免「识别时快时慢、甚至一直识别不上」。
        val elapsed = SystemClock.elapsedRealtime() - lastCaptureAt
        val wait = MIN_CAPTURE_INTERVAL_MS - elapsed
        if (wait > 0) delay(wait)
        val service = provider?.invoke() ?: return null
        val frame = captureOnR(service)
        lastCaptureAt = SystemClock.elapsedRealtime()
        return frame
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureOnR(service: AccessibilityService): ScreenFrame? =
        suspendCancellableCoroutine { cont ->
            try {
                val accepted = service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val frame = try {
                                val buffer = screenshot.hardwareBuffer
                                val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                buffer.close()
                                val soft = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                                hardware?.recycle()
                                soft?.let { ScreenFrame(it, it.width, it.height) }
                            } catch (t: Throwable) {
                                null
                            }
                            if (cont.isActive) cont.resume(frame)
                        }

                        override fun onFailure(errorCode: Int) {
                            // 以前这里静默返回 null，导致「截屏被系统节流」和「画面里没有目标」
                            // 两种情况无法区分，识别点击会误判成失败。现在明确记录原因。
                            RuntimeBus.log(LogLevel.WARN, "截屏失败：${errorName(errorCode)}")
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }

    override fun release() {
        provider = null
    }

    /** 截屏失败码转可读文案，便于在控制台日志里定位。 */
    private fun errorName(code: Int): String = when (code) {
        1 -> "系统内部错误"
        2 -> "无截屏权限，请确认无障碍服务已开启"
        3 -> "调用过于频繁被系统限制（间隔太短）"
        4 -> "无效显示设备"
        5 -> "无效窗口"
        else -> "未知错误码 $code"
    }

    companion object {
        /** 系统对 takeScreenshot 的最小调用间隔，留一点余量避免被判定为过于频繁。 */
        private const val MIN_CAPTURE_INTERVAL_MS = 450L

        @Volatile
        var instance: AccessibilityScreenSource? = null
            private set

        fun obtain(): AccessibilityScreenSource =
            instance ?: synchronized(this) {
                instance ?: AccessibilityScreenSource().also { instance = it }
            }
    }
}
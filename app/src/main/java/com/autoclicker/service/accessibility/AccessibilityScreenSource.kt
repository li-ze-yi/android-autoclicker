package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.autoclicker.platform.ScreenFrame
import com.autoclicker.platform.ScreenSource
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
        val service = provider?.invoke() ?: return null
        return captureOnR(service)
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

    companion object {
        @Volatile
        var instance: AccessibilityScreenSource? = null
            private set

        fun obtain(): AccessibilityScreenSource =
            instance ?: synchronized(this) {
                instance ?: AccessibilityScreenSource().also { instance = it }
            }
    }
}
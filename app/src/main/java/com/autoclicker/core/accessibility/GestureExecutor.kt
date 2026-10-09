package com.autoclicker.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.autoclicker.core.script.GesturePoint
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 手势执行器：通过无障碍服务派发点击 / 长按 / 滑动手势。
 * 所有方法在服务未连接或派发失败时返回 false，不抛出异常。
 */
object GestureExecutor {

    private const val CLICK_DURATION_MS = 50L

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** 点击指定坐标。 */
    suspend fun click(x: Float, y: Float): Boolean {
        return dispatchLine(x, y, x, y, CLICK_DURATION_MS)
    }

    /** 长按指定坐标，默认 800ms。 */
    suspend fun longPress(x: Float, y: Float, durationMs: Long = 800L): Boolean {
        return dispatchLine(x, y, x, y, durationMs)
    }

    /** 从 (x1, y1) 滑动到 (x2, y2)，默认 300ms。 */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        return dispatchLine(x1, y1, x2, y2, durationMs)
    }

    /**
     * 按完整轨迹派发手势（P0）。
     *
     * 每条 [strokes] 项是一根手指的轨迹点序列，点的 `t` 为相对手势起点的毫秒偏移；
     * 多条轨迹即多指手势，它们的 `t` 决定各自的起始时刻，可重叠。所有轨迹在同一次
     * `dispatchGesture` 中派发，保证多指同步。
     */
    suspend fun dispatchPath(strokes: List<List<GesturePoint>>): Boolean {
        val service = AutoAccessService.instance ?: return false
        if (strokes.isEmpty()) return false
        return try {
            val builder = GestureDescription.Builder()
            var added = 0
            for (points in strokes) {
                if (points.isEmpty()) continue
                val start = points.first().t.coerceAtLeast(0L)
                val end = points.last().t.coerceAtLeast(start)
                val duration = (end - start).coerceAtLeast(1L)
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        lineTo(points[i].x, points[i].y)
                    }
                }
                builder.addStroke(GestureDescription.StrokeDescription(path, start, duration))
                added++
            }
            if (added == 0) return false
            dispatchGesture(service, builder.build())
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun dispatchLine(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Boolean {
        val service = AutoAccessService.instance ?: return false
        val safeDuration = if (durationMs < 1L) 1L else durationMs
        return try {
            val path = Path().apply {
                moveTo(x1, y1)
                if (x1 != x2 || y1 != y2) {
                    lineTo(x2, y2)
                }
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            dispatchGesture(service, gesture)
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun dispatchGesture(
        service: AccessibilityService,
        gesture: GestureDescription
    ): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
            try {
                val accepted = service.dispatchGesture(gesture, callback, mainHandler)
                if (!accepted && continuation.isActive) {
                    continuation.resume(false)
                }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }
}
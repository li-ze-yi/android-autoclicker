package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.domain.model.PixelPoint
import com.autoclicker.platform.GestureExecutor
import com.autoclicker.platform.PixelStroke
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 基于无障碍 [AccessibilityService.dispatchGesture] 的手势派发实现。
 * 通过 [GestureResultCallback] 把异步派发结果转成挂起函数的布尔返回。
 */
class AndroidGestureExecutor(
    private val serviceProvider: () -> AccessibilityService?,
) : GestureExecutor {

    private val handler = Handler(Looper.getMainLooper())

    override fun isReady(): Boolean = serviceProvider() != null

    override suspend fun click(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val description = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceAtLeast(MIN_DURATION_MS),
                ),
            )
            .build()
        return dispatch(description, durationMs.coerceAtLeast(MIN_DURATION_MS))
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val description = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceAtLeast(MIN_DURATION_MS),
                ),
            )
            .build()
        return dispatch(description, durationMs.coerceAtLeast(MIN_DURATION_MS))
    }

    override suspend fun perform(strokes: List<PixelStroke>): Boolean {
        if (strokes.isEmpty()) return false
        val builder = GestureDescription.Builder()
        var added = false
        var endMs = MIN_DURATION_MS
        // 多轨迹必须按开始时间非递减顺序加入。
        strokes.sortedBy { it.startTimeMs }.forEach { stroke ->
            val path = buildPath(stroke.points) ?: return@forEach
            val start = stroke.startTimeMs.coerceAtLeast(0L)
            val duration = stroke.durationMs.coerceAtLeast(MIN_DURATION_MS)
            builder.addStroke(
                GestureDescription.StrokeDescription(path, start, duration),
            )
            endMs = maxOf(endMs, start + duration)
            added = true
        }
        if (!added) return false
        return dispatch(builder.build(), endMs)
    }

    private fun buildPath(points: List<PixelPoint>): Path? {
        if (points.isEmpty()) return null
        val path = Path()
        path.moveTo(points.first().x.toFloat(), points.first().y.toFloat())
        for (i in 1 until points.size) {
            path.lineTo(points[i].x.toFloat(), points[i].y.toFloat())
        }
        return path
    }

    /**
     * 派发手势并等待结果。
     *
     * [expectedMs] 是这次手势本身的持续时间，超时上限取「持续时间 + 余量」。
     * **必须带超时**：`dispatchGesture` 返回 true（已受理）之后，系统的 [AccessibilityService.GestureResultCallback]
     * 有概率一直不回调（服务繁忙、被系统回收等）。没有超时的话协程会永远挂起，
     * 表现为任务在点击那一步静默卡死——日志停在某次点击后再无输出。
     */
    private suspend fun dispatch(description: GestureDescription, expectedMs: Long): Boolean {
        val service = serviceProvider() ?: return false
        val timeoutMs = expectedMs + DISPATCH_TIMEOUT_MARGIN_MS
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val accepted = try {
                    service.dispatchGesture(description, callback, handler)
                } catch (t: Throwable) {
                    false
                }
                if (!accepted && cont.isActive) cont.resume(false)
            }
        }
        if (result == null) {
            RuntimeBus.log(
                LogLevel.WARN,
                "手势派发超时（${timeoutMs}ms 未收到系统回调），跳过等待继续执行下一步",
            )
        }
        return result ?: false
    }

    private companion object {
        const val MIN_DURATION_MS = 1L

        /** 手势回调的额外宽限时间，超过它就认定系统不会回调了。 */
        const val DISPATCH_TIMEOUT_MARGIN_MS = 2500L
    }
}
package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.autoclicker.domain.model.PixelPoint
import com.autoclicker.platform.GestureExecutor
import com.autoclicker.platform.PixelStroke
import kotlinx.coroutines.suspendCancellableCoroutine
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
        return dispatch(description)
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
        return dispatch(description)
    }

    override suspend fun perform(strokes: List<PixelStroke>): Boolean {
        if (strokes.isEmpty()) return false
        val builder = GestureDescription.Builder()
        var added = false
        // 多轨迹必须按开始时间非递减顺序加入。
        strokes.sortedBy { it.startTimeMs }.forEach { stroke ->
            val path = buildPath(stroke.points) ?: return@forEach
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    stroke.startTimeMs.coerceAtLeast(0L),
                    stroke.durationMs.coerceAtLeast(MIN_DURATION_MS),
                ),
            )
            added = true
        }
        if (!added) return false
        return dispatch(builder.build())
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

    private suspend fun dispatch(description: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val service = serviceProvider()
            if (service == null) {
                cont.resume(false)
                return@suspendCancellableCoroutine
            }
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

    private companion object {
        const val MIN_DURATION_MS = 1L
    }
}
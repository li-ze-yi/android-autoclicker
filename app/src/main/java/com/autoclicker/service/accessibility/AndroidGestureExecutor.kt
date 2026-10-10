package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.autoclicker.core.engine.GestureResult
import com.autoclicker.core.engine.GestureExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 基于无障碍服务 dispatchGesture 的真实 [GestureExecutor]。
 *
 * 全部派发在 [Dispatchers.Default] 上执行（不阻塞主线程），
 * 系统回调通过 [suspendCancellableCoroutine] 包装为挂起结果；
 * 协程取消（脚本停止）时结果记为 Cancelled。
 */
class AndroidGestureExecutor : GestureExecutor {

    override suspend fun tap(x: Int, y: Int, holdMs: Long): GestureResult =
        dispatchPointGesture(x, y, holdMs.coerceAtLeast(1L))

    override suspend fun longPress(x: Int, y: Int, durationMs: Long): GestureResult =
        dispatchPointGesture(x, y, durationMs.coerceAtLeast(1L))

    override suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
    ): GestureResult {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatchPath(path, durationMs.coerceAtLeast(1L))
    }

    /** 单点手势：在同一坐标按下并停留 [durationMs] */
    private suspend fun dispatchPointGesture(x: Int, y: Int, durationMs: Long): GestureResult {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchPath(path, durationMs)
    }

    /** 核心：构造单笔手势并派发 */
    private suspend fun dispatchPath(path: Path, durationMs: Long): GestureResult {
        val service = AccessibilityServiceHolder.get()
            ?: return GestureResult.Failed("无障碍服务未连接，无法执行手势")

        val stroke = GestureDescription.StrokeDescription(
            path,
            /* startTime = */ 0L,
            /* duration = */ durationMs,
            /* willContinue = */ false
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gesture: GestureDescription?) {
                        if (cont.isActive) cont.resume(GestureResult.Completed)
                    }

                    override fun onCancelled(gesture: GestureDescription?) {
                        if (cont.isActive) cont.resume(GestureResult.Cancelled)
                    }
                }
                val dispatched = runCatching { service.dispatchGesture(gesture, callback, null) }
                dispatched.exceptionOrNull()?.let { e ->
                    if (cont.isActive) {
                        cont.resume(GestureResult.Failed("手势派发失败：${e.message ?: "未知错误"}"))
                    }
                }
                // 说明：系统未提供取消「已派发手势」的 API；协程取消仅表示不再等待结果，
                // 已在执行中的短手势会自行结束（长手势同理，故引擎应避免在可能停止时下发超长手势）。
            }
        }
    }
}

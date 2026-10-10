package com.autoclicker.core.record

/**
 * 屏幕点（整数坐标）。
 */
data class Point(val x: Int, val y: Int)

/**
 * 归并后的手势结果。
 */
sealed interface RecordedGesture {
    /** 在 (x, y) 处点击 */
    data class Tap(val x: Int, val y: Int) : RecordedGesture

    /** 从 (x1, y1) 滑到 (x2, y2)，手势实际耗时 [durationMs] */
    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long,
    ) : RecordedGesture
}

/**
 * 手势归并器：把精确录制捕获的 按下/移动/抬起 原始事件归并为**一条** [RecordedGesture]。
 *
 * 纯 Kotlin、无 Android 依赖，可直接单测（对应 TR-8.3）。
 *
 * 规则：
 * - 抬起时，若按下后移动距离始终 ≤ [tapSlopPx]，判定为点击；
 * - 一旦移动超过 [tapSlopPx]，判定为滑动，起止点为 按下点 与 抬起点；
 * - 一次 down→up 生命周期只产出一条结果，必须先 [reset] 才能处理下一手势。
 */
class GestureAggregator(
    /** 点击容差（像素）：移动不超过该距离视为点击 */
    private val tapSlopPx: Int = DEFAULT_TAP_SLOP_PX,
) {

    private var downPoint: Point? = null
    private var downTimeMs: Long = 0L
    private var movedBeyondSlop: Boolean = false

    /** 手指按下 */
    fun onDown(x: Int, y: Int, timeMs: Long) {
        downPoint = Point(x, y)
        downTimeMs = timeMs
        movedBeyondSlop = false
    }

    /**
     * 手指移动。
     * 只需判断是否超出点击容差，不保留轨迹（多点滑动由起止点表达即可）。
     */
    fun onMove(x: Int, y: Int) {
        val start = downPoint ?: return
        if (movedBeyondSlop) return
        if (kotlin.math.abs(x - start.x) > tapSlopPx ||
            kotlin.math.abs(y - start.y) > tapSlopPx
        ) {
            movedBeyondSlop = true
        }
    }

    /**
     * 手指抬起：产出归并结果。
     * @throws IllegalStateException 未按下就抬起
     */
    fun onUp(x: Int, y: Int, timeMs: Long): RecordedGesture {
        val start = downPoint
            ?: throw IllegalStateException("收到抬起事件但没有对应的按下事件")
        val durationMs = (timeMs - downTimeMs).coerceAtLeast(1L)
        val result = if (movedBeyondSlop) {
            RecordedGesture.Swipe(
                x1 = start.x, y1 = start.y,
                x2 = x, y2 = y,
                durationMs = durationMs,
            )
        } else {
            RecordedGesture.Tap(start.x, start.y)
        }
        return result
    }

    /** 复位，准备归并下一个手势 */
    fun reset() {
        downPoint = null
        downTimeMs = 0L
        movedBeyondSlop = false
    }

    private companion object {
        /** 默认点击容差（像素） */
        const val DEFAULT_TAP_SLOP_PX: Int = 20
    }
}

package com.autoclicker.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 屏幕目标配置（多目标模式，对标 Automatic Tap 的屏幕控件）。
 *
 * 由悬浮窗中的可拖拽控件呈现；可由 [TargetSpec] 映射生成可执行脚本。
 */
@Serializable
sealed interface TargetSpec {

    /** 目标稳定 ID */
    val id: String

    /**
     * 点击目标。
     *
     * @property x 屏幕绝对坐标 x
     * @property y 屏幕绝对坐标 y
     * @property intervalMs 该目标两次点击间隔（毫秒）
     * @property holdMs 触摸时长（毫秒），设大即长按，建议 < [intervalMs]
     * @property repeats 单点重复次数：该点连续点击多少次后再处理下一目标
     */
    @Serializable
    @SerialName("TapTarget")
    data class TapTarget(
        override val id: String,
        val x: Int,
        val y: Int,
        val intervalMs: Long = DEFAULT_INTERVAL_MS,
        val holdMs: Long = DEFAULT_HOLD_MS,
        val repeats: Int = 1,
    ) : TargetSpec

    /**
     * 滑动目标：从 S1 ([x1], [y1]) 滑到 S2 ([x2], [y2])。
     *
     * @property durationMs 滑动时长（毫秒），建议 >= [MIN_SWIPE_DURATION_MS]
     */
    @Serializable
    @SerialName("SwipeTarget")
    data class SwipeTarget(
        override val id: String,
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long = MIN_SWIPE_DURATION_MS,
    ) : TargetSpec

    companion object {
        /** 默认点击间隔（毫秒） */
        const val DEFAULT_INTERVAL_MS: Long = 100L

        /** 默认触摸时长（毫秒） */
        const val DEFAULT_HOLD_MS: Long = 1L

        /** 建议的最小滑动时长（毫秒） */
        const val MIN_SWIPE_DURATION_MS: Long = 300L

        /** 触摸时长上限（毫秒，60 秒） */
        const val MAX_HOLD_MS: Long = 60_000L
    }
}

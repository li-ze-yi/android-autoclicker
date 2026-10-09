package com.autoclicker.core.clicker

import kotlinx.serialization.Serializable

/** 一个点击点。 */
@Serializable
data class ClickerPoint(
    val id: String,
    val x: Float,
    val y: Float,
    /** 该点按下到抬起的时长（ms）。> 60 时用长按手势派发，否则用点击。 */
    val touchDurationMs: Long = 50L,
    /** 执行该点之前先等待的毫秒数（即上一动作到本动作的间隔）。 */
    val delayBeforeMs: Long = 500L
)

/** 点击器配置。 */
@Serializable
data class ClickerConfig(
    val points: List<ClickerPoint> = emptyList(),
    val loopInfinite: Boolean = false,
    val loopCount: Int = 1,
    val loopIntervalMs: Long = 0L
)

/** 点击器运行状态。 */
sealed interface ClickerState {
    val isRunning: Boolean

    object Idle : ClickerState {
        override val isRunning: Boolean get() = false
    }

    data class Running(
        val loopIndex: Int,     // 从 0 开始
        val totalLoops: Int,    // 无限时为 -1
        val pointIndex: Int,    // 从 0 开始
        val totalPoints: Int
    ) : ClickerState {
        override val isRunning: Boolean get() = true
    }

    data class Finished(val message: String?) : ClickerState {
        override val isRunning: Boolean get() = false
    }
}
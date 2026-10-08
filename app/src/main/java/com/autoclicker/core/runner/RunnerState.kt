package com.autoclicker.core.runner

/**
 * 脚本执行引擎的对外状态。Engine 各 UI 订阅 [ScriptRunner.state] 获取。
 */
sealed interface RunnerState {
    val isActive: Boolean

    object Idle : RunnerState {
        override val isActive: Boolean get() = false
    }

    data class Running(
        val scriptId: String,
        val scriptName: String,
        val stepIndex: Int,
        val totalSteps: Int
    ) : RunnerState {
        override val isActive: Boolean get() = true
    }

    data class Paused(
        val scriptId: String,
        val scriptName: String,
        val stepIndex: Int,
        val totalSteps: Int
    ) : RunnerState {
        override val isActive: Boolean get() = true
    }

    data class Finished(
        val scriptName: String,
        val success: Boolean,
        val message: String?
    ) : RunnerState {
        override val isActive: Boolean get() = false
    }
}
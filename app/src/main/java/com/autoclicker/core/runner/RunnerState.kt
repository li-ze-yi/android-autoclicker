package com.autoclicker.core.runner

/**
 * 脚本执行引擎的对外状态。各 UI 订阅 [ScriptRunner.state] 获取。
 *
 * 运行与暂停合并为同一个 [Running]（用 [Running.paused] 区分），两者的字段原本完全一致，
 * 拆成两个类只会让 when 分支与状态判断重复。
 */
sealed interface RunnerState {
    val isActive: Boolean

    object Idle : RunnerState {
        override val isActive: Boolean get() = false
    }

    data class Running(
        val scriptId: String,
        val scriptName: String,
        val stepIndex: Int,        // 从 0 开始
        val totalSteps: Int,
        val stepText: String,      // 当前步骤可读描述，来自 step.describe()
        val loopIndex: Int,        // 当前第几轮，从 0 开始
        val totalLoops: Int,       // 无限循环时为 -1
        val paused: Boolean = false
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
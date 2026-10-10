package com.autoclicker.ui.home

import com.autoclicker.core.bus.EngineState
import com.autoclicker.domain.model.RepeatPolicy

/** 配置模式：单目标 / 多目标 */
enum class ConfigureMode { SINGLE, MULTI }

/** 重复策略表单选项 */
enum class PolicyKind { COUNT, DURATION, UNTIL_STOPPED }

/**
 * 首页 UI 状态（MVI 单一状态）。
 */
data class HomeUiState(
    val mode: ConfigureMode = ConfigureMode.SINGLE,
    val policyKind: PolicyKind = PolicyKind.UNTIL_STOPPED,
    /** Count：循环次数 */
    val loopCount: Int = 1,
    /** Duration：总运行时长（毫秒） */
    val runDurationMs: Long = 60_000L,
    val engineState: EngineState = EngineState.Idle,
    val accessibilityReady: Boolean = false,
    val overlayReady: Boolean = false,
    val overlayShown: Boolean = false,
) {
    /** 必要权限是否就绪 */
    val permissionsReady: Boolean get() = accessibilityReady && overlayReady

    /** 是否有任务在执行/暂停 */
    val busy: Boolean
        get() = engineState == EngineState.Running ||
            engineState == EngineState.Paused ||
            engineState == EngineState.Recording
}

/** 首页用户意图 */
sealed interface HomeIntent {
    data class SwitchMode(val mode: ConfigureMode) : HomeIntent
    data class SwitchPolicy(val kind: PolicyKind) : HomeIntent
    data class SetLoopCount(val value: Int) : HomeIntent
    data class SetDurationMs(val value: Long) : HomeIntent
    /** 显示/收起悬浮窗 */
    data object ToggleOverlay : HomeIntent
    /** 开始（按当前模式与策略）/ 停止 */
    data object TogglePlay : HomeIntent
    /** 刷新权限状态 */
    data object Refresh : HomeIntent
}

/** 把表单选择转换为领域重复策略 */
fun HomeUiState.toRepeatPolicy(): RepeatPolicy = when (policyKind) {
    PolicyKind.COUNT -> RepeatPolicy.Count(loopCount.coerceAtLeast(1))
    PolicyKind.DURATION -> RepeatPolicy.UntilTime(runDurationMs.coerceAtLeast(0))
    PolicyKind.UNTIL_STOPPED -> RepeatPolicy.UntilStopped
}

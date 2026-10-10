package com.autoclicker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autoclicker.MyApplication
import com.autoclicker.core.permissions.PermissionChecker
import com.autoclicker.service.overlay.OverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel：持有单一 [HomeUiState]，处理 [HomeIntent]。
 *
 * 引擎状态来自 AutomationBus；权限状态由 [PermissionChecker] 快照获取；
 * 播放/停止经 AutomationCoordinator（悬浮窗由 OverlayService 承载）。
 */
class HomeViewModel(private val app: MyApplication) : ViewModel() {

    private val checker = PermissionChecker(app)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // 引擎状态实时同步
        viewModelScope.launch {
            app.bus.engineState.collect { engine ->
                update { it.copy(engineState = engine) }
            }
        }
        refresh()
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            is HomeIntent.SwitchMode -> update { it.copy(mode = intent.mode) }
            is HomeIntent.SwitchPolicy -> update { it.copy(policyKind = intent.kind) }
            is HomeIntent.SetLoopCount ->
                update { it.copy(loopCount = intent.value.coerceAtLeast(1)) }
            is HomeIntent.SetDurationMs ->
                update { it.copy(runDurationMs = intent.value.coerceAtLeast(0)) }

            HomeIntent.ToggleOverlay -> toggleOverlay()
            HomeIntent.TogglePlay -> togglePlay()
            HomeIntent.Refresh -> refresh()
        }
    }

    private fun toggleOverlay() {
        val shown = _state.value.overlayShown
        if (shown) {
            OverlayService.stop(app)
            update { it.copy(overlayShown = false) }
        } else {
            val mode = if (_state.value.mode == ConfigureMode.SINGLE) {
                OverlayService.MODE_SINGLE
            } else {
                OverlayService.MODE_MULTI
            }
            OverlayService.start(app, mode)
            update { it.copy(overlayShown = true) }
        }
    }

    private fun togglePlay() {
        val s = _state.value
        if (s.busy) {
            app.coordinator.stop()
            return
        }
        if (!s.permissionsReady) return // UI 已置灰，双保险
        app.coordinator.desiredPolicy = s.toRepeatPolicy()
        app.coordinator.startWithDesiredPolicy()
    }

    /** 重新检测权限（从系统设置返回时调用） */
    fun refresh() {
        val snap = checker.snapshot()
        update {
            it.copy(
                accessibilityReady = snap.accessibilityEnabled,
                overlayReady = snap.overlayGranted,
            )
        }
    }

    private inline fun update(transform: (HomeUiState) -> HomeUiState) {
        _state.value = transform(_state.value)
    }
}

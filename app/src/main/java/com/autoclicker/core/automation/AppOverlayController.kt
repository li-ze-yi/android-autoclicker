package com.autoclicker.core.automation

import com.autoclicker.MyApplication
import com.autoclicker.core.bus.EngineState
import com.autoclicker.service.overlay.OverlayController

/**
 * 接入真实引擎的 [OverlayController]：悬浮窗操作经 [AutomationCoordinator] 驱动回放，
 * 目标增删转发 TargetController。
 */
class AppOverlayController(private val app: MyApplication) : OverlayController {

    private val coordinator get() = app.coordinator

    override fun onPlay() {
        when (app.bus.engineState.value) {
            EngineState.Paused -> coordinator.resume()
            EngineState.Idle -> coordinator.startWithDesiredPolicy()
            EngineState.Running, EngineState.Recording -> Unit
        }
    }

    override fun onPause() {
        if (app.bus.engineState.value == EngineState.Running) coordinator.pause()
    }

    override fun onStop() {
        coordinator.stop()
    }

    override fun isPaused(): Boolean = app.bus.engineState.value == EngineState.Paused

    override fun onAddTap(x: Int, y: Int): String = app.targetController.addTap(x, y)

    override fun onAddSwipe(x1: Int, y1: Int, x2: Int, y2: Int): String =
        app.targetController.addSwipe(x1, y1, x2, y2)

    override fun onToggleHidden(): Boolean {
        val next = !app.targetController.hidden.value
        app.targetController.setHidden(next)
        return next
    }
}

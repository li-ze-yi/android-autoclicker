package com.autoclicker.service.overlay

import com.autoclicker.platform.OverlayController

/**
 * [OverlayController] 实现：把调用转发给 [OverlayService] 的窗口操作。
 */
class OverlayControllerImpl(
    private val showBallAction: () -> Unit,
    private val hideBallAction: () -> Unit,
    private val ballVisible: () -> Boolean,
    private val showConsoleAction: () -> Unit,
    private val hideConsoleAction: () -> Unit,
) : OverlayController {

    override fun showFloatingBall() = showBallAction()

    override fun hideFloatingBall() = hideBallAction()

    override fun isFloatingBallVisible(): Boolean = ballVisible()

    override fun showConsole() = showConsoleAction()

    override fun hideConsole() = hideConsoleAction()
}
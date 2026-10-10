package com.autoclicker.platform

/** 悬浮窗控制（悬浮球 + 运行控制台）。 */
interface OverlayController {
    fun showFloatingBall()

    fun hideFloatingBall()

    fun isFloatingBallVisible(): Boolean

    fun showConsole()

    fun hideConsole()
}
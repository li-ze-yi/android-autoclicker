package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.di.ServiceLocator

/**
 * 无障碍服务：持有手势/全局操作/节点查询/截屏能力的实现，并在连接与断开时注册/注销到 [ServiceLocator]。
 */
class AutomationAccessibilityService : AccessibilityService() {

    private var gestureExecutor: AndroidGestureExecutor? = null
    private var globalActions: AndroidGlobalActions? = null
    private var nodeLocator: AndroidNodeLocator? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val provider: () -> AccessibilityService? = { instance }

        val executor = AndroidGestureExecutor(provider)
        val actions = AndroidGlobalActions(provider)
        val locator = AndroidNodeLocator(provider)
        gestureExecutor = executor
        globalActions = actions
        nodeLocator = locator

        ServiceLocator.gestureExecutor = executor
        ServiceLocator.globalActions = actions
        ServiceLocator.nodeLocator = locator

        val screen = AccessibilityScreenSource.obtain().also { it.attach(provider) }
        ServiceLocator.screenSource = screen

        RuntimeBus.log(LogLevel.SUCCESS, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 精确录制基于悬浮层捕获原始触摸，不再依赖无障碍事件回调；此处不消费事件。
    }

    override fun onInterrupt() {
        RuntimeBus.log(LogLevel.WARN, "无障碍服务被中断")
        cleanup()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance === this) instance = null

        if (ServiceLocator.gestureExecutor === gestureExecutor) ServiceLocator.gestureExecutor = null
        if (ServiceLocator.globalActions === globalActions) ServiceLocator.globalActions = null
        if (ServiceLocator.nodeLocator === nodeLocator) ServiceLocator.nodeLocator = null

        AccessibilityScreenSource.instance?.detach()
        if (ServiceLocator.screenSource === AccessibilityScreenSource.instance) {
            ServiceLocator.screenSource = null
        }

        gestureExecutor = null
        globalActions = null
        nodeLocator = null
    }

    companion object {
        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set
    }
}
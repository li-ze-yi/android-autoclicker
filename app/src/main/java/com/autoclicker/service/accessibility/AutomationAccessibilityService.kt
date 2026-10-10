package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.autoclicker.MyApplication

/**
 * 唯一的无障碍服务：负责手势派发的底层通道，以及普通录制模式的事件来源。
 *
 * - onServiceConnected：注册实例到 [AccessibilityServiceHolder]、上报状态到 AutomationBus
 * - onAccessibilityEvent：普通录制时由录制器订阅处理（Task 8 接入），本类保持轻量
 * - onInterrupt / onUnbind：清理实例与状态
 */
class AutomationAccessibilityService : AccessibilityService() {

    private val bus
        get() = (applicationContext as MyApplication).bus

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceHolder.attach(this)
        bus.onAccessibilityConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 普通录制模式事件处理在 Task 8 接入（录制器通过总线收集坐标）。
    }

    override fun onInterrupt() {
        // 服务被系统中断：不做重操作，状态在 onUnbind/回收时统一处理。
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityServiceHolder.detach(this)
        bus.onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }
}

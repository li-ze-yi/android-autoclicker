package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.MyApplication
import com.autoclicker.core.bus.EngineState
import com.autoclicker.core.bus.RecordMode
import com.autoclicker.core.record.RecordedGesture

/**
 * 唯一的无障碍服务：负责手势派发的底层通道，以及普通录制模式的事件来源。
 *
 * - onServiceConnected：注册实例到 [AccessibilityServiceHolder]、上报状态到 AutomationBus
 * - onAccessibilityEvent：仅「普通模式录制中」处理 TYPE_VIEW_CLICKED，取节点屏幕坐标中心录入；
 *   回放（Running）派发出的手势因 engineState 不是 Recording 天然不会被录入，杜绝自我级联
 * - onInterrupt / onUnbind：清理实例与状态
 */
class AutomationAccessibilityService : AccessibilityService() {

    private val app
        get() = applicationContext as MyApplication

    private val bus
        get() = app.bus

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceHolder.attach(this)
        bus.onAccessibilityConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // 双重门槛：仅「录制中 + 普通模式」处理。
        // - 回放（Running/Paused）：派发手势产生的事件一律不录入，避免自我级联；
        // - 精确模式：触摸由 PreciseCaptureLayer 捕获，不走本通道。
        if (app.bus.engineState.value != EngineState.Recording) return
        if (app.recorder.mode.value != RecordMode.Normal) return

        // 普通模式只录点击，不录滑动（需在 UI 说明，FR-5）
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return

        // 坐标来源：优先事件来源节点；source 为 null 时回退当前活动窗口根节点
        val node: AccessibilityNodeInfo? = event.source ?: rootInActiveWindow
        // 两种途径都拿不到节点：放弃本次，不崩溃
        if (node == null) return

        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            app.recorder.recordGesture(
                RecordedGesture.Tap(cx, cy),
                timeMs = event.eventTime,
            )
        } finally {
            // AccessibilityNodeInfo 用后必须回收
            node.recycle()
        }
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

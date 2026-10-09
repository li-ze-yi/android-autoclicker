package com.autoclicker.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
// ScriptRecorder 提供录制实现（包名 com.autoclicker.core.recorder），此处仅引用。
import com.autoclicker.core.recorder.ScriptRecorder

/**
 * 无障碍服务：既是实际服务实现，也作为全局单例持有者供引擎各组件获取服务实例。
 *
 * 说明：`AccessibilityService.onMotionEvent` 不在公开 SDK 中（编译期无法覆盖），
 * 因此录制统一走无障碍事件路径，见 [ScriptRecorder.onAccessibilityEvent]。
 */
class AutoAccessService : AccessibilityService() {

    companion object {
        @Volatile
        private var instanceRef: AutoAccessService? = null

        val instance: AutoAccessService?
            get() = instanceRef

        val isConnected: Boolean
            get() = instanceRef != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        ScriptRecorder.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instanceRef = null
        super.onDestroy()
    }
}
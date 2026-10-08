package com.autoclicker.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
// ScriptRecorder 由 Task 5 提供（包名 com.autoclicker.core.recorder），此处仅引用，不在本模块内创建。
import com.autoclicker.core.recorder.ScriptRecorder

/**
 * 无障碍服务：既是实际服务实现，也作为全局单例持有者供引擎各组件获取服务实例。
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
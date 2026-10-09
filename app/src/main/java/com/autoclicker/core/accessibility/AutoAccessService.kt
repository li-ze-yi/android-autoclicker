package com.autoclicker.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
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
        enableRawTouchCapture()
    }

    /** 在 Android 14+ 上请求触摸屏的原始事件流，供录制器精确录制手指轨迹。 */
    private fun enableRawTouchCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            val info = serviceInfo ?: return
            info.setMotionEventSources(InputDevice.SOURCE_TOUCHSCREEN)
            serviceInfo = info
        } catch (e: Exception) {
            // 设备不支持时静默忽略，录制器会退回无障碍事件路径
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        ScriptRecorder.onAccessibilityEvent(event)
    }

    override fun onMotionEvent(event: MotionEvent?) {
        if (event == null) return
        ScriptRecorder.onRawTouch(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instanceRef = null
        super.onDestroy()
    }
}
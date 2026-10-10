package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService

/**
 * 当前已连接无障碍服务实例的进程内持有器。
 *
 * 用途：手势执行器需要拿到 [AccessibilityService] 调用 dispatchGesture，
 * 但无法直接构造服务，故在服务连接时注册、断开时清空。
 *
 * 注意：这里只持有**系统服务实例引用**（生命周期由系统管理），
 * 不存放任何业务可变状态，业务状态统一走 AutomationBus。
 */
object AccessibilityServiceHolder {

    @Volatile
    private var current: AccessibilityService? = null

    /** 注册当前服务实例 */
    fun attach(service: AccessibilityService) {
        current = service
    }

    /** 清空服务实例 */
    fun detach(service: AccessibilityService) {
        if (current === service) current = null
    }

    /** 获取已连接服务；未连接时返回 null */
    fun get(): AccessibilityService? = current
}

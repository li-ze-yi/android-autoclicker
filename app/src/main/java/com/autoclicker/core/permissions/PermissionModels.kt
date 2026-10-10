package com.autoclicker.core.permissions

/**
 * 需要引导用户授予的四类权限（FR-1 / FR-9）。
 *
 * @property Accessibility 无障碍服务：手势派发的唯一通道（仅能检测"系统已启用"，
 *  服务真正连接状态仍以 AutomationBus.accessibilityState 为准）
 * @property Overlay 悬浮窗：悬浮球 / 控制面板 / 目标控件
 * @property Notification 通知：运行状态通知与定时触发提醒
 * @property ExactAlarm 精确闹钟：定时触发准时执行（API 31+）
 */
enum class PermissionKind {
    Accessibility,
    Overlay,
    Notification,
    ExactAlarm
}

/**
 * 四项权限的瞬时状态快照，供 UI 一次性渲染。
 *
 * @property accessibilityEnabled 系统设置中本 App 的无障碍服务是否已被启用
 * @property overlayGranted 是否拥有悬浮窗权限（Settings.canDrawOverlays）
 * @property notificationsEnabled 通知总开关是否开启
 * @property exactAlarmGranted 是否可以调度精确闹钟（API 31 以下恒为 true）
 */
data class PermissionState(
    val accessibilityEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val exactAlarmGranted: Boolean = false
) {
    /** 四项权限是否全部就绪（决定首页"开始"按钮是否可点，AC-1） */
    val allGranted: Boolean
        get() = accessibilityEnabled &&
            overlayGranted &&
            notificationsEnabled &&
            exactAlarmGranted

    /** 读取指定权限项是否已授予，便于 UI 按 [PermissionKind] 统一遍历 */
    fun isGranted(kind: PermissionKind): Boolean = when (kind) {
        PermissionKind.Accessibility -> accessibilityEnabled
        PermissionKind.Overlay -> overlayGranted
        PermissionKind.Notification -> notificationsEnabled
        PermissionKind.ExactAlarm -> exactAlarmGranted
    }
}

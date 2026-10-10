package com.autoclicker.core.permissions

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.autoclicker.service.accessibility.AutomationAccessibilityService

/**
 * 权限检测器（FR-1 权限引导）。
 *
 * - 普通可实例化类，构造时取 applicationContext，避免持有 Activity 造成泄漏。
 * - 无障碍只能检测"系统已启用服务列表中是否包含本服务"，**不代表服务已真正连接**；
 *   运行时真正的连接状态仍以 AutomationBus.accessibilityState 为准。
 * - 各检测均为轻量系统查询，但调用方仍建议放在后台线程（NFR-1）。
 */
class PermissionChecker(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * 系统无障碍设置中是否已启用本 App 的服务。
     *
     * 读取 [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES]（形如
     * "com.app/.ServiceA:com.app/.ServiceB" 的冒号分隔列表），判断其中是否包含
     * 本服务 [AutomationAccessibilityService] 的扁平化 ComponentName。
     */
    fun isAccessibilityConnected(): Boolean {
        val expected = ComponentName(
            appContext,
            AutomationAccessibilityService::class.java
        ).flattenToString()
        val enabled = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(ENABLED_LIST_SEPARATOR)
            .any { it.equals(expected, ignoreCase = true) }
    }

    /** 是否拥有悬浮窗权限（可在其他应用上层显示） */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    /** 通知总开关是否开启（minSdk 26，直接使用 NotificationManagerCompat） */
    fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    /**
     * 是否可以调度精确闹钟：
     * - API 31+：[AlarmManager.canScheduleExactAlarms]
     * - API 31 以下：系统不做该限制，直接返回 true
     */
    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        return alarmManager?.canScheduleExactAlarms() ?: false
    }

    /** 汇总四项权限当前状态，供 UI 渲染 */
    fun snapshot(): PermissionState = PermissionState(
        accessibilityEnabled = isAccessibilityConnected(),
        overlayGranted = canDrawOverlays(),
        notificationsEnabled = notificationsEnabled(),
        exactAlarmGranted = canScheduleExactAlarms()
    )

    /** 跳转系统无障碍设置列表页 */
    fun openAccessibilitySettings() {
        safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** 跳转悬浮窗授权页，并带上本包名直接定位本 App */
    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${appContext.packageName}")
        )
        // 部分 ROM 不支持带包名的悬浮窗授权页，失败时退回不带参数的页面
        if (!safeStart(intent)) {
            safeStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    /**
     * 跳转本 App 的通知渠道设置页（API 26+，minSdk 已为 26）。
     * 个别 ROM 缺少该页面时退回应用详情页，由用户手动进入通知设置。
     */
    fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        if (!safeStart(intent)) {
            openAppDetailSettings()
        }
    }

    /**
     * 跳转精确闹钟授权页（API 31+）。
     * 低版本无需授权；ROM 不支持该 Action 时退回应用详情页。
     */
    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            openAppDetailSettings()
            return
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${appContext.packageName}")
        )
        if (!safeStart(intent)) {
            openAppDetailSettings()
        }
    }

    /** 按 [PermissionKind] 打开对应设置页，便于 UI 统一调用 */
    fun openSettings(kind: PermissionKind) {
        when (kind) {
            PermissionKind.Accessibility -> openAccessibilitySettings()
            PermissionKind.Overlay -> openOverlaySettings()
            PermissionKind.Notification -> openAppNotificationSettings()
            PermissionKind.ExactAlarm -> openExactAlarmSettings()
        }
    }

    /** 跳转应用系统详情页（通知/闹钟设置页的兜底入口） */
    private fun openAppDetailSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${appContext.packageName}")
        )
        safeStart(intent)
    }

    /**
     * 以 NEW_TASK 方式启动设置页（从非 Activity 上下文启动必需）。
     *
     * @return 是否成功找到并启动了目标页面
     */
    private fun safeStart(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            true
        } catch (e: Exception) {
            // ActivityNotFoundException：部分 ROM 裁掉了对应设置页；交由调用方兜底
            false
        }
    }

    private companion object {
        /** 已启用无障碍服务列表的分隔符 */
        const val ENABLED_LIST_SEPARATOR = ":"
    }
}

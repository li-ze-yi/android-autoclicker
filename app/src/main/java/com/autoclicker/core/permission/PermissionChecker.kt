package com.autoclicker.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.autoclicker.di.ServiceLocator
import com.autoclicker.service.accessibility.AutomationAccessibilityService

/** 权限状态查询（无障碍 / 悬浮窗 / 通知 / 屏幕采集 / 精确闹钟）。 */
object PermissionChecker {

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = AutomationAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) || it.endsWith("/$expected") }
    }

    fun isOverlayGranted(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isNotificationGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isScreenCaptureReady(): Boolean = ServiceLocator.screenSource?.isReady() == true

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
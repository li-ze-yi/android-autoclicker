package com.autoclicker.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.di.ServiceLocator
import com.autoclicker.service.accessibility.AutomationAccessibilityService

/**
 * 权限状态查询与守卫（无障碍 / 悬浮窗 / 通知 / 屏幕采集 / 电池优化）。
 *
 * 需要在无障碍或悬浮窗权限的动作（录制、播放、取点、截图建模板、悬浮球）统一走
 * [requireAccessibility] / [requireOverlay]，被拦截时自动 Toast 并写日志。
 */
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

    // ---------------- 守卫 ----------------

    /** 无障碍不可用时的原因；null 表示可用。 */
    fun accessibilityBlocker(context: Context): String? =
        if (isAccessibilityEnabled(context)) null else "无障碍服务未开启：请到「我的 → 权限开启」启用后再试"

    /** 悬浮窗不可用时的原因；null 表示可用。 */
    fun overlayBlocker(context: Context): String? =
        if (isOverlayGranted(context)) null else "悬浮窗权限未开启：请先授权后再试"

    /** 需要无障碍的动作守卫（录制/播放/取点）。返回 true 表示可以继续。 */
    fun requireAccessibility(context: Context): Boolean = !showBlocker(context, accessibilityBlocker(context))

    /** 需要悬浮窗的动作守卫（悬浮球/取点/截图裁剪层）。返回 true 表示可以继续。 */
    fun requireOverlay(context: Context): Boolean = !showBlocker(context, overlayBlocker(context))

    private fun showBlocker(context: Context, reason: String?): Boolean {
        if (reason == null) return false
        Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
        RuntimeBus.log(LogLevel.WARN, reason)
        return true
    }
}
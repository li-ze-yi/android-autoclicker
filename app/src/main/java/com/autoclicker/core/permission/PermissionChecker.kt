package com.autoclicker.core.permission

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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
        val packageName = context.packageName

        // 首选：AccessibilityManager 中「实际启用」的服务列表，各 ROM 都可靠。
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabledServices = manager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (enabledServices?.any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo
                serviceInfo != null &&
                    serviceInfo.packageName == packageName &&
                    serviceInfo.name == expected
            } == true
        ) {
            return true
        }

        // 兜底：读系统设置字符串，同时兼容「包名/全类名」与「包名/.短类名」两种写法。
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val longForm = "$packageName/$expected"
        val shortForm = "$packageName/.${expected.removePrefix("$packageName.")}"
        return raw.split(':').any { entry ->
            val value = entry.trim()
            value.equals(longForm, ignoreCase = true) ||
                value.equals(shortForm, ignoreCase = true) ||
                value.endsWith("/$expected", ignoreCase = true)
        }
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

    // ---------------- 自动跳转 ----------------

    /** 自动化流程所需的权限种类。 */
    enum class Kind { ACCESSIBILITY, OVERLAY }

    /** 返回首个缺失的权限；null 表示都已具备。 */
    fun firstMissing(context: Context): Kind? = when {
        !isAccessibilityEnabled(context) -> Kind.ACCESSIBILITY
        !isOverlayGranted(context) -> Kind.OVERLAY
        else -> null
    }

    /** 对应权限的系统设置页 Intent（带 NEW_TASK，便于从任意 Context 启动）。 */
    fun settingsIntent(context: Context, kind: Kind): Intent {
        val intent = when (kind) {
            Kind.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            Kind.OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /** 自动跳转到对应权限设置页。 */
    fun openSettings(context: Context, kind: Kind) {
        runCatching { context.startActivity(settingsIntent(context, kind)) }
            .onFailure { RuntimeBus.log(LogLevel.WARN, "无法打开权限设置页：${it.message}") }
    }

    fun kindLabel(kind: Kind): String = when (kind) {
        Kind.ACCESSIBILITY -> "无障碍服务"
        Kind.OVERLAY -> "悬浮窗"
    }

    private fun showBlocker(context: Context, reason: String?): Boolean {
        if (reason == null) return false
        Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
        RuntimeBus.log(LogLevel.WARN, reason)
        return true
    }
}
package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.platform.GlobalActions

/**
 * 全局操作实现：返回/桌面/最近任务通过无障碍 [performGlobalAction]，
 * 打开应用走 packageManager 启动入口；关闭应用为尽力而为（仅当前在前台时可回桌面）。
 */
class AndroidGlobalActions(
    private val serviceProvider: () -> AccessibilityService?,
) : GlobalActions {

    override fun back(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    override fun home(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    override fun recents(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

    override fun openApp(packageName: String, activity: String?): Boolean {
        val context = serviceProvider()?.applicationContext ?: return false
        return try {
            val intent: Intent? = if (!activity.isNullOrBlank()) {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(packageName, activity)
                }
            } else {
                context.packageManager.getLaunchIntentForPackage(packageName)
            }
            if (intent == null) {
                RuntimeBus.log(LogLevel.WARN, "打开应用失败：未找到 $packageName 的启动入口")
                return false
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(intent)
            RuntimeBus.log("已打开应用：$packageName")
            true
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "打开应用失败：${t.message}")
            false
        }
    }

    override fun closeApp(packageName: String): Boolean {
        val service = serviceProvider() ?: return false
        val foreground = currentForegroundPackage(service)
        if (foreground == packageName) {
            val ok = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            if (ok) RuntimeBus.log("已返回桌面以关闭应用：$packageName")
            return ok
        }
        RuntimeBus.log(LogLevel.WARN, "关闭应用失败：$packageName 当前不在前台（前台=${foreground ?: "未知"}）")
        return false
    }

    private fun performGlobalAction(action: Int): Boolean {
        val service = serviceProvider() ?: return false
        return try {
            service.performGlobalAction(action)
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.WARN, "全局操作失败：${t.message}")
            false
        }
    }

    private fun currentForegroundPackage(service: AccessibilityService): String? {
        return try {
            service.windows.firstOrNull { it.isActive }?.root?.packageName?.toString()
                ?: service.rootInActiveWindow?.packageName?.toString()
        } catch (t: Throwable) {
            null
        }
    }
}
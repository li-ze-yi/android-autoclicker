package com.autoclicker.core.trigger

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.autoclicker.core.runner.ScriptRunner
import com.autoclicker.core.script.ScriptRepository
import com.autoclicker.core.util.PermissionChecker
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 通知触发配置（P3）：命中来源包名 / 文本关键字后启动指定脚本。
 * 空字符串表示该条件不参与匹配（例如 packageName 为空表示监听所有应用）。
 */
@Serializable
data class NotificationTrigger(
    val enabled: Boolean = false,
    /** 监听的来源包名；空表示不限。 */
    val packageName: String = "",
    /** 通知标题/正文需包含的关键字；空表示不限。 */
    val keyword: String = "",
    /** 命中后要运行的脚本 id；为空则不做任何事。 */
    val scriptId: String = ""
)

/** 通知触发配置的持久化（SharedPreferences + JSON）。 */
object NotificationTriggerStore {

    private const val PREFS_NAME = "autoclicker_notification_trigger"
    private const val KEY_CONFIG = "config"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(context: Context): NotificationTrigger {
        return try {
            val raw = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CONFIG, null) ?: return NotificationTrigger()
            json.decodeFromString(NotificationTrigger.serializer(), raw)
        } catch (e: Exception) {
            NotificationTrigger()
        }
    }

    fun save(context: Context, config: NotificationTrigger) {
        try {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIG, json.encodeToString(NotificationTrigger.serializer(), config))
                .apply()
        } catch (e: Exception) {
            // 忽略写入异常
        }
    }
}

/**
 * 通知触发服务（P3）：监听通知栏，按配置匹配后启动脚本。
 *
 * 需要用户在系统「通知使用权」里授予本应用权限，见 [PermissionChecker.openNotificationAccessSettings]。
 */
class NotificationTriggerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val statusBarNotification = sbn ?: return
            val config = NotificationTriggerStore.load(this)
            if (!config.enabled || config.scriptId.isBlank()) return
            if (config.packageName.isNotBlank() &&
                statusBarNotification.packageName != config.packageName
            ) {
                return
            }
            if (config.keyword.isNotBlank() &&
                !notificationText(statusBarNotification.notification).contains(config.keyword)
            ) {
                return
            }

            val script = ScriptRepository.get(this).load(config.scriptId) ?: return
            if (PermissionChecker.isAccessibilityEnabled(this)) {
                ScriptRunner.start(this, script)
            } else {
                TriggerNotifier.notifyAccessibilityMissing(this, script.name)
            }
        } catch (e: Exception) {
            // 通知回调内不抛异常
        }
    }

    /** 拼接通知的标题与正文用于关键字匹配。 */
    private fun notificationText(notification: Notification?): String {
        val extras = notification?.extras ?: return ""
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        return "$title $text $bigText"
    }
}
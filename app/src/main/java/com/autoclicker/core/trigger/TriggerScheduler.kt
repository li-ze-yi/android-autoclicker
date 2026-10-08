package com.autoclicker.core.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 定时触发记录：某脚本在每天的 hour:minute 触发。
 */
@Serializable
data class TriggerRecord(
    val scriptId: String,
    val scriptName: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true
)

/**
 * 定时任务调度器：负责记录的持久化与 AlarmManager 闹钟的注册/取消。
 * 仅注册「下一次」闹钟，执行时由 AlarmReceiver 重新注册下一天。
 */
class TriggerScheduler private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val EXTRA_SCRIPT_ID = "extra_script_id"
        const val EXTRA_TRIGGER_HOUR = "extra_hour"
        const val EXTRA_TRIGGER_MINUTE = "extra_minute"

        private const val PREFS_NAME = "autoclicker_triggers"
        private const val KEY_RECORDS = "records"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        @Volatile
        private var INSTANCE: TriggerScheduler? = null

        fun get(context: Context): TriggerScheduler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TriggerScheduler(context).also { INSTANCE = it }
            }
        }
    }

    /** 读取全部定时记录，失败返回空列表。 */
    fun list(): List<TriggerRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(TriggerRecord.serializer()), raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 新增或覆盖记录，并注册下一次闹钟（enabled 为 false 时仅保存）。 */
    fun schedule(record: TriggerRecord) {
        val updated = list().toMutableList().apply {
            removeAll { it.scriptId == record.scriptId }
            add(record)
        }
        saveAll(updated)
        if (record.enabled) {
            registerAlarm(record)
        } else {
            cancelAlarm(record.scriptId)
        }
    }

    /** 删除记录并取消闹钟。 */
    fun cancel(scriptId: String) {
        val updated = list().filterNot { it.scriptId == scriptId }
        saveAll(updated)
        cancelAlarm(scriptId)
    }

    /** 启用/停用某条记录。 */
    fun setEnabled(scriptId: String, enabled: Boolean) {
        val records = list()
        val target = records.firstOrNull { it.scriptId == scriptId } ?: return
        val updated = records.map {
            if (it.scriptId == scriptId) it.copy(enabled = enabled) else it
        }
        saveAll(updated)
        if (enabled) {
            registerAlarm(target.copy(enabled = true))
        } else {
            cancelAlarm(scriptId)
        }
    }

    /** 为所有 enabled 的记录重新注册闹钟。 */
    fun rescheduleAll() {
        list().filter { it.enabled }.forEach { registerAlarm(it) }
    }

    /** 计算「今天 hour:minute」，若已过则加一天，返回毫秒时间戳。 */
    fun nextTriggerTimeMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    private fun saveAll(records: List<TriggerRecord>) {
        try {
            prefs.edit()
                .putString(
                    KEY_RECORDS,
                    json.encodeToString(ListSerializer(TriggerRecord.serializer()), records)
                )
                .apply()
        } catch (e: Exception) {
            // 忽略写入异常
        }
    }

    private fun pendingIntent(scriptId: String): PendingIntent {
        val intent = Intent(appContext, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_SCRIPT_ID, scriptId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            scriptId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun registerAlarm(record: TriggerRecord) {
        try {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = nextTriggerTimeMillis(record.hour, record.minute)
            val pi = pendingIntent(record.scriptId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 无精确闹钟权限时退化为非精确闹钟
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            // 注册失败不影响其他记录
        }
    }

    private fun cancelAlarm(scriptId: String) {
        try {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = pendingIntent(scriptId)
            am.cancel(pi)
            pi.cancel()
        } catch (e: Exception) {
            // 忽略
        }
    }
}
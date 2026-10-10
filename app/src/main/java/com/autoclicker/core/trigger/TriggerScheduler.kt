package com.autoclicker.core.trigger

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.util.Log

/**
 * 定时调度器（FR-9）：基于 [AlarmManager] 的精确闹钟。
 *
 * - 使用 [AlarmManager.setExactAndAllowWhileIdle]（RTC_WAKEUP），Doze 待机下
 *   也能在设定时刻唤醒，但系统受限时仍可能存在数十秒到数分钟级偏差，
 *   UI 需如实说明「无法保证精确到秒」。
 * - PendingIntent 为显式 Intent（按类名字符串指向 TriggerReceiver，避免 core
 *   层反向依赖 service 层），requestCode = 条目 id 的稳定哈希，
 * 因此同一 id 反复调度只会覆盖同一个闹钟，取消时也能精确定位。
 * - API 31+ 必须先取得精确闹钟权限（canScheduleExactAlarms），否则
 *   setExact* 会抛 SecurityException；调用方应先查 [canScheduleExact]，
 *   无权限时引导用户去设置（PermissionChecker.openExactAlarmSettings）。
 */
class TriggerScheduler(context: Context) {

    private val appContext = context.applicationContext

    private val alarmManager: AlarmManager =
        appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * 是否可以设置精确闹钟：
     * API 31+ 查 [AlarmManager.canScheduleExactAlarms]；低版本无此限制。
     */
    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager.canScheduleExactAlarms()
    }

    /**
     * 注册（或覆盖）定时闹钟。
     *
     * @throws IllegalArgumentException [Trigger.triggerAtMs] 不晚于当前时间
     * @throws SecurityException API 31+ 未授予精确闹钟权限
     */
    fun schedule(trigger: Trigger) {
        val now = System.currentTimeMillis()
        require(trigger.triggerAtMs > now) { "触发时间必须晚于当前时间" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            throw SecurityException("未授予精确闹钟权限，无法设置定时任务")
        }
        val pendingIntent = alarmPendingIntent(trigger.id)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            trigger.triggerAtMs,
            pendingIntent,
        )
        Log.d(TAG, "已注册定时：id=${trigger.id} at=${trigger.triggerAtMs}")
    }

    /**
     * 取消定时闹钟。
     * 必须以与注册时完全相同的 PendingIntent（action/component/requestCode）
     * 才能取消，故此处复用同一构造方式。
     */
    fun cancel(triggerId: String) {
        alarmManager.cancel(alarmPendingIntent(triggerId))
        Log.d(TAG, "已取消定时：id=$triggerId")
    }

    /**
     * 构造闹钟 PendingIntent：显式指向 TriggerReceiver，携带条目 id。
     * FLAG_IMMUTABLE（系统强制要求）+ FLAG_UPDATE_CURRENT（同 id 覆盖时
     * extras 一并刷新）。
     */
    private fun alarmPendingIntent(triggerId: String): PendingIntent {
        val intent = Intent().apply {
            // 按类名显式指定接收方，避免 core 层编译期依赖 service 层
            setClassName(appContext, RECEIVER_CLASS_NAME)
            action = ACTION_TRIGGER_FIRE
            putExtra(EXTRA_TRIGGER_ID, triggerId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCodeOf(triggerId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "TriggerScheduler"

        /** 触发广播 Action */
        const val ACTION_TRIGGER_FIRE = "com.autoclicker.action.TRIGGER_FIRE"

        /** Intent extra：定时条目 ID */
        const val EXTRA_TRIGGER_ID = "com.autoclicker.extra.TRIGGER_ID"

        /** TriggerReceiver 全限定类名（与实际类保持一致） */
        const val RECEIVER_CLASS_NAME = "com.autoclicker.service.trigger.TriggerReceiver"

        /** 条目 id → AlarmManager requestCode 的稳定映射（通知 id 也复用） */
        fun requestCodeOf(id: String): Int = id.hashCode()
    }
}

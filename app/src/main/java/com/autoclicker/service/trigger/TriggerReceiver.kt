package com.autoclicker.service.trigger

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autoclicker.MainActivity
import com.autoclicker.R
import com.autoclicker.core.data.scripts.ScriptFileRepository
import com.autoclicker.core.notify.NotificationChannels
import com.autoclicker.core.trigger.Trigger
import com.autoclicker.core.trigger.TriggerLaunch
import com.autoclicker.core.trigger.TriggerScheduler
import com.autoclicker.core.trigger.TriggerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 定时闹钟到点接收器（FR-9）。
 *
 * AlarmManager 在设定时刻唤起本接收器后：
 * 1. 读取条目与脚本名，向 CHANNEL_TRIGGER 渠道发高重要性通知
 *    （标题「定时任务」、正文「开始执行：<脚本名>」，点击打开 MainActivity
 *    并携带 [TriggerLaunch.EXTRA_RUN_SCRIPT_ID]）；
 * 2. 重复条目：把触发时间推进到下一个未来时刻并重新注册闹钟；
 *    单次条目：触发后从持久化中删除。
 *
 * 本接收器只发通知，不直接执行脚本（真正运行由用户点击通知 / MainActivity
 * 接入自动运行完成）。广播处理有时限（约 10 秒），使用 goAsync + IO 协程，
 * 全部异常兜底，杜绝崩溃与 ANR。
 */
class TriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TriggerScheduler.ACTION_TRIGGER_FIRE) return
        val triggerId = intent.getStringExtra(TriggerScheduler.EXTRA_TRIGGER_ID) ?: return
        val appContext = context.applicationContext

        // goAsync 让出主线程，处理完必须 finish
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                handleTrigger(appContext, triggerId)
            } catch (t: Throwable) {
                Log.w(TAG, "处理定时触发失败：$triggerId", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** 到点处理：发通知 + 推进/清理条目 */
    private suspend fun handleTrigger(appContext: Context, triggerId: String) {
        val store = TriggerStore(appContext)
        val trigger = store.get(triggerId)
        // 条目不存在（已被删除）或已被开关关闭：什么都不做
        if (trigger == null || !trigger.enabled) return

        val scriptName = ScriptFileRepository(appContext).get(trigger.scriptId)?.name
            ?: "未知脚本"

        postTriggerNotification(appContext, trigger, scriptName)

        if (trigger.isRepeating) {
            // 重复任务：推进到下一个未来时刻并重新注册
            val now = System.currentTimeMillis()
            var nextAt = trigger.triggerAtMs
            while (nextAt <= now) {
                nextAt += trigger.repeatIntervalMs
            }
            val updated = trigger.copy(triggerAtMs = nextAt)
            store.upsert(updated)
            // 权限可能在触发前被用户收回：重排失败不丢数据，
            // 条目已持久化，App 下次打开 / 开机重注册时可恢复
            runCatching { TriggerScheduler(appContext).schedule(updated) }
                .onFailure { Log.w(TAG, "重复任务重新注册失败：${trigger.id}", it) }
        } else {
            // 单次任务触发完毕即清理
            store.delete(trigger.id)
        }
    }

    /** 发送高重要性触发通知，点击后打开 MainActivity 并带脚本 ID extra */
    private fun postTriggerNotification(
        appContext: Context,
        trigger: Trigger,
        scriptName: String,
    ) {
        // 渠道创建幂等，发送前再确保一次（应对极端情况下渠道缺失）
        NotificationChannels.ensure(appContext)

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TriggerLaunch.EXTRA_RUN_SCRIPT_ID, trigger.scriptId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            appContext,
            TriggerScheduler.requestCodeOf(trigger.id),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(appContext, NotificationChannels.CHANNEL_TRIGGER)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("定时任务")
            .setContentText("开始执行：$scriptName")
            // 长脚本名时展开显示完整正文
            .setStyle(NotificationCompat.BigTextStyle().bigText("开始执行：$scriptName"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true) // 点击后自动消失
            .setContentIntent(contentPendingIntent)
            .build()

        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        // POST_NOTIFICATIONS 被拒时 notify 静默无效，包一层避免 ROM 差异
        runCatching {
            notificationManager?.notify(
                TriggerScheduler.requestCodeOf(trigger.id),
                notification,
            )
        }.onFailure { Log.w(TAG, "触发通知发送失败", it) }
    }

    private companion object {
        const val TAG = "TriggerReceiver"
    }
}

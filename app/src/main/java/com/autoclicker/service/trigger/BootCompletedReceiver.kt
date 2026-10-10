package com.autoclicker.service.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autoclicker.core.trigger.TriggerScheduler
import com.autoclicker.core.trigger.TriggerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机完成接收器（FR-9 / AC-10）：开机后重新注册全部定时闹钟。
 *
 * AlarmManager 的闹钟在设备重启后会全部丢失，因此收到 BOOT_COMPLETED 后：
 * - 从 [TriggerStore] 读出所有启用条目；
 * - 单次条目：触发时间已过 → 删除条目；未来时间 → setExact 重新注册；
 * - 重复条目：时间已过 → 按间隔推进到下一个未来时刻（更新持久化）后重新注册，
 *   未过 → 直接重新注册；
 * - 关闭（enabled=false）的条目不注册，保留文件待用户重新打开。
 *
 * 使用 goAsync + IO 协程完成「有限重注册」（仅文件读写与 AlarmManager 调用，
 * 通常几百毫秒内完成，远低于广播时限）。精确闹钟权限缺失时注册会被系统拒绝，
 * 异常逐条吞掉不影响其余条目；过期单次条目的清理不依赖权限，照常执行。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 仅处理开机完成（Manifest 中已注册对应 intent-filter）
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                reregisterAll(appContext)
            } catch (t: Throwable) {
                Log.w(TAG, "开机重注册定时任务失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** 遍历全部条目做清理 / 推进 / 重注册 */
    private suspend fun reregisterAll(appContext: Context) {
        val store = TriggerStore(appContext)
        val scheduler = TriggerScheduler(appContext)
        val now = System.currentTimeMillis()

        val triggers = store.list()
        for (trigger in triggers) {
            if (!trigger.enabled) continue

            if (!trigger.isRepeating) {
                // 单次：已过期清理，未过期重新注册
                if (trigger.triggerAtMs <= now) {
                    store.delete(trigger.id)
                    Log.d(TAG, "清理已过期单次任务：${trigger.id}")
                } else {
                    runCatching { scheduler.schedule(trigger) }
                        .onFailure { Log.w(TAG, "单次任务重注册失败：${trigger.id}", it) }
                }
                continue
            }

            // 重复：若已过期则推进到下一个未来时刻并更新文件
            var nextAt = trigger.triggerAtMs
            if (nextAt <= now) {
                while (nextAt <= now) {
                    nextAt += trigger.repeatIntervalMs
                }
                store.upsert(trigger.copy(triggerAtMs = nextAt))
            }
            runCatching { scheduler.schedule(trigger.copy(triggerAtMs = nextAt)) }
                .onFailure { Log.w(TAG, "重复任务重注册失败：${trigger.id}", it) }
        }
        Log.d(TAG, "开机定时任务重注册完成，共 ${triggers.size} 条")
    }

    private companion object {
        const val TAG = "BootCompletedReceiver"
    }
}

package com.autoclicker.core.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autoclicker.core.runner.ScriptRunner
import com.autoclicker.core.script.ScriptRepository
import com.autoclicker.core.util.PermissionChecker

/**
 * 定时闹钟接收器：加载脚本并执行，随后注册下一天闹钟。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val scriptId = intent.getStringExtra(TriggerScheduler.EXTRA_SCRIPT_ID) ?: return
            val script = ScriptRepository.get(context).load(scriptId) ?: return

            if (PermissionChecker.isAccessibilityEnabled(context)) {
                ScriptRunner.start(context, script)
            } else {
                TriggerNotifier.notifyAccessibilityMissing(context, script.name)
            }

            // 无论执行是否成功，都为下一天重新注册闹钟
            TriggerScheduler.get(context).rescheduleAll()
        } catch (e: Exception) {
            // 保证 onReceive 快速返回且不崩溃
        }
    }
}
package com.autoclicker.core.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启接收器：开机后恢复所有定时闹钟。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            TriggerScheduler.get(context).rescheduleAll()
        } catch (e: Exception) {
            // 忽略
        }
    }
}
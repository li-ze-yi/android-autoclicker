package com.autoclicker.core.trigger

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.autoclicker.R
import com.autoclicker.core.util.NotificationChannels

/**
 * 定时任务相关通知。
 */
object TriggerNotifier {

    const val CHANNEL_ID = "trigger_channel"
    private const val NOTIFICATION_ID = 1001

    /** 创建通知渠道（API 26+）。 */
    fun ensureChannel(context: Context) {
        NotificationChannels.ensure(context, CHANNEL_ID, "定时任务", NotificationManager.IMPORTANCE_DEFAULT)
    }

    /** 提示无障碍服务未开启导致脚本未执行。 */
    fun notifyAccessibilityMissing(context: Context, scriptName: String) {
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("定时任务未执行")
            .setContentText("脚本「$scriptName」未运行：无障碍服务未开启")
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // 通知权限未授权时可能抛异常，静默忽略
        }
    }
}
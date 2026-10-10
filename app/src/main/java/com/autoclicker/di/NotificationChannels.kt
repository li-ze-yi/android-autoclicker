package com.autoclicker.di

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** 通知渠道统一登记。 */
object NotificationChannels {
    const val CHANNEL_RUNTIME = "runtime"
    const val CHANNEL_ALERT = "alert"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(CHANNEL_RUNTIME) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RUNTIME,
                    "运行状态",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "脚本播放与录制的前台服务状态" },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ALERT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT,
                    "提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "权限与异常提醒" },
            )
        }
    }
}
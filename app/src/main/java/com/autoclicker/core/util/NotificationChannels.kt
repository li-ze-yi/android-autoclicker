package com.autoclicker.core.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 通知渠道创建的统一入口（API 26+）。
 *
 * 原先三处（悬浮窗服务、截屏服务、定时通知）各自重复实现渠道创建，收敛到此处。
 * 所有异常均吞掉，失败返回 null，不抛给调用方。
 */
object NotificationChannels {

    /**
     * 确保名为 [channelId] 的渠道存在，并返回 NotificationManager 供调用方继续构建通知。
     * API 26 以下不创建渠道，仅返回 manager。
     */
    fun ensure(
        context: Context,
        channelId: String,
        channelName: String,
        importance: Int
    ): NotificationManager? {
        return try {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(channelId, channelName, importance)
                )
            }
            manager
        } catch (e: Exception) {
            null
        }
    }
}
package com.autoclicker.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.provider.Settings

/**
 * 通知渠道定义与初始化（FR-8 运行状态、FR-9 定时触发）。
 *
 * minSdk 26，渠道 API 可直接使用，无需版本判断。
 * 渠道创建是幂等的：同名渠道重复创建不会覆盖用户后续的手动调整，可安全多次调用。
 */
object NotificationChannels {

    /** 运行状态渠道：脚本运行/录制期间的常驻通知，低重要性、无声 */
    const val CHANNEL_RUNNING = "running"

    /** 定时触发渠道：定时闹钟到点提醒，高重要性、可震动 */
    const val CHANNEL_TRIGGER = "trigger"

    /**
     * 确保两个通知渠道均已创建。建议在 Application.onCreate 及发送通知前调用。
     */
    fun ensure(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 运行状态：低重要性，不发声、不弹窗，仅静默展示
        val runningChannel = NotificationChannel(
            CHANNEL_RUNNING,
            "运行状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "脚本运行或录制时的常驻状态通知"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        // 定时触发：高重要性，发声并震动，确保用户能注意到
        val triggerChannel = NotificationChannel(
            CHANNEL_TRIGGER,
            "定时触发",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "定时任务到达设定时间时的触发提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
            // 系统默认通知铃声；Manifest 已声明 VIBRATE 权限，震动可正常生效
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes)
        }

        notificationManager.createNotificationChannels(
            listOf(runningChannel, triggerChannel)
        )
    }
}

package com.autoclicker

import android.app.Application
import com.autoclicker.core.automation.AppOverlayController
import com.autoclicker.core.automation.AutomationCoordinator
import com.autoclicker.core.bus.AutomationBus
import com.autoclicker.core.record.Recorder
import com.autoclicker.core.targets.TargetController

/**
 * 应用入口。V2 全新架构。
 *
 * 持有全局唯一的 [AutomationBus]、[TargetController]、[AutomationCoordinator]
 * （普通类、此处装配为进程级单例），UI 层与各服务统一通过这些实例通信，禁止另建静态可变状态。
 */
class MyApplication : Application() {

    /** 全局状态中枢（懒加载，进程内唯一） */
    val bus: AutomationBus by lazy { AutomationBus() }

    /** 多目标配置控制器（进程内唯一） */
    val targetController: TargetController by lazy { TargetController() }

    /** 动作录制器（进程内唯一） */
    val recorder: Recorder by lazy { Recorder() }

    /** 自动化总协调器（进程内唯一） */
    val coordinator: AutomationCoordinator by lazy { AutomationCoordinator(this) }

    /** 悬浮窗控制器（真实引擎实现，进程内唯一） */
    val overlayController: AppOverlayController by lazy { AppOverlayController(this) }

    override fun onCreate() {
        super.onCreate()
        // 初始化通知渠道（运行状态 / 定时触发）
        com.autoclicker.core.notify.NotificationChannels.ensure(this)
    }
}

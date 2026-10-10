package com.autoclicker

import android.app.Application
import com.autoclicker.core.bus.AutomationBus

/**
 * 应用入口。V2 全新架构。
 *
 * 持有全局唯一的 [AutomationBus]（普通类、此处装配为进程级单例），
 * UI 层与各服务统一通过该实例通信，禁止另建静态可变状态。
 */
class MyApplication : Application() {

    /** 全局状态中枢（懒加载，进程内唯一） */
    val bus: AutomationBus by lazy { AutomationBus() }

    override fun onCreate() {
        super.onCreate()
    }
}

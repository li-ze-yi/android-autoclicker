package com.autoclicker

import android.app.Application
import com.autoclicker.core.recorder.ScriptRecorder

/**
 * 应用级 Application 入口。
 * 后续可在此初始化日志、依赖容器等全局设施。
 */
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 恢复录制器的持久化开关（精确模式）。
        ScriptRecorder.initialize(this)
    }
}
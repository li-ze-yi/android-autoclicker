package com.autoclicker

import android.app.Application
import com.autoclicker.di.ServiceLocator
import com.autoclicker.engine.DefaultScriptPlayer
import com.autoclicker.vision.VisionModule

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        VisionModule.install(this)
        ServiceLocator.player = DefaultScriptPlayer(this)
    }
}
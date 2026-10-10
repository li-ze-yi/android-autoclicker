package com.autoclicker

import android.app.Application
import com.autoclicker.di.ServiceLocator

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
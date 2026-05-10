package com.elal.demoapp

import android.app.Application
import com.elal.flightsdk.manager.LiveUpdateManager

class ElAlDemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LiveUpdateManager.ensureChannel(this)
        LiveUpdateManager.restoreFromPrefs(this)
    }
}

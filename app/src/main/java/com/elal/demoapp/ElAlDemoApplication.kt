package com.elal.demoapp

import android.app.Application
import android.util.Log
import com.elal.flightsdk.analytics.FlightAnalyticsListener
import com.elal.flightsdk.manager.LiveUpdateManager

class ElAlDemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LiveUpdateManager.ensureChannel(this)
        LiveUpdateManager.analyticsListener = DebugAnalyticsListener
        LiveUpdateManager.restoreFromPrefs(this)
    }
}

private object DebugAnalyticsListener : FlightAnalyticsListener {
    private const val TAG = "FlightAnalytics"

    override fun onLiveActivityActivated(params: Map<String, Any?>) {
        Log.i(TAG, "live_activity_activated ${params.pretty()}")
    }

    override fun onLiveActivityClicked(params: Map<String, Any?>) {
        Log.i(TAG, "live_activity_clicked ${params.pretty()}")
    }

    private fun Map<String, Any?>.pretty(): String =
        entries.joinToString(prefix = "{ ", postfix = " }") { (k, v) -> "$k=$v" }
}

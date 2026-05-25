package com.elal.flightsdk.analytics

/**
 * Customer-supplied sink for Live Activity analytics events.
 *
 * The SDK assembles the event parameters and invokes the listener; the host app
 * forwards them to whatever analytics framework it uses (Firebase, Adjust, etc.).
 *
 * Register via [com.elal.flightsdk.manager.LiveUpdateManager.analyticsListener].
 */
interface FlightAnalyticsListener {

    /**
     * Fired once when the Live Activity is first presented to the user
     * (event name: `live_activity_activated`).
     */
    fun onLiveActivityActivated(params: Map<String, Any?>)

    /**
     * Fired every time the user taps the Live Activity to open the host app
     * (event name: `live_activity_clicked`).
     */
    fun onLiveActivityClicked(params: Map<String, Any?>)
}

/**
 * Where the live-activity click originated.
 * Android currently only surfaces lock-screen / notification-shade taps; Dynamic Island is iOS-only.
 */
object ClickSource {
    const val LOCK_SCREEN = "lock_screen"
}

/** Trigger that caused the live activity to be activated for the first time. */
object TriggerSource {
    /** A server push (e.g. FCM AcceptanceStarted event). */
    const val PUSH = "push"
    /** Time-based local fallback (e.g. 6h-before-departure heuristic). */
    const val FALLBACK_TIME_BASED = "fallback_time_based"
}

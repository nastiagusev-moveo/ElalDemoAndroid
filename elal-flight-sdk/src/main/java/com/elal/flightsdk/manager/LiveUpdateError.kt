package com.elal.flightsdk.manager

sealed class LiveUpdateError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotificationsNotEnabled : LiveUpdateError("Notifications are not enabled")
    class ActivityAlreadyRunning : LiveUpdateError("A live update is already running")
    class NoActiveActivity : LiveUpdateError("No active live update to modify")
    class FailedToStart(cause: Throwable) : LiveUpdateError("Failed to start live update", cause)
}

package com.elal.flightsdk.manager

sealed class LiveUpdateError : Error {
    class NotificationsNotEnabled : LiveUpdateError()
    class ActivityAlreadyRunning : LiveUpdateError()
    class NoActiveActivity : LiveUpdateError()
    class FailedToStart(val cause: Throwable) : LiveUpdateError()
}

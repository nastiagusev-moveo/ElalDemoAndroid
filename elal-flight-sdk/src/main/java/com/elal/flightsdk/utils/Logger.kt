package com.elal.flightsdk.utils

import android.util.Log

class Logger(private val tag: String) {

    fun d(message: String) = Log.d(tag, message)

    fun i(message: String) = Log.i(tag, message)

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable)
        else Log.e(tag, message)
    }
}

package com.elal.flightsdk.utils

import java.time.Instant
import java.time.ZoneId

fun String?.orDash(): String = this ?: "--"

fun Instant?.toFormattedTime(): String {
    if (this == null) return "--"
    val local = atZone(ZoneId.systemDefault())
    return "%d:%02d".format(local.hour, local.minute)
}

fun Instant.toHhMmSs(): String {
    val local = atZone(ZoneId.systemDefault())
    return "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
}

fun Long.toMmSs(): String {
    val totalSec = this / 1000
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}

fun Long.toMsReadable(): String {
    val totalSec = this / 1000
    return "${totalSec / 60}m ${"%02d".format(totalSec % 60)}s"
}

package com.elal.flightsdk.utils

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun String?.orDash(): String = this ?: "--"

private val HH_MM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)

fun Instant?.toFormattedTime(): String {
    if (this == null) return "--"
    return HH_MM.format(this)
}

fun Instant.toHhMmSs(): String {
    val utc = atZone(ZoneOffset.UTC)
    return "%02d:%02d:%02d".format(utc.hour, utc.minute, utc.second)
}

fun Long.toMmSs(): String {
    val totalSec = this / 1000
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}

fun Long.toMsReadable(): String {
    val totalSec = this / 1000
    return "${totalSec / 60}m ${"%02d".format(totalSec % 60)}s"
}

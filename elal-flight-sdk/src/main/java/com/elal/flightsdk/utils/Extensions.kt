package com.elal.flightsdk.utils

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun String?.orDash(): String = this ?: "--"

fun Instant?.toFormattedTime(): String {
    if (this == null) return "--"
    val local = atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .format(local)
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

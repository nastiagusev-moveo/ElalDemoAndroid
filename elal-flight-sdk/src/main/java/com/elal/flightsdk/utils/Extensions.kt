package com.elal.flightsdk.utils

import java.time.Instant
import java.time.ZoneId

fun String?.orDash(): String = this ?: "--"

fun Instant?.toFormattedTime(): String {
    if (this == null) return "--"
    val local = atZone(ZoneId.systemDefault())
    return "%d:%02d".format(local.hour, local.minute)
}

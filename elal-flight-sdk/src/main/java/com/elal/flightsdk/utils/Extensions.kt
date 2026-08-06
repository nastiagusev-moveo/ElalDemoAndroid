package com.elal.flightsdk.utils

import androidx.core.text.BidiFormatter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun String?.orDash(): String = if (isNullOrEmpty()) "--" else this

fun combineLabelAndValue(label: String, value: String?): String = listOfNotNull(
    label.takeIf { it.isNotBlank() },
    value?.takeIf { it.isNotBlank() }?.let { BidiFormatter.getInstance().unicodeWrap(it) }
).joinToString(" ")

fun wallClockNow(): Instant = LocalDateTime.now().toInstant(ZoneOffset.UTC)

/**
 * Re-expresses a naive departure timestamp — which carries *origin airport* local time, e.g. 00:20 at
 * JFK — on the device's wall clock, so it shares one frame with [wallClockNow] and the two can be
 * subtracted. Without this the elapsed flight time is over-counted by the offset gap between the two
 * zones (7h for JFK→TLV), which collapses `flightDuration - elapsed` down to minutes.
 *
 * [originOffset] is the server's `departureTimezoneOffset` ("-04", "+03", "+02:00" are all accepted).
 * Returns the receiver untouched when it is absent or unparseable — preserving the previous behaviour
 * rather than guessing a zone. Display paths keep using the raw value, so the UI still shows
 * airport-local times.
 */
fun Instant.toDeviceWallClock(originOffset: String?): Instant {
    val offset = originOffset?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        ?: return this
    return atZone(ZoneOffset.UTC).toLocalDateTime()      // recover the naive wall-clock fields
        .toInstant(offset)                               // → true instant at the origin airport
        .atZone(ZoneId.systemDefault()).toLocalDateTime()
        .toInstant(ZoneOffset.UTC)                       // → naive again, now on the device's clock
}

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

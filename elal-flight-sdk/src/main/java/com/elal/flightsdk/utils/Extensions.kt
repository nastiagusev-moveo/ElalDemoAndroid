package com.elal.flightsdk.utils

import androidx.core.text.BidiFormatter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun String?.orDash(): String = if (isNullOrEmpty()) "--" else this

fun combineLabelAndValue(label: String, value: String?): String = listOfNotNull(
    label.takeIf { it.isNotBlank() },
    value?.takeIf { it.isNotBlank() }?.let { BidiFormatter.getInstance().unicodeWrap(it) }
).joinToString(" ")

fun wallClockNow(): Instant = LocalDateTime.now().toInstant(ZoneOffset.UTC)

/**
 * Resolves a naive departure timestamp — which carries *origin airport* local time, e.g. 00:20 at
 * JFK — to the real point on the GMT timeline it refers to, using the server's
 * `departureTimezoneOffset` ("-04", "+03", "+02:00" are all accepted) as [originOffset].
 *
 * The result is a true epoch instant, so it is comparable against [System.currentTimeMillis] and any
 * duration derived from it comes out identical on every device: nothing here reads
 * `ZoneId.systemDefault()`, so neither the device's timezone nor a DST transition in it can shift the
 * answer.
 *
 * Returns null when the offset is absent or unparseable — a naive stamp cannot be placed on the
 * timeline at all without it, so callers must fall back rather than guess a zone. Display paths keep
 * using the raw value, so the UI still shows airport-local times.
 */
fun Instant.toAbsoluteInstant(originOffset: String?): Instant? =
    originOffset?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        ?.let {
            atZone(ZoneOffset.UTC).toLocalDateTime()     // recover the naive wall-clock fields
                .toInstant(it)                           // → true instant at the origin airport
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

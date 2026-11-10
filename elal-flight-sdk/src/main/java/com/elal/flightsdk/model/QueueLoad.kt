package com.elal.flightsdk.model

import kotlinx.serialization.Serializable

/**
 * Check-in / self-service queue load level, as sent by the server in
 * `checkInQueueLoad` / `selfServiceQueueLoad` ("Low", "Medium", "High").
 */
@Serializable
enum class QueueLoad {
    LOW, MEDIUM, HIGH;

    companion object {
        /**
         * Parses the server value case-insensitively. Returns null for blank or unknown values so a
         * new server-side level never breaks the FCM parse — the line simply falls back to text only.
         */
        fun fromString(value: String?): QueueLoad? =
            value?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { raw -> entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
    }
}

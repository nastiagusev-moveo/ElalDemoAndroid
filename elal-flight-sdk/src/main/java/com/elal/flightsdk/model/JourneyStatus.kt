package com.elal.flightsdk.model

import androidx.annotation.StringRes
import com.elal.flightsdk.R
import kotlinx.serialization.Serializable

@Serializable
enum class JourneyStatus(@StringRes val displayNameResId: Int, val colorInt: Int) {
    CHECK_IN(R.string.status_check_in,                 0xFF2196F3.toInt()),
    BOARDING_SOON(R.string.status_boarding_soon,       0xFF2196F3.toInt()),
    BOARDING_DELAYED(R.string.status_boarding_delayed, 0xFFFF9800.toInt()),
    BOARDING(R.string.status_boarding,                 0xFF4CAF50.toInt()),
    LAST_CALL(R.string.status_last_call,               0xFFFFC107.toInt()),
    TAKING_OFF(R.string.status_taking_off,             0xFF2196F3.toInt()),
    ON_THE_WAY(R.string.status_on_the_way,             0xFF2196F3.toInt()),
    ARRIVAL(R.string.status_arrival,                   0xFF4CAF50.toInt()),
    CONNECTION_ERROR(R.string.status_connection_error, 0xFFF44336.toInt()),
    GENERAL_ERROR(R.string.status_general_error,       0xFFF44336.toInt())
}

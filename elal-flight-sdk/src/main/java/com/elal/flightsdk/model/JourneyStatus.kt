package com.elal.flightsdk.model

enum class JourneyStatus(val displayName: String, val colorInt: Int) {
    CHECK_IN("Check In", 0xFF2196F3.toInt()),
    BOARDING_SOON("Boarding Soon", 0xFF2196F3.toInt()),
    BOARDING_DELAYED("Boarding Delayed", 0xFFFF9800.toInt()),
    BOARDING("Boarding", 0xFF4CAF50.toInt()),
    LAST_CALL("Last Call", 0xFFFFC107.toInt()),
    TAKING_OFF("Taking Off", 0xFF2196F3.toInt()),
    ON_THE_WAY("On The Way", 0xFF2196F3.toInt()),
    ARRIVAL("Arrival", 0xFF4CAF50.toInt()),
    CONNECTION_ERROR("Connection Error", 0xFFF44336.toInt()),
    GENERAL_ERROR("General Error", 0xFFF44336.toInt())
}

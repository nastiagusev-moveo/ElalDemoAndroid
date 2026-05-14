package com.elal.flightsdk.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.elal.flightsdk.model.FlightContentState
import com.elal.flightsdk.utils.Logger
import kotlinx.serialization.json.Json

internal object JourneyPrefs {

    private const val PREFS_NAME = "journey_prefs"
    private const val KEY_STATE = "state"

    private val log = Logger("JourneyPrefs")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun saveState(context: Context, state: FlightContentState) {
        context.prefs().edit { putString(KEY_STATE, json.encodeToString(FlightContentState.serializer(), state)) }
    }

    fun loadState(context: Context): FlightContentState? {
        val stateJson = context.prefs().getString(KEY_STATE, null) ?: return null
        return try {
            json.decodeFromString<FlightContentState>(stateJson)
        } catch (e: Exception) {
            log.e("loadState: corrupt data, clearing — ${e.message}", e)
            context.prefs().edit { clear() }
            null
        }
    }

    fun clear(context: Context) {
        context.prefs().edit { clear() }
    }

    private fun Context.prefs(): SharedPreferences =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

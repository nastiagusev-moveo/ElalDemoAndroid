# Live Activity — Analytics & Click Handling (Customer Integration)

This document describes what the **host app** (the app that embeds `elal-flight-sdk`)
needs to do so that:

1. Tapping the Live Activity opens the host app.
2. The two analytics events from the spec are delivered to the host app's analytics framework:
   - `live_activity_activated` — fired when the Live Activity is first presented.
   - `live_activity_clicked` — fired every time the user taps the Live Activity.

> **TL;DR:** the SDK handles everything (notification click intent, trampoline activity,
> event assembly). The host app only has to **implement one listener interface** and
> **register it once** at startup.

---

## 1. What the SDK does for you (no integration work required)

- Attaches a `PendingIntent` to the Live Activity notification, so tapping it opens your app.
- Hosts an internal trampoline `Activity` (declared in the SDK manifest) that:
  - fires the `live_activity_clicked` event,
  - then launches your app's main / launcher activity,
  - finishes immediately with no visible transition.
- Resolves your launcher activity automatically via
  `packageManager.getLaunchIntentForPackage(packageName)` — no configuration needed.
- Assembles all event parameters (`flight_number`, `airline`, `pnr`, `current_stage`,
  `flight_status`, `main_message`, `secondary_message`, `dynamic_data_displayed`,
  `is_fallback_state`, `trigger_source`, `click_source`, `timestamp`, `os_platform`)
  from its internal state.

You do not need to declare the trampoline activity, request additional permissions,
or add a `PendingIntent` yourself.

---

## 2. What you **must** do in the host app

### Step 1 — Implement `FlightAnalyticsListener`

The SDK does not depend on any specific analytics framework. You provide a thin adapter
that forwards the assembled parameter map to whatever framework you already use
(Firebase Analytics, Adjust, AppsFlyer, Mixpanel, …).

```kotlin
import com.elal.flightsdk.analytics.FlightAnalyticsListener
import com.google.firebase.analytics.FirebaseAnalytics
import android.os.Bundle

class HostFlightAnalytics(
    private val firebase: FirebaseAnalytics
) : FlightAnalyticsListener {

    override fun onLiveActivityActivated(params: Map<String, Any?>) {
        firebase.logEvent("live_activity_activated", params.toBundle())
    }

    override fun onLiveActivityClicked(params: Map<String, Any?>) {
        firebase.logEvent("live_activity_clicked", params.toBundle())
    }

    private fun Map<String, Any?>.toBundle(): Bundle = Bundle().also { b ->
        for ((k, v) in this) when (v) {
            null -> { /* skip */ }
            is String  -> b.putString(k, v)
            is Long    -> b.putLong(k, v)
            is Int     -> b.putInt(k, v)
            is Boolean -> b.putBoolean(k, v)
            is Double  -> b.putDouble(k, v)
            else       -> b.putString(k, v.toString())
        }
    }
}
```

### Step 2 — Register the listener once at startup

In your `Application.onCreate()` (or any single early-init point):

```kotlin
import com.elal.flightsdk.manager.LiveUpdateManager

class HostApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LiveUpdateManager.analyticsListener = HostFlightAnalytics(FirebaseAnalytics.getInstance(this))
    }
}
```

That's the full required integration.

---

## 3. Optional — open a specific screen instead of the launcher

By default the trampoline launches the activity returned by
`packageManager.getLaunchIntentForPackage(packageName)` — i.e. whatever your manifest
declares as the `MAIN` / `LAUNCHER` activity.

If you want the click to deep-link to a specific screen (e.g. "My Trips" with the
current PNR), set `clickIntentProvider`:

```kotlin
LiveUpdateManager.clickIntentProvider = { ctx ->
    Intent(ctx, MyTripsActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra("source", "live_activity")
    }
}
```

Return `null` from the lambda to fall back to the default launcher behavior.
You may read the current `FlightContentState` from `LiveUpdateManager.stateFlow.value`
if you want to include flight details (PNR, flight number) in the intent.

---

## 4. Optional — pass the correct `trigger_source`

The spec distinguishes two activation triggers:

| Value                   | Meaning                                                                |
|-------------------------|------------------------------------------------------------------------|
| `push`                  | Server push (e.g. FCM `AcceptanceStarted`). **Default.**               |
| `fallback_time_based`   | Local time-based fallback (e.g. 6 h before scheduled departure).       |

`startJourney` defaults to `push`. If your app starts the journey from a
time-based fallback path, pass it explicitly:

```kotlin
import com.elal.flightsdk.analytics.TriggerSource

LiveUpdateManager.startJourney(
    context = applicationContext,
    state = state,
    triggerSource = TriggerSource.FALLBACK_TIME_BASED,
)
```

---

## 5. Event parameter reference

Field names exactly match the analytics spec. Values are derived from the SDK's
internal `FlightContentState`.

### `live_activity_activated`

| Key              | Type     | Source                                                    |
|------------------|----------|-----------------------------------------------------------|
| `timestamp`      | `Long`   | `System.currentTimeMillis()` at event fire                |
| `os_platform`    | `String` | always `"android"`                                        |
| `flight_number`  | `String` | `state.flightNumber`                                      |
| `airline`        | `String` | `state.carrier.name` (e.g. `EL_AL`, `SUN_DOR`)            |
| `pnr`            | `String` | `state.pnr`                                               |
| `trigger_source` | `String` | `"push"` or `"fallback_time_based"` (see §4)              |

### `live_activity_clicked`

| Key                      | Type           | Notes                                                                                                                |
|--------------------------|----------------|----------------------------------------------------------------------------------------------------------------------|
| `timestamp`              | `Long`         | `System.currentTimeMillis()` at click                                                                                |
| `click_source`           | `String`       | always `"lock_screen"` on Android (Dynamic Island is iOS-only)                                                       |
| `os_platform`            | `String`       | always `"android"`                                                                                                   |
| `flight_number`          | `String`       |                                                                                                                      |
| `airline`                | `String`       | `state.carrier.name`                                                                                                 |
| `pnr`                    | `String`       |                                                                                                                      |
| `current_stage`          | `String`       | spec label: `"Check-in open"`, `"Boarding starts"`, `"Last call"`, `"Flight departs"`, `"Arrival"`, `"Carrousel"`, …  |
| `flight_status`          | `String?`      | `"ON-TIME"` / `"DELAYED"` if known, otherwise `null`                                                                 |
| `main_message`           | `String`       | central message shown to the user                                                                                    |
| `secondary_message`      | `String`       | supporting text shown beneath                                                                                        |
| `dynamic_data_displayed` | `String?`      | e.g. `"Gate: E4"`, `"Desk: A101-108"`, `"Zone: 3"`, `"Carousel: 7"`, or `null` if none                               |
| `is_fallback_state`      | `Boolean`      | `true` when the user sees a fallback / error screen (`CONNECTION_ERROR`, `GENERAL_ERROR`)                            |

---

## 6. Android-specific notes

- **Dynamic Island is iOS-only.** On Android the only click surface is the lock
  screen / notification shade, so `click_source` is always `"lock_screen"`.
- **Notification trampolines must be Activities on Android 12+.** The SDK uses an
  invisible `Activity` (`com.elal.flightsdk.analytics.LiveActivityClickActivity`,
  `Theme.NoDisplay`, `noHistory`, `excludeFromRecents`). You don't see it; it
  finishes immediately after firing the event and launching your activity.
- **API level.** Live Activity notifications require Android 16 (API 36). Older
  devices are no-ops; both events simply don't fire there.
- **Notification permission.** The user must grant `POST_NOTIFICATIONS` for any of
  this to run — that's already handled at the start of `startJourney`.

---



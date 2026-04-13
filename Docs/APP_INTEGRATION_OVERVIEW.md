# El Al Flight Journey — App Integration Overview

## What It Does

The El Al Flight Journey SDK delivers real-time flight status to passengers via **Live Activities** (iOS lock screen & Dynamic Island) and **Live Updates** (Android notification shade). A single integration surfaces glanceable journey progress from check-in through to baggage claim.

## Architecture

```
┌───────────────────────────────┐
│        Host Application       │  ← Your app: provides flight data & drives state
├───────────────────────────────┤
│     ElAlFlightSDK / Module    │  ← SDK: manages lifecycle, models, and rendering
├───────────────────────────────┤
│    ActivityKit / ProgressStyle│  ← OS: displays on lock screen / notification shade
└───────────────────────────────┘
```

The SDK sits between your app and the OS. Your app supplies flight data and advances the journey state; the SDK handles all platform-specific rendering and lifecycle management.

## Integration Flow

1. **Initialize** — Import the SDK and perform one-time setup (iOS: enable `NSSupportsLiveActivities`; Android: create the notification channel).
2. **Start** — Call `startJourney()` with static flight attributes (flight number, airports, times) and an initial state (e.g., Check In).
3. **Update** — As the journey progresses, call `updateJourney()` with new state data (gate, seat, boarding time, flight progress, etc.).
4. **End** — Call `endJourney()` when the journey is complete. The activity dismisses automatically or after a configured delay.

The SDK defines **11 journey states**: Check In → Baggage Drop → Checked In → Boarding → Final Call → Taking Off → On The Way → Arrived → Welcome (+ Cancelled / Delayed).

## Data Flow

In production, every state transition is **push-driven**. El Al's backend sends a push notification (APNs on iOS, FCM on Android) carrying the full state payload. The app receives the push, parses the data, and calls `updateJourney()` on the SDK.

```
El Al Backend  ──push──▶  APNs / FCM  ──▶  App  ──▶  SDK.updateJourney()  ──▶  Live Activity / Notification
```

### Push Payload Per State

Each push carries a full JSON payload containing both **static flight attributes** and **dynamic state data**. Below is the expected JSON for each state.

#### Check In
```json
{
  "flightNumber": "LY 317",
  "airline": "El Al",
  "passengerName": "Israel Israeli",
  "departureAirportCode": "TLV",
  "departureCity": "Tel Aviv",
  "arrivalAirportCode": "JFK",
  "arrivalCity": "New York",
  "scheduledDepartureTime": "2026-03-26T14:00:00Z",
  "scheduledArrivalTime": "2026-03-27T01:00:00Z",
  "departureTerminal": "3",
  "arrivalTerminal": "6",
  "status": "checkIn",
  "checkInDesk": "A108",
  "checkInStartTime": "2026-03-26T10:00:00Z"
}
```

#### Baggage Drop
```json
{
  "flightNumber": "LY 317",
  "airline": "El Al",
  "passengerName": "Israel Israeli",
  "departureAirportCode": "TLV",
  "departureCity": "Tel Aviv",
  "arrivalAirportCode": "JFK",
  "arrivalCity": "New York",
  "scheduledDepartureTime": "2026-03-26T14:00:00Z",
  "scheduledArrivalTime": "2026-03-27T01:00:00Z",
  "departureTerminal": "3",
  "arrivalTerminal": "6",
  "status": "baggageDrop",
  "checkInDesk": "B12",
  "checkInStartTime": "2026-03-26T10:30:00Z"
}
```

#### Checked In
```json
{
  "flightNumber": "LY 317",
  "airline": "El Al",
  "passengerName": "Israel Israeli",
  "departureAirportCode": "TLV",
  "departureCity": "Tel Aviv",
  "arrivalAirportCode": "JFK",
  "arrivalCity": "New York",
  "scheduledDepartureTime": "2026-03-26T14:00:00Z",
  "scheduledArrivalTime": "2026-03-27T01:00:00Z",
  "departureTerminal": "3",
  "arrivalTerminal": "6",
  "status": "checkedIn",
  "gate": "D3",
  "terminal": "3",
  "seat": "12A",
  "boardingGroup": "1",
  "boardingTime": "2026-03-26T13:15:00Z",
  "gateClosingTime": "2026-03-26T13:30:00Z"
}
```

#### Boarding
```json
{
  "flightNumber": "LY 317",
  "airline": "El Al",
  "passengerName": "Israel Israeli",
  "departureAirportCode": "TLV",
  "departureCity": "Tel Aviv",
  "arrivalAirportCode": "JFK",
  "arrivalCity": "New York",
  "scheduledDepartureTime": "2026-03-26T14:00:00Z",
  "scheduledArrivalTime": "2026-03-27T01:00:00Z",
  "departureTerminal": "3",
  "arrivalTerminal": "6",
  "status": "boarding",
  "gate": "D3",
  "terminal": "3",
  "seat": "12A",
  "boardingGroup": "1",
  "boardingTime": "2026-03-26T13:15:00Z",
  "gateClosingTime": "2026-03-26T13:50:00Z"
}
```

#### Final Call
```json
{
  "flightNumber": "LY 317",
  "airline": "El Al",
  "passengerName": "Israel Israeli",
  "departureAirportCode": "TLV",
  "departureCity": "Tel Aviv",
  "arrivalAirportCode": "JFK",
  "arrivalCity": "New York",
  "scheduledDepartureTime": "2026-03-26T14:00:00Z",
  "scheduledArrivalTime": "2026-03-27T01:00:00Z",
  "departureTerminal": "3",
  "arrivalTerminal": "6",
  "status": "finalCall",
  "gate": "D3",
  "terminal": "3",
  "seat": "12A",
  "boardingGroup": "1",
  "gateClosingTime": "2026-03-26T13:55:00Z"
}
```

#### Taking Off
```json
{
  "flightNumber": "LY 317",
  "airline": "El Al",
  "passengerName": "Israel Israeli",
  "departureAirportCode": "TLV",
  "departureCity": "Tel Aviv",
  "arrivalAirportCode": "JFK",
  "arrivalCity": "New York",
  "scheduledDepartureTime": "2026-03-26T14:00:00Z",
  "scheduledArrivalTime": "2026-03-27T01:00:00Z",
  "departureTerminal": "3",
  "arrivalTerminal": "6",
  "status": "takingOff",
  "takeoffTime": "2026-03-26T14:07:00Z"
}
```

#### On The Way
Progress is calculated from the flight duration (scheduled departure to estimated arrival). Additional pushes may be sent if the estimated arrival time changes.
```json
{
  "flightNumber": "LY 317",
  "airline": "El Al",
  "passengerName": "Israel Israeli",
  "departureAirportCode": "TLV",
  "departureCity": "Tel Aviv",
  "arrivalAirportCode": "JFK",
  "arrivalCity": "New York",
  "scheduledDepartureTime": "2026-03-26T14:00:00Z",
  "scheduledArrivalTime": "2026-03-27T01:00:00Z",
  "departureTerminal": "3",
  "arrivalTerminal": "6",
  "status": "onTheWay",
  "estimatedArrivalTime": "2026-03-27T01:00:00Z"
}
```

#### Arrived
```json
{
  "flightNumber": "LY 317",
  "airline": "El Al",
  "passengerName": "Israel Israeli",
  "departureAirportCode": "TLV",
  "departureCity": "Tel Aviv",
  "arrivalAirportCode": "JFK",
  "arrivalCity": "New York",
  "scheduledDepartureTime": "2026-03-26T14:00:00Z",
  "scheduledArrivalTime": "2026-03-27T01:00:00Z",
  "departureTerminal": "3",
  "arrivalTerminal": "6",
  "status": "arrived",
  "terminal": "1",
  "estimatedArrivalTime": "2026-03-27T01:00:00Z",
  "baggageCarousel": "25"
}
```

#### Welcome
```json
{
  "flightNumber": "LY 317",
  "airline": "El Al",
  "passengerName": "Israel Israeli",
  "departureAirportCode": "TLV",
  "departureCity": "Tel Aviv",
  "arrivalAirportCode": "JFK",
  "arrivalCity": "New York",
  "scheduledDepartureTime": "2026-03-26T14:00:00Z",
  "scheduledArrivalTime": "2026-03-27T01:00:00Z",
  "departureTerminal": "3",
  "arrivalTerminal": "6",
  "status": "welcome",
  "baggageCarousel": "25"
}
```

#### Delayed
```json
{
  "flightNumber": "LY 317",
  "airline": "El Al",
  "passengerName": "Israel Israeli",
  "departureAirportCode": "TLV",
  "departureCity": "Tel Aviv",
  "arrivalAirportCode": "JFK",
  "arrivalCity": "New York",
  "scheduledDepartureTime": "2026-03-26T14:00:00Z",
  "scheduledArrivalTime": "2026-03-27T01:00:00Z",
  "departureTerminal": "3",
  "arrivalTerminal": "6",
  "status": "delayed",
  "delayMinutes": 55,
  "gate": "D3",
  "boardingTime": "2026-03-26T15:00:00Z",
  "estimatedDepartureTime": "2026-03-26T16:55:00Z",
  "estimatedArrivalTime": "2026-03-27T03:55:00Z"
}
```

#### Cancelled
```json
{
  "flightNumber": "LY 317",
  "airline": "El Al",
  "passengerName": "Israel Israeli",
  "departureAirportCode": "TLV",
  "departureCity": "Tel Aviv",
  "arrivalAirportCode": "JFK",
  "arrivalCity": "New York",
  "scheduledDepartureTime": "2026-03-26T14:00:00Z",
  "scheduledArrivalTime": "2026-03-27T01:00:00Z",
  "departureTerminal": "3",
  "arrivalTerminal": "6",
  "status": "cancelled"
}
```

> **Note:** Every push includes the full static flight attributes plus the state-specific dynamic fields. All timestamps are ISO 8601 UTC. The "On The Way" state receives multiple pushes as progress and altitude update throughout the flight.

> **Demo app:** The demo simulates this flow locally using timers and sample data instead of real push notifications.

## Why the SDK?

Even with push-driven data, the SDK is still needed — though for different reasons on each platform.

### iOS — Required

- **No SDK = no UI.** The lock screen card and Dynamic Island are rendered by the Widget Extension (`JourneyLiveActivity`). Without it, there is nothing to display — the OS has no built-in flight template.
- **ActivityKit needs the data model.** The `JourneyAttributes` type must be compiled into both the app and the widget extension. Push payloads from APNs are decoded against this schema.
- **Branded rendering.** The SDK provides all SwiftUI views, flight path animation, live countdown timers, status-to-color mapping, and icon selection for each of the 11 states.
- **Lifecycle management.** `LiveActivityManager` prevents duplicate activities, handles errors, and controls dismissal policies.

### Android — Recommended

- **You could skip it.** Android's `Notification.ProgressStyle` is system-rendered, so you could technically build the notification directly from push data.
- **But you'd reimplement everything.** The SDK provides:
  - 6-point progress bar with colored segments (maps 11 states to a visual journey)
  - Status-to-color mapping for each progress point
  - Formatted content text per state (e.g., "Gate D3 · Seat 12A · Group 1")
  - Singleton lifecycle management (prevents duplicates, tracks active state)
- **Without the SDK**, you'd need to write all the status-to-UI translation logic yourself.

## Platform Requirements

### iOS
- iOS 16.2+, Swift 5.9, Xcode 15+
- Add `ElAlFlightSDK` as a Swift Package dependency
- Set `NSSupportsLiveActivities = true` in Info.plist
- Create a Widget Extension containing the `JourneyLiveActivity` widget

### Android
- API 36 (Android 16+), Kotlin 2.0+, JDK 17
- Add the `:elal-flight-sdk` Gradle module dependency
- Declare `POST_NOTIFICATIONS` and `POST_PROMOTED_NOTIFICATIONS` permissions
- Call `LiveUpdateManager.ensureChannel(context)` once in `Application.onCreate()`

## Key SDK Entry Points

| | iOS | Android |
|---|---|---|
| **Manager** | `LiveActivityManager.shared` | `LiveUpdateManager` |
| **Start** | `.startJourney(attributes:initialState:)` | `.startJourney(context, attributes, state)` |
| **Update** | `.updateJourney(state:)` | `.updateJourney(context, state)` |
| **End** | `.endJourney(finalState:dismissalPolicy:)` | `.endJourney(context)` |

## Further Reading

See [CLIENT_INTEGRATION_GUIDE.md](./CLIENT_INTEGRATION_GUIDE.md) for full code examples, error handling, data models, and troubleshooting.

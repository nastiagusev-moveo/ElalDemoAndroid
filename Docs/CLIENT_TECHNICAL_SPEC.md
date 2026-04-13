# El Al Flight Journey — Client Technical Specification

## 1. Overview

### What We're Building
A cross-platform SDK that delivers real-time flight journey status to passengers via **Live Activities** (iOS lock screen & Dynamic Island) and **Live Updates** (Android notification shade). The SDK receives push-driven state updates from El Al's backend and renders a branded, glanceable journey experience from check-in through baggage claim.

### Why
Passengers need at-a-glance access to their flight status without opening the app. iOS Live Activities and Android 16 Live Updates provide native, persistent UI surfaces for this purpose.

### Architecture

```
El Al Backend ──push──▶ APNs / FCM ──▶ App ──▶ SDK ──▶ Live Activity / Notification
```

The SDK sits between the app and the OS. The app receives push payloads, parses them, and calls the SDK's lifecycle methods. The SDK handles all rendering, animation, and lifecycle management.

---

## 2. Data Model

### 2.1 Static Attributes

Set once when starting the activity. Immutable for the duration of the journey.

| Field | Type | Required | Example |
|-------|------|----------|---------|
| `flightNumber` | String | Yes | `"LY 317"` |
| `airline` | String | Yes | `"El Al"` |
| `passengerName` | String | Yes | `"Israel Israeli"` |
| `departureAirportCode` | String | Yes | `"TLV"` |
| `departureCity` | String | Yes | `"Tel Aviv"` |
| `arrivalAirportCode` | String | Yes | `"JFK"` |
| `arrivalCity` | String | Yes | `"New York"` |
| `scheduledDepartureTime` | ISO 8601 Date | Yes | `"2026-03-26T14:00:00Z"` |
| `scheduledArrivalTime` | ISO 8601 Date | Yes | `"2026-03-27T01:00:00Z"` |
| `departureTerminal` | String? | No | `"3"` |
| `arrivalTerminal` | String? | No | `"6"` |

### 2.2 Dynamic Content State

Updated with each push notification. Only fields relevant to the current state need to be populated.

| Field | Type | Default | Used By States |
|-------|------|---------|----------------|
| `status` | JourneyStatus enum | — | All |
| `delayMinutes` | Int | `0` | Boarding Delayed |
| `gate` | String? | nil | Boarding Soon, Boarding Delayed, Boarding, Last Call |
| `terminal` | String? | nil | Boarding Soon, Boarding, Last Call, Arrival |
| `seat` | String? | nil | Boarding Soon, Boarding, Last Call |
| `boardingGroup` | String? | nil | Boarding Soon, Boarding, Last Call |
| `checkInDesk` | String? | nil | Check In |
| `checkInStartTime` | ISO 8601 Date? | nil | Check In |
| `boardingTime` | ISO 8601 Date? | nil | Boarding Delayed, Boarding Soon |
| `gateClosingTime` | ISO 8601 Date? | nil | Boarding, Last Call |
| `estimatedDepartureTime` | ISO 8601 Date? | nil | Boarding Delayed |
| `takeoffTime` | ISO 8601 Date? | nil | Taking Off |
| `estimatedArrivalTime` | ISO 8601 Date? | nil | On The Way, Arrival |
| `baggageCarousel` | String? | nil | Arrival |

### 2.3 Flight Progress

Progress is **not** sent in the push payload. It is computed by the SDK from time:

```
progress = (now - scheduledDepartureTime) / (estimatedArrivalTime - scheduledDepartureTime)
clamped to 0.0 ... 1.0
```

This drives the flight path animation (iOS) and progress bar interpolation (Android).

---

## 3. Journey States

### 3.1 State Enum

10 states representing the full passenger journey:

| # | State | Display Name | Badge Label (center) | Normal Flow | Exception |
|---|-------|-------------|---------------------|-------------|-----------|
| 1 | `checkIn` | Check In | Baggage drop | Yes | |
| 2 | `boardingSoon` | Boarding Soon | Boarding soon | Yes | |
| 3 | `boardingDelayed` | Boarding Delayed | Boarding soon | | Yes |
| 4 | `boarding` | Boarding | Boarding | Yes | |
| 5 | `lastCall` | Last Call | Last call | Yes | |
| 6 | `takingOff` | Taking Off | Taking off | Yes | |
| 7 | `onTheWay` | On The Way | On the way | Yes | |
| 8 | `arrival` | Arrival | Arrived | Yes | |
| 9 | `connectionError` | Connection Error | Connection is lost | | Yes |
| 10 | `generalError` | General Error | Something went wrong | | Yes |

Normal flow: Check In → Boarding Soon → Boarding → Last Call → Taking Off → On The Way → Arrival

Exception states: Boarding Delayed can replace Boarding Soon at any point before boarding. Connection Error and General Error can occur at any point.

### 3.2 Required Fields Per State

#### Check In
| Field | Value |
|-------|-------|
| `status` | `checkIn` |
| `checkInDesk` | e.g. `"A108"` |
| `checkInStartTime` | Countdown target |

#### Boarding Soon
| Field | Value |
|-------|-------|
| `status` | `boardingSoon` |
| `gate` | e.g. `"D3"` |
| `terminal` | e.g. `"3"` |
| `seat` | e.g. `"12A"` |
| `boardingGroup` | e.g. `"1"` |
| `boardingTime` | Countdown target |
| `gateClosingTime` | Optional |

#### Boarding Delayed
| Field | Value |
|-------|-------|
| `status` | `boardingDelayed` |
| `delayMinutes` | e.g. `55` |
| `gate` | Optional |
| `boardingTime` | New boarding time |
| `estimatedDepartureTime` | New departure time |
| `estimatedArrivalTime` | New arrival time |

#### Boarding
| Field | Value |
|-------|-------|
| `status` | `boarding` |
| `gate` | e.g. `"D3"` |
| `terminal` | e.g. `"3"` |
| `seat` | e.g. `"12A"` |
| `boardingGroup` | e.g. `"1"` |
| `boardingTime` | When boarding started |
| `gateClosingTime` | Countdown target |

#### Last Call
| Field | Value |
|-------|-------|
| `status` | `lastCall` |
| `gate` | e.g. `"D3"` |
| `terminal` | e.g. `"3"` |
| `seat` | e.g. `"12A"` |
| `boardingGroup` | e.g. `"1"` |
| `gateClosingTime` | Countdown target |

#### Taking Off
| Field | Value |
|-------|-------|
| `status` | `takingOff` |
| `takeoffTime` | Countdown target |

#### On The Way
| Field | Value |
|-------|-------|
| `status` | `onTheWay` |
| `estimatedArrivalTime` | Landing countdown target |

#### Arrival
| Field | Value |
|-------|-------|
| `status` | `arrival` |
| `terminal` | Arrival terminal, e.g. `"1"` |
| `estimatedArrivalTime` | Actual arrival time |
| `baggageCarousel` | e.g. `"25"` |

#### Connection Error
| Field | Value |
|-------|-------|
| `status` | `connectionError` |

#### General Error
| Field | Value |
|-------|-------|
| `status` | `generalError` |

---

## 4. Push Notification Payloads

Every push carries a full JSON payload: **static attributes + dynamic state fields**. All timestamps are ISO 8601 UTC.

### Check In
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

### Boarding Soon
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
  "status": "boardingSoon",
  "gate": "D3",
  "terminal": "3",
  "seat": "12A",
  "boardingGroup": "1",
  "boardingTime": "2026-03-26T13:15:00Z",
  "gateClosingTime": "2026-03-26T13:30:00Z"
}
```

### Boarding Delayed
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
  "status": "boardingDelayed",
  "delayMinutes": 55,
  "gate": "D3",
  "boardingTime": "2026-03-26T15:00:00Z",
  "estimatedDepartureTime": "2026-03-26T16:55:00Z",
  "estimatedArrivalTime": "2026-03-27T03:55:00Z"
}
```

### Boarding
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

### Last Call
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
  "status": "lastCall",
  "gate": "D3",
  "terminal": "3",
  "seat": "12A",
  "boardingGroup": "1",
  "gateClosingTime": "2026-03-26T13:55:00Z"
}
```

### Taking Off
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

### On The Way
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

Additional pushes may be sent if the ETA changes during flight.

### Arrival
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
  "status": "arrival",
  "terminal": "1",
  "estimatedArrivalTime": "2026-03-27T01:00:00Z",
  "baggageCarousel": "25"
}
```

### Connection Error
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
  "status": "connectionError"
}
```

### General Error
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
  "status": "generalError"
}
```

---

## 5. iOS Specification

### 5.1 Platform Requirements

| Requirement | Value |
|-------------|-------|
| Minimum OS | iOS 16.2+ |
| Swift | 5.9+ |
| Xcode | 15+ |
| Framework | ActivityKit, WidgetKit |
| Integration | Swift Package |
| Info.plist | `NSSupportsLiveActivities = true` |
| Widget Extension | Required |

### 5.2 SDK API

```swift
@MainActor
public final class LiveActivityManager: ObservableObject {
    public static let shared: LiveActivityManager

    @Published public var activeJourneyActivity: Activity<JourneyAttributes>?
    public var areActivitiesEnabled: Bool

    // Start a new journey Live Activity
    @discardableResult
    public func startJourney(
        attributes: JourneyAttributes,
        initialState: JourneyAttributes.ContentState
    ) throws -> Activity<JourneyAttributes>

    // Update the current journey state
    public func updateJourney(
        state: JourneyAttributes.ContentState
    ) async throws

    // End the journey
    public func endJourney(
        finalState: JourneyAttributes.ContentState? = nil,
        dismissalPolicy: ActivityUIDismissalPolicy = .default
    ) async throws

    // End all active activities
    public func endAllActivities() async
}
```

**Errors:**
```swift
public enum LiveActivityError: Error {
    case activitiesNotEnabled     // User disabled Live Activities
    case activityAlreadyRunning   // Journey already active
    case noActiveActivity         // No journey to update/end
    case failedToStart(underlying: Error)
}
```

### 5.3 Lock Screen Card Layout (Dark Theme)

```
┌─────────────────────────────────────────────────┐
│ Dark Background rgba(0,0,0,0.8)          96pt  │
│                                                  │
│  [El Al Logo] LY 317          [ON TIME pill]    │  ← Header row
│                                                  │
│  TLV        ✈️ flight path ✈️          JFK      │
│  14:00     [Badge Label]               01:00    │
│  [T3 pill]                                       │
│                                                  │
├─────────────────────────────────────────────────┤
│ Dark Bottom Bar rgba(0,0,0,0.6)          64pt  │  ← or rgba(254,198,48,0.95) for boarding/lastCall
│                                                  │
│  Gate D3                                         │
│  Boarding in: 00:45:23                           │
│                                                  │
└─────────────────────────────────────────────────┘
Corner radius: 24pt
Total height: 160pt
Horizontal padding: 18pt
```

**Header Row (new):**
- Left: El Al logo + flight number
- Right: Status pill — "ON TIME" (green `#2BA45F`), "DELAYED" (yellow `#FEC630`), or "ERROR" (grey `#6C6C6C`)

**Top Section (96pt, dark `rgba(0,0,0,0.8)`):**
- 3-column layout: Departure | Center | Arrival
- Airport code: 18pt medium font
- Time: 13pt regular font, color varies — green (on time), yellow (delayed), grey (error)
- Terminal: shown as pill with pin icon
- Center: Flight path animation + badge label (differs from displayName — see badge label table)

**Bottom Bar (64pt, dark `rgba(0,0,0,0.6)` or yellow `rgba(254,198,48,0.95)` for boarding/lastCall):**
- Content varies per state (see section 5.5)
- No icons — the previous walking/airplane/luggage icons have been removed
- Text colors defined per-state via `bottomBarForegroundColor` and `bottomBarSubtitleColor`

**Note:** The card is now fully dark themed. The previous design (white top / blue bottom) has been replaced.

### 5.4 Dynamic Island

**Expanded Leading (departure airport):**
- Airport code (18pt white)
- Time display (mode-dependent: normal / delayed with strikethrough + new time)
- "On Time" badge (8pt green) — only in normal mode
- Terminal (10pt grey)

**Expanded Trailing (arrival airport):**
- Same structure as leading, for arrival

**Expanded Center:**
- Flight path animation (125pt x 28pt)
- Status badge
- Flight number (11pt grey, 50% opacity)

**Expanded Bottom:**
- Status icon (18x18) + title + subtitle
- El Al logo on right (10pt height, 30% opacity)

**Compact Leading:**
- Departure airport code (12pt medium)

**Compact Trailing:**
- Arrival airport code (normal states)
- Live countdown timer to landing (On The Way state)

**Minimal:**
- Airplane icon (white)

### 5.5 Bottom Bar Content Per State

The bottom bar no longer uses icons. Content and background color vary per state.

| State | Background | Title | Subtitle |
|-------|-----------|-------|----------|
| Check In | `rgba(0,0,0,0.6)` dark | "Desk {desk}" | "Check in closes in {countdown}" |
| Boarding Soon | `rgba(0,0,0,0.6)` dark | "Gate {gate}" | "Boarding in {countdown}" |
| Boarding Delayed | `rgba(0,0,0,0.6)` dark | "Gate {gate}" | "Gate Departure in {countdown}" |
| Boarding | `rgba(254,198,48,0.95)` yellow | "Gate {gate}" | "Boarding now" |
| Last Call | `rgba(254,198,48,0.95)` yellow | "Gate {gate}" | "Gate closing in {countdown}" |
| Taking Off | `rgba(0,0,0,0.6)` dark | — | "Taking off in: {countdown}" |
| On The Way | `rgba(0,0,0,0.6)` dark | — | "Landing in: {countdown}" |
| Arrival | `rgba(0,0,0,0.6)` dark | — | "Baggage Claim: {carousel}" |
| Connection Error | `rgba(0,0,0,0.6)` dark | — | "Connection Error" |
| General Error | `rgba(0,0,0,0.6)` dark | — | "General Error" |

### 5.6 Time Display Modes

The previous `AirportColumnMode` enum and `airportColumnMode` property have been removed. Time display is now controlled by the `timeColor` and `isDelayed` properties on `JourneyStatus`.

| Condition | Time Style | Time Color | Status Pill |
|-----------|-----------|------------|-------------|
| On time (`isDelayed == false`, not error) | Normal text | Green | "ON TIME" (green `#2BA45F`) |
| Delayed (`isDelayed == true`) | Strikethrough + new time below | Yellow | "DELAYED" (yellow `#FEC630`) |
| Error | Normal text | Grey | "ERROR" (grey `#6C6C6C`) |

### 5.7 Flight Path Animation

The flight path is a parabolic arc with an airplane icon positioned based on computed progress (0.0 → 1.0):

```
Airplane X offset = progress * 125.0 - 62.5
Airplane Y: let t = progress → y = 1 + 10 * (2t - 1)²
Rotation: atan(slope) in degrees
```

Canvas size: 125pt x 28pt. Dashed line with dot pattern (1pt dash, 2pt gap).

---

## 6. Android Specification

### 6.1 Platform Requirements

| Requirement | Value |
|-------------|-------|
| Minimum API | 36 (Android 16+) |
| Kotlin | 2.0+ |
| JDK | 17+ |
| Integration | Gradle module dependency |
| Permissions | `POST_NOTIFICATIONS`, `POST_PROMOTED_NOTIFICATIONS` |
| Notification Channel | Required, created in `Application.onCreate()` |

### 6.2 SDK API

```kotlin
object LiveUpdateManager {
    val isActive: Boolean

    // Create notification channel (call once in Application.onCreate())
    fun ensureChannel(context: Context)

    // Start a new journey notification
    @Throws(LiveUpdateError::class)
    fun startJourney(
        context: Context,
        attributes: JourneyAttributes,
        initialState: JourneyContentState
    )

    // Update the current journey state
    @Throws(LiveUpdateError::class)
    fun updateJourney(context: Context, state: JourneyContentState)

    // End the journey (dismisses notification)
    fun endJourney(context: Context)
}
```

**Errors:**
```kotlin
sealed class LiveUpdateError : Exception() {
    class ActivityAlreadyRunning : LiveUpdateError()
    class NoActiveActivity : LiveUpdateError()
}
```

### 6.3 Notification Structure

```
Notification.ProgressStyle
├── Small icon: flight icon drawable
├── Title: "{flightNumber} {status displayName}"
├── Content text: state-specific (see below)
├── Category: CATEGORY_TRANSPORT
├── Ongoing: true
├── Channel: "journey_updates" (HIGH importance)
└── Progress bar:
    ├── Max: 1000
    ├── 7 points at [0, 167, 333, 500, 667, 833, 1000]
    └── 6 segments connecting points
```

### 6.4 Progress Bar Mapping

7 progress points represent journey milestones:

```
[0]────[167]────[333]────[500]────[667]────[833]────[1000]
 │       │        │        │        │        │         │
Check  Boarding  Boarding  Last   Taking   On The    Arrival
 In     Soon              Call     Off      Way
```

| State | Progress Value | Completed Segments |
|-------|---------------|-------------------|
| Check In | 0 | None |
| Boarding Soon | 167 | 0-1 |
| Boarding Delayed | 167 | 0-1 |
| Boarding | 333 | 0-2 |
| Last Call | 500 | 0-3 |
| Taking Off | 667 | 0-4 |
| On The Way | 833 + (time_progress * 167) | 0-5, segment 6 interpolated |
| Arrival | 1000 | All |
| Connection Error | 0 | None (all grey) |
| General Error | 0 | None (all grey) |

Colors: green for completed points/segments, grey for pending.

### 6.5 Notification Content Text Per State

| State | Content Text |
|-------|-------------|
| Check In | `"Desk: {desk} · Check-in open"` |
| Boarding Soon | `"Gate {gate} · Seat {seat} · Group {group} · Terminal {terminal}"` |
| Boarding Delayed | `"Delayed by {delayMinutes} minutes"` |
| Boarding | `"Gate {gate} · Seat {seat} · Group {group}"` |
| Last Call | `"Gate {gate} · Gate closing soon"` |
| Taking Off | `"Taking off soon"` |
| On The Way | `"ETA {estimatedArrivalTime}"` |
| Arrival | `"Carousel {carousel} · Terminal {terminal}"` |
| Connection Error | `"Connection error"` |
| General Error | `"General error"` |

### 6.6 Status Bar Title

Format: `"{flightNumber} {displayName}"`

Example: `"LY 317 Boarding"`, `"LY 317 On The Way"`

---

## 7. UI Design System

### 7.1 Status Pill Colors (Header Row)

The header row status pill replaces the previous per-state badge colors. The old `badgeBackgroundColor` and `badgeForegroundColor` properties have been removed.

| Condition | Pill Text | Pill Background | Pill Foreground |
|-----------|----------|----------------|----------------|
| On time (most states) | "ON TIME" | Green `#2BA45F` | White |
| Delayed (`boardingDelayed`) | "DELAYED" | Yellow `#FEC630` | Dark text |
| Error (`connectionError`, `generalError`) | "ERROR" | Grey `#6C6C6C` | White |

### 7.1.1 Center Badge Labels

The center badge text (`badgeLabel`) differs from `displayName` for several states:

| Status | displayName | badgeLabel (center badge) |
|--------|------------|--------------------------|
| `checkIn` | Check In | Baggage drop |
| `boardingSoon` | Boarding Soon | Boarding soon |
| `boardingDelayed` | Boarding Delayed | Boarding soon |
| `boarding` | Boarding | Boarding |
| `lastCall` | Last Call | Last call |
| `takingOff` | Taking Off | Taking off |
| `onTheWay` | On The Way | On the way |
| `arrival` | Arrival | Arrived |
| `connectionError` | Connection Error | Connection is lost |
| `generalError` | General Error | Something went wrong |

### 7.2 Brand Colors (iOS)

| Name | Value | Usage |
|------|-------|-------|
| Top Section BG | `rgba(0,0,0,0.8)` | Dark card background (was white 96%) |
| Bottom Bar BG (default) | `rgba(0,0,0,0.6)` | Bottom bar for most states (was El Al Blue 92%) |
| Bottom Bar BG (boarding/lastCall) | `rgba(254,198,48,0.95)` | Yellow bottom bar for boarding + lastCall states |
| Status Pill Green | `#2BA45F` | "ON TIME" pill background |
| Status Pill Yellow | `#FEC630` | "DELAYED" pill background |
| Status Pill Grey | `#6C6C6C` | "ERROR" pill background |
| Time Green | (on time) | Time labels when flight is on time |
| Time Yellow | (delayed) | Time labels when flight is delayed |
| Time Grey | (error) | Time labels during error states |
| Light Text | 240, 242, 250 / `#F0F2FA` | Light text on dark backgrounds |
| Subtitle Text | 218, 221, 232 / `#DADDE8` | Subtitle text on dark backgrounds |
| Delayed Yellow | 254, 198, 48 / `#FEC630` | Delayed time text and boarding/lastCall bottom bar |

**Removed:** El Al Blue (`#1B358F`) as bottom bar background, white as top section background, On Time Green (`#008D70`) badge, Error Red (`#D23B39`) badge. The card is now fully dark themed.

### 7.3 Android Status Colors

| Status | Color Int | Material |
|--------|----------|---------|
| Check In | `0xFF2196F3` | Blue |
| Boarding Soon | `0xFF2196F3` | Blue |
| Boarding Delayed | `0xFFF44336` | Red |
| Boarding | `0xFF9C27B0` | Purple |
| Last Call | `0xFFFF9800` | Orange |
| Taking Off | `0xFF1565C0` | Dark Blue |
| On The Way | `0xFF1565C0` | Dark Blue |
| Arrival | `0xFF4CAF50` | Green |
| Connection Error | `0xFFF44336` | Red |
| General Error | `0xFFF44336` | Red |

### 7.4 Typography (iOS)

Font family: PingHL (custom El Al font, falls back to system)

| Usage | Size | Weight |
|-------|------|--------|
| Airport code | 18pt | Medium |
| Time | 13pt | Regular |
| Status badge | 11pt | Medium |
| Flight number | 12pt | Regular |
| Terminal | 10pt | Regular |
| "On Time" badge | 8pt | Medium |
| Bottom bar title | 13pt | Medium |
| Bottom bar subtitle | 11pt | Regular |

### 7.5 Icons

The bottom bar no longer uses per-state icons. The previous `bottomBarIconName` and `bottomBarUsesCircleIcon` properties have been removed.

- **Header row:** El Al logo (left side, alongside flight number)
- **Terminal:** Pin icon inside terminal pill
- **Dynamic Island:** Airplane icon (minimal view), airport codes (compact view)

The walking person, airplane-in-circle, and luggage icons that previously appeared in the bottom bar are no longer used in the Live Activity card layout.

---

## 8. Data Sources

### 8.1 From PNR Retrieve (SOAP API)

| Field | PNR Path |
|-------|----------|
| Flight number | `flightInfo.flightNumber` |
| Airline | `flightInfo.airlineCode` |
| Passenger name | `passengerInfo.firstName` + `lastName` |
| Departure/arrival airports | `flightInfo.departureAirport` / `arrivalAirport` |
| Scheduled times | `flightInfo.scheduledDeparture` / `scheduledArrival` |
| Terminals | `flightInfo.departureTerminal` / `arrivalTerminal` |
| Seat | DCS section: `dcsLegInfo.seatDelivery.seatDetails.seatNumber` |

### 8.2 From External Systems

| Field | Source |
|-------|--------|
| Gate | Airport Operations / DCS |
| Boarding group | DCS / Boarding pass generation |
| Check-in desk | Airport Operations |
| Check-in start time | Flight Operations |
| Boarding time | Flight Operations schedule |
| Gate closing time | Flight Operations |
| Takeoff time | ATC / Flight Tracking |
| Estimated arrival time | Flight Tracking (FlightAware, etc.) |
| Baggage carousel | Arrival Airport Operations |
| City names | IATA airport code lookup table |

### 8.3 DCS Passenger Status Codes

| Code | Meaning |
|------|---------|
| `CAC` / `CAS` | Checked In |
| `BDD` / `BDS` | Boarding Done |
| `BGG` / `CBS` | Baggage |
| `NFL` / `TPS` | Not Flown |

---

## 9. Why the SDK Is Needed

### iOS — Required

The SDK is **not optional** on iOS:

1. **No UI without it.** The lock screen card and Dynamic Island are rendered by the Widget Extension. The OS has no built-in flight template — without the SDK's `JourneyLiveActivity` widget, nothing displays.
2. **ActivityKit needs the compiled data model.** `JourneyAttributes` must be compiled into both the app and widget extension. Push updates from APNs decode against this schema.
3. **All rendering logic.** The SDK provides SwiftUI views, flight path animation (parabolic arc with airplane positioning), live countdown timers, 10-state color/icon mapping, and Dynamic Island region layouts.
4. **Lifecycle management.** Prevents duplicate activities, handles errors, controls dismissal policies.

### Android — Recommended

You *could* build the notification yourself, but the SDK provides:

1. **Progress bar construction.** Maps 10 states to a 7-point progress visualization with colored segments.
2. **Content text formatting.** Translates each state into structured notification text.
3. **Status-to-color mapping.** Each state gets a specific color for the progress indicator.
4. **Lifecycle management.** Singleton pattern, prevents duplicates, tracks active state.

Skipping the SDK means reimplementing all this logic from scratch.

---

## 10. Integration Checklist

### iOS

- [ ] Add `ElAlFlightSDK` as a Swift Package dependency
- [ ] Set `NSSupportsLiveActivities = true` in Info.plist (app + widget extension)
- [ ] Create a Widget Extension target
- [ ] Implement `JourneyLiveActivity` widget in the extension
- [ ] Import `ElAlFlightSDK` and `ActivityKit`
- [ ] Set up push notification handling (APNs)
- [ ] On push received: parse JSON → call `LiveActivityManager.shared.startJourney()` or `.updateJourney()`
- [ ] On journey complete: call `.endJourney()`
- [ ] Handle `LiveActivityError` cases (activities disabled, already running, etc.)

### Android

- [ ] Add `:elal-flight-sdk` as a Gradle module dependency
- [ ] Declare `POST_NOTIFICATIONS` and `POST_PROMOTED_NOTIFICATIONS` in AndroidManifest.xml
- [ ] Request runtime permissions on app launch
- [ ] Call `LiveUpdateManager.ensureChannel(context)` in `Application.onCreate()`
- [ ] Set up FCM push handling
- [ ] On push received: parse JSON → call `LiveUpdateManager.startJourney()` or `.updateJourney()`
- [ ] On journey complete: call `.endJourney()`
- [ ] Handle `LiveUpdateError` cases (already running, no active activity)

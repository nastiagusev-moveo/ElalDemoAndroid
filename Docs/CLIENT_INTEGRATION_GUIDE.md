# El Al Flight Journey — Client Integration Guide

## 1. Quick Start

### iOS — Live Activity in 5 Steps

```swift
// 1. Add ElAlFlightSDK package dependency
// 2. Enable Live Activities in Info.plist: NSSupportsLiveActivities = true
// 3. Create a Widget Extension with JourneyLiveActivity

// 4. Start a journey
let activity = try LiveActivityManager.shared.startJourney(
    attributes: SampleFlightData.journeyAttributes,
    initialState: SampleFlightData.journeySimulationSequence[0]
)

// 5. Update as the journey progresses
try await LiveActivityManager.shared.updateJourney(
    state: SampleFlightData.journeySimulationSequence[1]
)
```

### Android — Live Update in 4 Steps

```kotlin
// 1. Add elal-flight-sdk module dependency
// 2. Request POST_NOTIFICATIONS + POST_PROMOTED_NOTIFICATIONS permissions

// 3. Ensure notification channel exists (call once, e.g., in Application.onCreate)
LiveUpdateManager.ensureChannel(context)

// 4. Start a journey
LiveUpdateManager.startJourney(
    context = context,
    attributes = SampleFlightData.journeyAttributes,
    initialState = SampleFlightData.journeySimulationSequence[0]
)
```

---

## 2. Prerequisites

### iOS

| Requirement | Minimum |
|------------|---------|
| Xcode | 15+ |
| iOS deployment target | 16.2 |
| Swift Tools | 5.9 |
| Device/Simulator | iOS 16.2+ |
| Entitlement | `NSSupportsLiveActivities = true` |

### Android

| Requirement | Minimum |
|------------|---------|
| Android Studio | Latest stable |
| compileSdk | 36 |
| minSdk | 36 |
| Kotlin | 2.0.21 |
| JDK | 17 |
| Device/Emulator | API 36 (Android 16) |
| Permissions | `POST_NOTIFICATIONS`, `POST_PROMOTED_NOTIFICATIONS` |

---

## 3. iOS Implementation

### Step 1: Add ElAlFlightSDK Swift Package

Add the SDK as a local package dependency in your project:

```swift
// In Package.swift or via Xcode: File → Add Package Dependencies
.package(path: "../ElAlFlightSDK")
```

Or reference it in your `project.yml` (XcodeGen):

```yaml
packages:
  ElAlFlightSDK:
    path: ../ElAlFlightSDK
```

Both your main app target and widget extension target need to depend on `ElAlFlightSDK`.

### Step 2: Enable Live Activities in Info.plist

Add to **both** your app and widget extension Info.plist files:

```xml
<key>NSSupportsLiveActivities</key>
<true/>
```

### Step 3: Create Widget Extension

1. In Xcode: File → New → Target → Widget Extension
2. Create the widget bundle entry point:

```swift
import WidgetKit
import SwiftUI
import ElAlFlightSDK

@main
struct MyWidgetBundle: WidgetBundle {
    var body: some Widget {
        JourneyLiveActivity()
    }
}
```

3. Create the `JourneyLiveActivity` widget using `ActivityConfiguration`:

```swift
struct JourneyLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: JourneyAttributes.self) { context in
            // Lock Screen view
            LockScreenView(context: context)
        } dynamicIsland: { context in
            DynamicIsland {
                // Expanded regions
                DynamicIslandExpandedRegion(.leading) {
                    // Status-specific leading content
                }
                DynamicIslandExpandedRegion(.trailing) {
                    // Status-specific trailing content
                }
                DynamicIslandExpandedRegion(.center) {
                    // Flight number + progress
                }
                DynamicIslandExpandedRegion(.bottom) {
                    // Passenger name + status badge
                }
            } compactLeading: {
                Text(context.attributes.departureAirportCode)
            } compactTrailing: {
                Text(context.attributes.arrivalAirportCode)
            } minimal: {
                Image(systemName: "airplane")
            }
        }
    }
}
```

### Step 4: Build Lock Screen + Dynamic Island Views

The SDK provides reusable view components:

```swift
import ElAlFlightSDK

// Flight route visualization (departure → arrival with times)
FlightRouteView(
    departureCode: context.attributes.departureAirportCode,
    arrivalCode: context.attributes.arrivalAirportCode,
    departureTime: context.attributes.scheduledDepartureTime,
    arrivalTime: context.attributes.scheduledArrivalTime,
    delayMinutes: context.state.delayMinutes
)

// Status badge capsule
StatusBadgeView(status: context.state.status)

// Countdown timer (e.g., to boarding time)
CountdownTimerView(
    targetDate: context.state.boardingTime ?? Date(),
    label: "Boarding in",
    isUrgent: context.state.status == .lastCall
)

// Flight progress bar (for on-the-way status)
FlightProgressView(
    departureCode: context.attributes.departureAirportCode,
    arrivalCode: context.attributes.arrivalAirportCode,
    progress: context.state.progress,
    status: .enRoute  // maps visual styling
)
```

**Adapting layout per status:**

```swift
switch context.state.status {
case .checkIn:
    // Show check-in desk, countdown to check-in start
case .boardingSoon:
    // Show gate, seat, boarding group, countdown to boarding
case .boardingDelayed:
    // Show gate, seat, boarding group, delay info
case .boarding:
    // Show gate, seat, boarding group
case .lastCall:
    // Show gate, seat, gate closing countdown
case .takingOff:
    // Show takeoff countdown
case .onTheWay:
    // Show flight progress bar, altitude, ETA
case .arrival:
    // Show baggage carousel, terminal
case .connectionError:
    // Show connection error message
case .generalError:
    // Show general error message
}
```

### Step 5: Manage the Activity Lifecycle

```swift
import ElAlFlightSDK

// Start
let attributes = JourneyAttributes(
    flightNumber: "LY 001",
    passengerName: "Israel Israeli",
    departureAirportCode: "TLV",
    departureCity: "Tel Aviv",
    arrivalAirportCode: "JFK",
    arrivalCity: "New York",
    scheduledDepartureTime: departureDate,
    scheduledArrivalTime: arrivalDate
)

let initialState = JourneyAttributes.ContentState(
    status: .checkIn,
    checkInDesk: "Row 3, Desk 12"
)

let activity = try LiveActivityManager.shared.startJourney(
    attributes: attributes,
    initialState: initialState
)

// Update — advance to next status
let boardingSoonState = JourneyAttributes.ContentState(
    status: .boardingSoon,
    gate: "B22",
    terminal: "3",
    seat: "12A",
    boardingGroup: "1",
    boardingTime: boardingDate,
    gateClosingTime: gateCloseDate
)
try await LiveActivityManager.shared.updateJourney(state: boardingSoonState)

// End — when journey is complete
try await LiveActivityManager.shared.endJourney(
    finalState: arrivalState,
    dismissalPolicy: .default  // or .after(Date()) or .immediate
)
```

### Step 6: Handle Errors

```swift
do {
    let activity = try LiveActivityManager.shared.startJourney(
        attributes: attributes,
        initialState: initialState
    )
} catch LiveActivityError.activitiesNotEnabled {
    // Prompt user to enable Live Activities in Settings
} catch LiveActivityError.activitiesNotSupported {
    // Device doesn't support Live Activities (iOS < 16.1, or iPhone 8/X)
} catch LiveActivityError.activityAlreadyRunning {
    // End the existing activity first, or update it instead
} catch LiveActivityError.failedToStart(let underlying) {
    // Log underlying error for debugging
} catch {
    // Unexpected error
}
```

---

## 4. Android Implementation

### Step 1: Add SDK Module Dependency

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":elal-flight-sdk"))
}
```

Ensure the SDK module is included in `settings.gradle.kts`:

```kotlin
include(":elal-flight-sdk")
```

The SDK requires `compileSdk = 36` and `minSdk = 36`.

### Step 2: Configure Permissions

Add to your app's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />
```

Request permissions at runtime in your Activity:

```kotlin
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Handle permission results
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissions()
        // ... set up UI
    }

    private fun requestNotificationPermissions() {
        val permissions = mutableListOf(Manifest.permission.POST_NOTIFICATIONS)
        try {
            // POST_PROMOTED_NOTIFICATIONS is only available on API 36+
            val field = Manifest.permission::class.java.getField("POST_PROMOTED_NOTIFICATIONS")
            (field.get(null) as? String)?.let { permissions.add(it) }
        } catch (_: Exception) { }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}
```

### Step 3: Create Notification Channel

In your `Application` class:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LiveUpdateManager.ensureChannel(this)
    }
}
```

Register it in `AndroidManifest.xml`:

```xml
<application android:name=".MyApplication" ...>
```

### Step 4: Manage the Live Update Lifecycle

```kotlin
import com.elal.flightsdk.manager.LiveUpdateManager
import com.elal.flightsdk.model.*

// Start
val attributes = JourneyAttributes(
    flightNumber = "LY 001",
    passengerName = "Israel Israeli",
    departureAirportCode = "TLV",
    departureCity = "Tel Aviv",
    arrivalAirportCode = "JFK",
    arrivalCity = "New York",
    scheduledDepartureTime = Instant.now().plus(2, ChronoUnit.HOURS),
    scheduledArrivalTime = Instant.now().plus(13, ChronoUnit.HOURS)
)

val initialState = JourneyContentState(
    status = JourneyStatus.CHECK_IN,
    checkInDesk = "Row 3, Desk 12"
)

LiveUpdateManager.startJourney(context, attributes, initialState)

// Update — advance to next status
val boardingSoonState = JourneyContentState(
    status = JourneyStatus.BOARDING_SOON,
    gate = "B22",
    terminal = "3",
    seat = "12A",
    boardingGroup = "1",
    boardingTime = Instant.now().plus(90, ChronoUnit.MINUTES),
    gateClosingTime = Instant.now().plus(110, ChronoUnit.MINUTES)
)

LiveUpdateManager.updateJourney(context, boardingSoonState)

// End — when journey is complete
LiveUpdateManager.endJourney(context)
```

### Step 5: Handle Errors

```kotlin
try {
    LiveUpdateManager.startJourney(context, attributes, initialState)
} catch (e: LiveUpdateError.NotificationsNotEnabled) {
    // Prompt user to grant notification permissions
} catch (e: LiveUpdateError.ActivityAlreadyRunning) {
    // End the existing update first, or call updateJourney instead
} catch (e: LiveUpdateError.FailedToStart) {
    // Log e.cause for debugging
}

try {
    LiveUpdateManager.updateJourney(context, newState)
} catch (e: LiveUpdateError.NoActiveActivity) {
    // No active update — call startJourney first
}
```

---

## 5. Data Model Reference

### JourneyAttributes (static, set at start)

| Field | iOS (Swift) | Android (Kotlin) | Description |
|-------|-------------|-------------------|-------------|
| `flightNumber` | `String` | `String` | e.g., "LY 001" |
| `airline` | `String` = "El Al" | `String` = "El Al" | Airline name |
| `passengerName` | `String` | `String` | e.g., "Israel Israeli" |
| `departureAirportCode` | `String` | `String` | IATA code, e.g., "TLV" |
| `departureCity` | `String` | `String` | e.g., "Tel Aviv" |
| `arrivalAirportCode` | `String` | `String` | IATA code, e.g., "JFK" |
| `arrivalCity` | `String` | `String` | e.g., "New York" |
| `scheduledDepartureTime` | `Date` | `Instant` | Scheduled departure |
| `scheduledArrivalTime` | `Date` | `Instant` | Scheduled arrival |

### JourneyContentState (dynamic, updated per status)

| Field | Type | Default | Status |
|-------|------|---------|-------|
| `status` | `JourneyStatus` | required | All |
| `delayMinutes` | `Int` | `0` | All (optional metadata) |
| `gate` | `String?` | `nil`/`null` | Boarding Soon, Boarding, Last Call |
| `terminal` | `String?` | `nil`/`null` | Boarding Soon, Arrival |
| `seat` | `String?` | `nil`/`null` | Boarding Soon, Boarding, Last Call |
| `boardingGroup` | `String?` | `nil`/`null` | Boarding Soon, Boarding, Last Call |
| `checkInDesk` | `String?` | `nil`/`null` | Check In |
| `checkInStartTime` | `Date?`/`Instant?` | `nil`/`null` | Check In |
| `boardingTime` | `Date?`/`Instant?` | `nil`/`null` | Boarding Soon, Boarding |
| `gateClosingTime` | `Date?`/`Instant?` | `nil`/`null` | Boarding Soon, Boarding, Last Call |
| `progress` | `Double` (0.0–1.0) | `0.0` | On The Way |
| `currentAltitude` | `String?` | `nil`/`null` | On The Way |
| `estimatedArrivalTime` | `Date?`/`Instant?` | `nil`/`null` | On The Way |
| `baggageCarousel` | `String?` | `nil`/`null` | Arrival |

### JourneyStatus

| iOS | Android | Display Name | Badge Label (center) | Status Pill | Time Color |
|-----|---------|-------------|---------------------|-------------|------------|
| `.checkIn` | `JourneyStatus.CHECK_IN` | Check In | Baggage drop | ON TIME (green `#2BA45F`) | Green |
| `.boardingSoon` | `JourneyStatus.BOARDING_SOON` | Boarding Soon | Boarding soon | ON TIME (green `#2BA45F`) | Green |
| `.boardingDelayed` | `JourneyStatus.BOARDING_DELAYED` | Boarding Delayed | Boarding soon | DELAYED (yellow `#FEC630`) | Yellow |
| `.boarding` | `JourneyStatus.BOARDING` | Boarding | Boarding | ON TIME (green `#2BA45F`) | Green |
| `.lastCall` | `JourneyStatus.LAST_CALL` | Last Call | Last call | ON TIME (green `#2BA45F`) | Green |
| `.takingOff` | `JourneyStatus.TAKING_OFF` | Taking Off | Taking off | ON TIME (green `#2BA45F`) | Green |
| `.onTheWay` | `JourneyStatus.ON_THE_WAY` | On The Way | On the way | ON TIME (green `#2BA45F`) | Green |
| `.arrival` | `JourneyStatus.ARRIVAL` | Arrival | Arrived | ON TIME (green `#2BA45F`) | Green |
| `.connectionError` | `JourneyStatus.CONNECTION_ERROR` | Connection Error | Connection is lost | ERROR (grey `#6C6C6C`) | Grey |
| `.generalError` | `JourneyStatus.GENERAL_ERROR` | General Error | Something went wrong | ERROR (grey `#6C6C6C`) | Grey |

**Note:** The `badgeLabel` (shown in the center badge on the Live Activity card) differs from `displayName` for several states. The status pill appears in the header row of the dark-themed Live Activity card.

---

## 6. Simulation for Testing

Both platforms include `SampleFlightData` with a pre-built 8-state simulation sequence:

| # | Status | Key Fields |
|---|--------|------------|
| 1 | Check In | checkInDesk: "Row 3" |
| 2 | Boarding Soon | gate: "B22", seat: "12A", group: "1" |
| 3 | Boarding | gate: "B22", seat: "12A", group: "1" |
| 4 | Last Call | gate: "B22", gateClosingTime |
| 5 | Taking Off | takeoffTime |
| 6 | On The Way | progress: 0.5, altitude: "36,000 ft" |
| 7 | On The Way | progress: 0.85, altitude: "22,000 ft" |
| 8 | Arrival | progress: 1.0, baggageCarousel: "5" |

### iOS — Run Simulation

```swift
// Using the ViewModel (recommended for demo)
let viewModel = JourneyViewModel()
viewModel.simulateJourney()  // Auto-advances every 5 seconds

// Or manually step through
let sequence = SampleFlightData.journeySimulationSequence
let activity = try LiveActivityManager.shared.startJourney(
    attributes: SampleFlightData.journeyAttributes,
    initialState: sequence[0]
)
for state in sequence.dropFirst() {
    try await Task.sleep(nanoseconds: 5_000_000_000)
    try await LiveActivityManager.shared.updateJourney(state: state)
}
try await LiveActivityManager.shared.endJourney()
```

### Android — Run Simulation

```kotlin
// Using the ViewModel (recommended for demo)
val viewModel: JourneyViewModel = viewModel()
viewModel.simulateJourney()  // Auto-advances every 5 seconds

// Or manually step through
val sequence = SampleFlightData.journeySimulationSequence
LiveUpdateManager.startJourney(
    context, SampleFlightData.journeyAttributes, sequence[0]
)
for (state in sequence.drop(1)) {
    delay(5000)
    LiveUpdateManager.updateJourney(context, state)
}
LiveUpdateManager.endJourney(context)
```

---

## 7. Customization

### Adding New Journey Statuses

To add a status (e.g., "Taxi" between Last Call and Taking Off):

**iOS:**
1. Add a case to `JourneyStatus` in `ElAlFlightSDK/Sources/ElAlFlightSDK/Models/JourneyStatus.swift`
2. Add `displayName` and `color` for the new case
3. Add any new fields to `JourneyAttributes.ContentState`
4. Handle the new case in `JourneyLiveActivity.swift` (Lock Screen + Dynamic Island layouts)
5. Update `SampleFlightData` with a new simulation state

**Android:**
1. Add a value to `JourneyStatus` in `elal-flight-sdk/.../model/JourneyStatus.kt`
2. Add any new fields to `JourneyContentState`
3. Update `LiveUpdateManager.buildProgressStyle()` to add a new point/segment
4. Update `LiveUpdateManager.buildContentText()` for the new status
5. Update `SampleFlightData` with a new simulation state

### Changing Colors

The Live Activity card uses a fully dark theme. The top section background is `rgba(0,0,0,0.8)` and the bottom bar background is `rgba(0,0,0,0.6)` for most states (or `rgba(254,198,48,0.95)` yellow for boarding/lastCall). Status pill colors, time colors, and bottom bar colors are all defined per-state on the `JourneyStatus` enum.

**iOS:** Edit `JourneyStatus.swift` properties (`statusPillBackgroundColor`, `statusPillForegroundColor`, `timeColor`, `bottomBarBackgroundColor`, `bottomBarForegroundColor`, `bottomBarSubtitleColor`) or `ElAlSDKConfiguration.swift`:
```swift
public static let primaryColor = Color(hex: "#YOUR_COLOR")
```

**Android:** Edit `JourneyStatus.kt` colorInt values or `Color.kt`:
```kotlin
val ElAlBlue = Color(0xFFYOUR_COLOR)
```

### Modifying Notification Content Text (Android)

Edit `LiveUpdateManager.buildContentText()`:

```kotlin
private fun buildContentText(state: JourneyContentState): String = when (state.status) {
    JourneyStatus.CHECK_IN -> "Your custom check-in text: ${state.checkInDesk}"
    JourneyStatus.BOARDING_SOON -> "Your custom gate text: ${state.gate}"
    JourneyStatus.BOARDING_DELAYED -> "Your custom delay text: ${state.gate}"
    JourneyStatus.BOARDING -> "Your custom boarding text: ${state.gate}"
    JourneyStatus.LAST_CALL -> "Your custom last call text: ${state.gate}"
    JourneyStatus.TAKING_OFF -> "Your custom taking off text"
    JourneyStatus.ON_THE_WAY -> "Your custom flight text: ${state.currentAltitude}"
    JourneyStatus.ARRIVAL -> "Your custom arrival text: ${state.baggageCarousel}"
    JourneyStatus.CONNECTION_ERROR -> "Your custom connection error text"
    JourneyStatus.GENERAL_ERROR -> "Your custom general error text"
}
```

### Modifying Lock Screen Layout (iOS)

Edit `JourneyLiveActivity.swift` — the Lock Screen view is in the first closure of `ActivityConfiguration`. Each status has a `switch` branch you can customize with any SwiftUI views.

---

## 8. Cross-Platform Parity

### API Mapping

| Operation | iOS | Android |
|-----------|-----|---------|
| **Manager** | `LiveActivityManager.shared` | `LiveUpdateManager` |
| **Start** | `.startJourney(attributes:initialState:)` | `.startJourney(context, attributes, initialState)` |
| **Update** | `.updateJourney(state:)` (async) | `.updateJourney(context, state)` |
| **End** | `.endJourney(finalState:dismissalPolicy:)` (async) | `.endJourney(context)` |
| **Check active** | `.activeJourneyActivity != nil` | `.isActive` |
| **Check enabled** | `.areActivitiesEnabled` | N/A (check permission instead) |
| **End all** | `.endAllActivities()` (async) | N/A (single activity only) |
| **Channel setup** | N/A | `.ensureChannel(context)` |

### Key Differences

| Aspect | iOS | Android |
|--------|-----|---------|
| Async model | `async throws` | Synchronous (throws exceptions) |
| State type | Nested `JourneyAttributes.ContentState` | Standalone `JourneyContentState` |
| Time type | `Date` | `java.time.Instant` |
| Error type | `enum LiveActivityError` (7 cases) | `sealed class LiveUpdateError` (4 subtypes) |
| End method | Accepts final state + dismissal policy | No final state, immediate cancel |
| Multiple activities | Supports flight tracker + boarding + journey simultaneously | Single notification only |
| Context parameter | Not needed (uses ActivityKit) | Required (`Context`) on every call |

---

## 9. Troubleshooting

### iOS

| Issue | Cause | Solution |
|-------|-------|----------|
| `activitiesNotEnabled` error | User disabled Live Activities | Guide user to Settings → App → Live Activities → Enable |
| `activitiesNotSupported` error | iOS < 16.1 or unsupported device | Check `ActivityAuthorizationInfo().areActivitiesEnabled` before calling start |
| Activity doesn't appear | Missing `NSSupportsLiveActivities` | Add `<key>NSSupportsLiveActivities</key><true/>` to **both** app and widget Info.plist |
| Widget extension not found | Missing app group or bundle ID | Ensure widget extension bundle ID is `$(APP_BUNDLE_ID).widget` |
| Custom fonts not rendering | Fonts not registered | Add font files to both targets and list them in `UIAppFonts` in Info.plist |
| Activity disappears after 12h | System limit | Activities auto-end after 12 hours; design for shorter journeys or restart |
| Lock Screen view not updating | Stale state | Ensure `updateJourney()` is called with a new `ContentState` instance |
| Build error: "No such module" | Package not linked | Add `ElAlFlightSDK` dependency to both app and widget extension targets |
| Dynamic Island not showing | Simulator limitation | Test on a physical iPhone 14 Pro or later |
| XcodeGen project won't build | Missing generation step | Run `xcodegen generate` in `ElAlDemoApp/` before opening in Xcode |

### Android

| Issue | Cause | Solution |
|-------|-------|----------|
| Notification doesn't appear | Missing permission | Request `POST_NOTIFICATIONS` at runtime; check `NotificationManager.areNotificationsEnabled()` |
| ProgressStyle not rendering | Wrong API level | `Notification.ProgressStyle` requires API 36; ensure device/emulator runs Android 16 |
| "POST_PROMOTED_NOTIFICATIONS not found" | Compiling against older SDK | Set `compileSdk = 36` and use API 36 SDK |
| Notification dismissed on swipe | Not promoted | Ensure `POST_PROMOTED_NOTIFICATIONS` permission is granted for persistent display |
| Channel not created | `ensureChannel` not called | Call `LiveUpdateManager.ensureChannel(context)` in `Application.onCreate()` |
| `ActivityAlreadyRunning` error | Duplicate start call | Check `LiveUpdateManager.isActive` before calling `startJourney()` |
| Emulator crashes on API 36 | Preview SDK instability | Use a physical device running Android 16, or update emulator system image |
| Build fails with Compose errors | Version mismatch | Ensure Compose BOM and Kotlin compiler plugin versions are aligned (see `libs.versions.toml`) |
| Notification shows wrong status | Stale state object | Create a new `JourneyContentState` for each update; don't mutate existing instances |
| App killed, notification disappears | No foreground service | For production, consider a foreground service to keep the notification alive during long flights |

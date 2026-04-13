# El Al Flight Journey — Technical Overview

## 1. Project Overview

This project demonstrates real-time flight journey tracking using **Live Activities** (iOS) and **Live Updates** (Android 16) — two platform-native mechanisms for surfacing glanceable, always-visible progress on the lock screen and notification shade.

The deliverable is a pair of demo apps (one per platform) backed by a shared-concept SDK that manages the full journey lifecycle: **Check In → Boarding Soon → Boarding → Last Call → Taking Off → On The Way → Arrival**. Each SDK exposes a simple `start` / `update` / `end` API so that a host application can drive the journey with minimal integration effort.

**Key goals:**
- Prove feasibility of Live Activities and Android 16 Live Updates for airline journey tracking
- Provide a reusable SDK that can be embedded in El Al's production apps
- Demonstrate data-model parity across platforms while respecting each platform's native rendering model

---

## 2. Architecture

### 2.1 Shared SDK + Demo App Pattern

Both platforms follow the same two-module architecture:

```
┌─────────────────────────────────────────────┐
│                 Demo App                     │
│  (UI, ViewModel, permissions, simulation)    │
│                                              │
│   ┌───────────────────────────────────────┐  │
│   │            Flight SDK                 │  │
│   │  (models, manager, sample data)       │  │
│   └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

| Concern | iOS | Android |
|---------|-----|---------|
| SDK distribution | Swift Package (`ElAlFlightSDK`) | Gradle module (`:elal-flight-sdk`) |
| App UI framework | SwiftUI | Jetpack Compose (Material 3) |
| Live surface | Live Activity (Lock Screen + Dynamic Island) | Notification with `ProgressStyle` |
| State management | `@Published` / `ObservableObject` | `StateFlow` / `ViewModel` |

### 2.2 iOS: SwiftUI + ActivityKit + WidgetKit

```
ElAlFlightSDK (Swift Package)
├── Models
│   ├── JourneyAttributes        (ActivityAttributes)
│   ├── JourneyContentState      (Codable dynamic state)
│   └── JourneyStatus            (enum: checkIn, boardingSoon, boardingDelayed, boarding, lastCall, takingOff, onTheWay, arrival, connectionError, generalError)
│                                 — properties: badgeLabel, statusPillText, statusPillBackgroundColor, statusPillForegroundColor, timeColor, isDelayed, bottomBarBackgroundColor, bottomBarForegroundColor, bottomBarSubtitleColor
├── Manager
│   ├── LiveActivityManager      (@MainActor singleton, manages Activity<JourneyAttributes>)
│   └── LiveActivityError        (enum with 7 cases)
├── Views
│   ├── CountdownTimerView
│   ├── FlightProgressView
│   └── GateInfoView
├── Configuration
│   └── ElAlSDKConfiguration     (brand colors)
└── SampleData
    └── SampleFlightData         (attributes + 8-state simulation sequence)

ElAlDemoApp
├── App entry (ElAlDemoAppApp)
├── Views
│   ├── ContentView
│   └── JourneyDemoView          (control buttons, status display)
└── ViewModels
    └── JourneyViewModel         (drives simulation, updates manager)

ElAlWidgetExtension
├── ElAlWidgetExtensionBundle    (WidgetBundle entry)
├── JourneyLiveActivity          (Lock Screen + Dynamic Island layouts)
├── FlightRouteView              (departure → arrival with dashed line)
├── StatusBadgeView              (colored capsule badge)
├── PingHLFont                   (custom El Al typography)
```

### 2.3 Android: Kotlin + Jetpack Compose + Notification.ProgressStyle

```
elal-flight-sdk (Gradle library module)
├── model
│   ├── JourneyData.kt           (JourneyAttributes + JourneyContentState data classes)
│   └── JourneyStatus.kt         (enum: CHECK_IN, BOARDING_SOON, BOARDING_DELAYED, BOARDING, LAST_CALL, TAKING_OFF, ON_THE_WAY, ARRIVAL, CONNECTION_ERROR, GENERAL_ERROR)
├── manager
│   ├── LiveUpdateManager.kt     (object singleton, builds Notification + ProgressStyle)
│   └── LiveUpdateError.kt       (sealed class with 4 subtypes)
└── sample
    └── SampleFlightData.kt      (attributes + 8-state simulation sequence)

app (Gradle application module)
├── MainActivity.kt              (permissions, Compose entry)
├── ElAlDemoApplication.kt       (notification channel init)
├── notification
│   └── NotificationChannelHelper.kt
└── ui
    ├── journey
    │   ├── JourneyDemoScreen.kt (Compose UI with controls)
    │   └── JourneyViewModel.kt  (StateFlow-based state management)
    └── theme
        └── Color.kt, Theme.kt, Type.kt
```

### 2.4 Data Model Parity

The core data models are intentionally parallel across platforms:

| Concept | iOS (Swift) | Android (Kotlin) |
|---------|-------------|-------------------|
| Static flight info | `JourneyAttributes: ActivityAttributes` | `data class JourneyAttributes` |
| Dynamic state | `JourneyAttributes.ContentState` (nested) | `data class JourneyContentState` |
| Journey status | `enum JourneyStatus` (10 cases) | `enum class JourneyStatus` (10 values) |
| Error type | `enum LiveActivityError` (7 cases) | `sealed class LiveUpdateError` (4 subtypes) |
| Manager | `LiveActivityManager.shared` | `LiveUpdateManager` (object) |
| Sample data | `SampleFlightData` (enum) | `SampleFlightData` (object) |

Both platforms share the same field names and semantics for `JourneyContentState`:

```
Common fields:        status, delayMinutes, gate, terminal, seat, boardingGroup
Check-In fields:      checkInDesk, checkInStartTime
Boarding Soon fields: boardingTime, gateClosingTime
On The Way fields:    progress (0.0–1.0), currentAltitude, estimatedArrivalTime
Arrival fields:       baggageCarousel
```

### 2.5 Journey Lifecycle

```
                    ┌──────────────┐
                    │    Start     │
                    │   Journey    │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
           ┌─────  │   Check In   │
           │       └──────┬────────┘
           │              │ update()
           │       ┌──────▼────────┐
           │       │ Boarding Soon │
           │       └──────┬────────┘
           │              │ update()
           │       ┌──────▼────────┐
           │       │   Boarding    │
           │       └──────┬────────┘
           │              │ update()
      end()│       ┌──────▼────────┐
      at   │       │  Last Call    │
      any  │       └──────┬────────┘
      time │              │ update()
           │       ┌──────▼────────┐
           │       │  Taking Off   │
           │       └──────┬────────┘
           │              │ update()
           │       ┌──────▼────────┐
           │       │  On The Way   │ ← progress: 0.0 → 1.0
           │       └──────┬────────┘
           │              │ update()
           │       ┌──────▼────────┐
           │       │   Arrival     │
           └─────  └──────┬────────┘
                          │
                   ┌──────▼───────┐
                   │     End      │
                   │   Journey    │
                   └──────────────┘

   Boarding Delayed can replace Boarding Soon at any point before boarding.
   Connection Error / General Error can occur at any point.
```

---

## 3. Platform Differences

### 3.1 iOS Live Activities

- **Rendering:** Fully custom SwiftUI views on the Lock Screen and Dynamic Island
- **Lock Screen:** Fully dark-themed card — top section `rgba(0,0,0,0.8)`, bottom bar `rgba(0,0,0,0.6)` (or `rgba(254,198,48,0.95)` yellow for boarding/lastCall). Header row with El Al logo + flight number (left) and status pill (right). Center badge, flight route visualization, countdown timers, and progress bars
- **Dynamic Island:** Four presentation regions:
  - **Expanded Leading:** Status-specific info (desk, gate, departure airport, carousel)
  - **Expanded Trailing:** Seat info or arrival airport
  - **Expanded Center:** Flight number + progress bar (on the way) or status name
  - **Expanded Bottom:** Passenger name + status badge
  - **Compact Leading/Trailing:** Airport codes
  - **Minimal:** Airplane icon
- **Custom typography:** PingHL font family (Regular, Medium, Black)
- **Interactivity:** Views can include buttons and deep links
- **Maximum duration:** 12 hours (system limit), up to 4 hours after `end()` before auto-dismissal

### 3.2 Android 16 Live Updates

- **Rendering:** System-rendered `Notification.ProgressStyle` — the OS controls the visual layout
- **Visual elements:**
  - 7 labeled points (Check In, Boarding Soon, Boarding, Last Call, Taking Off, On The Way, Arrival)
  - 6 connecting segments between points
  - Color-coded progress: green (completed) / grey (pending)
  - Progress value mapped to 0–1000 integer scale
- **Content text:** Status-specific detail string (desk, gate/seat/group, taking off, altitude/progress%, carousel)
- **Placement:** Notification shade and status bar (promoted notification with `POST_PROMOTED_NOTIFICATIONS` permission)
- **No custom layout:** The ProgressStyle API does not support custom RemoteViews — appearance is system-defined

### 3.3 Comparison Table

| Aspect | iOS Live Activities | Android 16 Live Updates |
|--------|-------------------|------------------------|
| **Rendering model** | Custom SwiftUI views | System-rendered ProgressStyle |
| **Visual flexibility** | Full control (colors, fonts, layout, images) | Limited to points, segments, progress bar |
| **Lock screen placement** | Dedicated Live Activity area | Notification shade (promoted) |
| **Dynamic Island** | Yes (4 presentation regions) | N/A |
| **Custom fonts** | Yes (PingHL) | No |
| **Brand imagery** | Logo, route visualization, badges | Icon only |
| **Countdown timers** | Native `Text(date:, style: .timer)` | Not available |
| **Animations** | SwiftUI animations | System-controlled |
| **Interactivity** | Buttons, deep links | Notification actions only |
| **API surface** | ActivityKit + WidgetKit | NotificationManager + ProgressStyle |
| **Min OS version** | iOS 16.2 | Android 16 (API 36) |
| **Duration limit** | 12 hours active | No system-imposed limit |

### 3.4 Why They Look Different

The visual difference between iOS and Android is **by design, not a limitation of this implementation.** Apple's Live Activities allow fully custom SwiftUI views, enabling branded, rich lock-screen experiences. Android 16's Live Updates use a system-rendered `Notification.ProgressStyle` that standardizes appearance across all apps. Each implementation maximizes what the respective platform offers — a richer custom UI on iOS, and a clean, consistent system-native progress visualization on Android.

---

## 4. Project Structure

### 4.1 iOS

```
ElAlFlightSDK/
├── Package.swift                           # Swift Package manifest (tools 5.9, iOS 16.0+)
├── Sources/ElAlFlightSDK/
│   ├── Configuration/
│   │   └── ElAlSDKConfiguration.swift      # Brand colors (#003087, etc.)
│   ├── Manager/
│   │   ├── LiveActivityManager.swift       # Singleton activity lifecycle manager
│   │   └── LiveActivityError.swift         # Error enum (7 cases)
│   ├── Models/
│   │   ├── JourneyAttributes.swift         # ActivityAttributes + ContentState
│   │   ├── JourneyStatus.swift             # 10-status enum with displayName + color
│   │   ├── FlightTrackerAttributes.swift   # (Legacy) flight tracker model
│   │   ├── BoardingPassAttributes.swift    # (Legacy, deprecated) boarding model
│   │   ├── FlightStatus.swift              # (Legacy, deprecated) status enum
│   │   └── BoardingStatus.swift            # (Legacy, deprecated) boarding enum
│   ├── Views/
│   │   ├── CountdownTimerView.swift        # Timer with urgency styling
│   │   ├── FlightProgressView.swift        # Horizontal progress with airplane
│   │   └── GateInfoView.swift              # Gate display with change indicator
│   └── SampleData/
│       └── SampleFlightData.swift          # Demo attributes + 8-state sequence
└── Tests/ElAlFlightSDKTests/
    └── ElAlFlightSDKTests.swift            # Unit tests (model validation, encoding)

ElAlDemoApp/
├── project.yml                             # XcodeGen project definition
├── ElAlDemoApp/
│   ├── ElAlDemoAppApp.swift                # @main app entry
│   ├── Info.plist                          # NSSupportsLiveActivities = true
│   ├── Views/
│   │   ├── ContentView.swift               # Root view wrapper
│   │   └── JourneyDemoView.swift           # Demo controls UI
│   └── ViewModels/
│       └── JourneyViewModel.swift          # Simulation + state management
├── ElAlWidgetExtension/
│   ├── ElAlWidgetExtensionBundle.swift     # WidgetBundle entry point
│   ├── JourneyLiveActivity.swift           # Lock Screen + Dynamic Island views
│   ├── FlightRouteView.swift               # Route visualization component
│   ├── StatusBadgeView.swift               # Status badge capsule
│   ├── PingHLFont.swift                    # Custom font helper
│   ├── Info.plist                          # NSSupportsLiveActivities = true
│   └── Fonts/
│       ├── PingHL-Regular.ttf
│       ├── PingHL-Medium.ttf
│       └── PingHL-Black.ttf
└── ElAlDemoApp/Fonts/                      # App-level font copies
    ├── PingHL-Regular.ttf
    ├── PingHL-Medium.ttf
    └── PingHL-Black.ttf
```

### 4.2 Android

```
ElAlDemoAndroid/
├── build.gradle.kts                        # Root build config
├── settings.gradle.kts                     # Module includes
├── gradle/libs.versions.toml               # Version catalog
│
├── elal-flight-sdk/                        # Library module
│   ├── build.gradle.kts                    # compileSdk 36, minSdk 36
│   └── src/main/
│       ├── AndroidManifest.xml             # Empty library manifest
│       └── java/com/elal/flightsdk/
│           ├── manager/
│           │   ├── LiveUpdateManager.kt    # Singleton, builds ProgressStyle notification
│           │   └── LiveUpdateError.kt      # Sealed class (4 subtypes)
│           ├── model/
│           │   ├── JourneyData.kt          # JourneyAttributes + JourneyContentState
│           │   └── JourneyStatus.kt        # 10-value enum with displayName + colorInt
│           └── sample/
│               └── SampleFlightData.kt     # Demo data + 8-state sequence
│
└── app/                                    # Application module
    ├── build.gradle.kts                    # compileSdk 36, minSdk 36, Compose enabled
    └── src/main/
        ├── AndroidManifest.xml             # POST_NOTIFICATIONS + POST_PROMOTED_NOTIFICATIONS
        ├── res/values/
        │   ├── strings.xml
        │   ├── colors.xml
        │   └── themes.xml
        └── java/com/elal/demoapp/
            ├── MainActivity.kt             # Permission requests, Compose entry
            ├── ElAlDemoApplication.kt      # Channel initialization
            ├── notification/
            │   └── NotificationChannelHelper.kt
            └── ui/
                ├── journey/
                │   ├── JourneyDemoScreen.kt    # Compose demo UI
                │   └── JourneyViewModel.kt     # StateFlow state management
                └── theme/
                    ├── Color.kt
                    ├── Theme.kt
                    └── Type.kt
```

---

## 5. SDK API Reference

### 5.1 Journey Lifecycle

Both platforms expose the same three-method lifecycle:

#### iOS — `LiveActivityManager`

```swift
@MainActor final class LiveActivityManager: ObservableObject {
    static let shared: LiveActivityManager

    // Start a new Live Activity
    func startJourney(
        attributes: JourneyAttributes,
        initialState: JourneyAttributes.ContentState
    ) throws -> Activity<JourneyAttributes>

    // Update the current Live Activity state
    func updateJourney(
        state: JourneyAttributes.ContentState
    ) async throws

    // End the Live Activity
    func endJourney(
        finalState: JourneyAttributes.ContentState? = nil,
        dismissalPolicy: ActivityUIDismissalPolicy = .default
    ) async throws

    // Utility
    var areActivitiesEnabled: Bool { get }
    func endAllActivities() async
}
```

#### Android — `LiveUpdateManager`

```kotlin
object LiveUpdateManager {
    val isActive: Boolean

    fun ensureChannel(context: Context)

    @Throws(LiveUpdateError::class)
    fun startJourney(
        context: Context,
        attributes: JourneyAttributes,
        initialState: JourneyContentState
    )

    @Throws(LiveUpdateError::class)
    fun updateJourney(context: Context, state: JourneyContentState)

    fun endJourney(context: Context)
}
```

### 5.2 Error Handling

#### iOS — `LiveActivityError`

| Case | Description |
|------|-------------|
| `activitiesNotEnabled` | Live Activities disabled in Settings |
| `activitiesNotSupported` | Device/OS does not support Live Activities |
| `failedToStart(underlying:)` | ActivityKit threw during `request()` |
| `failedToUpdate(underlying:)` | ActivityKit threw during `update()` |
| `failedToEnd(underlying:)` | ActivityKit threw during `end()` |
| `noActiveActivity` | Attempted update/end with no running activity |
| `activityAlreadyRunning` | Attempted start while an activity is already active |

#### Android — `LiveUpdateError`

| Subclass | Description |
|----------|-------------|
| `NotificationsNotEnabled` | Notifications permission not granted |
| `ActivityAlreadyRunning` | Attempted start while an update is active |
| `NoActiveActivity` | Attempted update with no running update |
| `FailedToStart(cause)` | Unexpected error during notification post |

### 5.3 Data Models

#### JourneyAttributes (static, set once at start)

| Field | Type (iOS) | Type (Android) |
|-------|-----------|----------------|
| `flightNumber` | `String` | `String` |
| `airline` | `String` (default "El Al") | `String` (default "El Al") |
| `passengerName` | `String` | `String` |
| `departureAirportCode` | `String` | `String` |
| `departureCity` | `String` | `String` |
| `arrivalAirportCode` | `String` | `String` |
| `arrivalCity` | `String` | `String` |
| `scheduledDepartureTime` | `Date` | `Instant` |
| `scheduledArrivalTime` | `Date` | `Instant` |

#### JourneyContentState (dynamic, updated throughout journey)

| Field | Type (iOS) | Type (Android) | Used In Status |
|-------|-----------|----------------|---------------|
| `status` | `JourneyStatus` | `JourneyStatus` | All |
| `delayMinutes` | `Int` | `Int` | All (optional metadata) |
| `gate` | `String?` | `String?` | Boarding Soon, Boarding, Last Call |
| `terminal` | `String?` | `String?` | Boarding Soon, Arrival |
| `seat` | `String?` | `String?` | Boarding Soon, Boarding, Last Call |
| `boardingGroup` | `String?` | `String?` | Boarding Soon, Boarding, Last Call |
| `checkInDesk` | `String?` | `String?` | Check In |
| `checkInStartTime` | `Date?` | `Instant?` | Check In |
| `boardingTime` | `Date?` | `Instant?` | Boarding Soon, Boarding |
| `gateClosingTime` | `Date?` | `Instant?` | Boarding Soon, Boarding, Last Call |
| `progress` | `Double` (0.0–1.0) | `Double` (0.0–1.0) | On The Way |
| `currentAltitude` | `String?` | `String?` | On The Way |
| `estimatedArrivalTime` | `Date?` | `Instant?` | On The Way |
| `baggageCarousel` | `String?` | `String?` | Arrival |

#### JourneyStatus

| iOS | Android | Display Name | Badge Label (center) | Status Pill | Status Pill BG | Time Color |
|-----|---------|-------------|---------------------|-------------|----------------|------------|
| `.checkIn` | `CHECK_IN` | Check In | Baggage drop | ON TIME | Green `#2BA45F` | Green (on time) |
| `.boardingSoon` | `BOARDING_SOON` | Boarding Soon | Boarding soon | ON TIME | Green `#2BA45F` | Green (on time) |
| `.boardingDelayed` | `BOARDING_DELAYED` | Boarding Delayed | Boarding soon | DELAYED | Yellow `#FEC630` | Yellow (delayed) |
| `.boarding` | `BOARDING` | Boarding | Boarding | ON TIME | Green `#2BA45F` | Green (on time) |
| `.lastCall` | `LAST_CALL` | Last Call | Last call | ON TIME | Green `#2BA45F` | Green (on time) |
| `.takingOff` | `TAKING_OFF` | Taking Off | Taking off | ON TIME | Green `#2BA45F` | Green (on time) |
| `.onTheWay` | `ON_THE_WAY` | On The Way | On the way | ON TIME | Green `#2BA45F` | Green (on time) |
| `.arrival` | `ARRIVAL` | Arrival | Arrived | ON TIME | Green `#2BA45F` | Green (on time) |
| `.connectionError` | `CONNECTION_ERROR` | Connection Error | Connection is lost | ERROR | Grey `#6C6C6C` | Grey (error) |
| `.generalError` | `GENERAL_ERROR` | General Error | Something went wrong | ERROR | Grey `#6C6C6C` | Grey (error) |

**New JourneyStatus properties (iOS):**

| Property | Type | Description |
|----------|------|-------------|
| `badgeLabel` | `String` | Center badge text (differs from `displayName` — see table above) |
| `statusPillText` | `String` | "ON TIME", "DELAYED", or "ERROR" |
| `statusPillBackgroundColor` | `Color` | Green `#2BA45F`, Yellow `#FEC630`, or Grey `#6C6C6C` |
| `statusPillForegroundColor` | `Color` | Text color inside the status pill |
| `timeColor` | `Color` | Color for time labels: green (on time), yellow (delayed), grey (error) |
| `isDelayed` | `Bool` | `true` only for `boardingDelayed` |
| `bottomBarBackgroundColor` | `Color` | `rgba(0,0,0,0.6)` for most states; `rgba(254,198,48,0.95)` yellow for `boarding` and `lastCall` |
| `bottomBarForegroundColor` | `Color` | Primary text color in the bottom bar |
| `bottomBarSubtitleColor` | `Color` | Subtitle text color in the bottom bar |

**Removed properties:** `badgeBackgroundColor`, `badgeForegroundColor`, `airportColumnMode`, `AirportColumnMode` enum, `bottomBarIconName`, `bottomBarUsesCircleIcon`

---

## 6. Build & Export

### 6.1 iOS

**Requirements:**
- Xcode 15+ (Swift Tools 5.9)
- iOS 16.2+ deployment target
- macOS with Xcode Command Line Tools

**Build with Xcode:**
1. Install XcodeGen: `brew install xcodegen`
2. Generate project: `cd ElAlDemoApp && xcodegen generate`
3. Open `ElAlDemoApp.xcodeproj` in Xcode
4. Select a simulator or device running iOS 16.2+
5. Build and run (Cmd+R)

**Build from command line:**
```bash
cd ElAlDemoApp
xcodegen generate
xcodebuild -project ElAlDemoApp.xcodeproj \
  -scheme ElAlDemoApp \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  build
```

**Key configuration (project.yml):**
- Bundle ID: `com.elal.demoapp` (app), `com.elal.demoapp.widget` (extension)
- `NSSupportsLiveActivities: true` in both Info.plist files
- Custom fonts registered in `UIAppFonts`: PingHL-Regular, PingHL-Medium, PingHL-Black

### 6.2 Android

**Requirements:**
- Android Studio (latest stable)
- Android SDK 36 (Android 16 preview)
- JDK 17+
- Physical device or emulator running API 36

**Build with Android Studio:**
1. Open `ElAlDemoAndroid/` in Android Studio
2. Sync Gradle files
3. Select an API 36 device/emulator
4. Run the `app` configuration

**Build from command line:**
```bash
cd ElAlDemoAndroid
./gradlew assembleDebug
```

**Install on device:**
```bash
./gradlew installDebug
```

**Key configuration:**
- `compileSdk = 36`, `minSdk = 36`, `targetSdk = 36`
- Kotlin 2.0.21 with Compose compiler plugin
- Java 17 source/target compatibility
- Compose BOM 2025.03.00

---

## 7. Testing Coverage

### 7.1 iOS Unit Tests

Located in `ElAlFlightSDK/Tests/ElAlFlightSDKTests/ElAlFlightSDKTests.swift`:

| Test | What It Verifies |
|------|-----------------|
| `testFlightStatusDisplayNames` | All `FlightStatus` cases return correct display strings |
| `testFlightStatusIsActive` | `isActive` property is true for boarding, departed, enRoute |
| `testBoardingStatusDisplayNames` | All `BoardingStatus` cases return correct display strings |
| `testBoardingStatusIsUrgent` | `isUrgent` is true only for `lastCall` |
| `testProgressClamping` | Progress values are clamped to 0.0–1.0 |
| `testFlightTrackerContentStateCodable` | JSON round-trip for FlightTrackerAttributes.ContentState |
| `testBoardingPassContentStateCodable` | JSON round-trip for BoardingPassAttributes.ContentState |
| `testSampleDataValidity` | SampleFlightData attributes have expected values |
| `testSimulationSequences` | Flight simulation has 5 states, boarding has 5 states |
| `testJourneyStatusDisplayName` | All 10 `JourneyStatus` cases return correct display names |
| `testJourneySimulationSequence` | Journey simulation has 8 states with correct statuses |
| `testJourneyContentStateCodable` | JSON round-trip for JourneyAttributes.ContentState |

**Run tests:**
```bash
swift test --package-path ElAlFlightSDK
```

### 7.2 Android

No automated unit tests are included in the Android project yet. Testing is manual.

### 7.3 Manual QA Checklist

#### Both Platforms
- [ ] Journey starts and displays initial Check In state
- [ ] Each status transition updates the live surface correctly
- [ ] All 8 simulation states render with correct content
- [ ] End journey dismisses the live surface
- [ ] Error states display appropriate messages
- [ ] Simulate Journey auto-advances through all stages (5-second intervals)

#### iOS-Specific
- [ ] Lock Screen shows correct layout for each status (including error states)
- [ ] Dynamic Island compact view shows airport codes
- [ ] Dynamic Island expanded view shows full journey info
- [ ] Countdown timers count down in real time
- [ ] Custom PingHL fonts render correctly
- [ ] Activity persists after app is backgrounded
- [ ] Activity appears on Always-On Display (iPhone 14 Pro+)

#### Android-Specific
- [ ] Notification appears in notification shade
- [ ] ProgressStyle shows 7 points with correct labels
- [ ] Completed segments turn green, pending segments stay grey
- [ ] Content text updates per status (desk → gate/seat → taking off → altitude → carousel)
- [ ] Connection Error/General Error show 0 progress with appropriate text
- [ ] Permission prompts appear for POST_NOTIFICATIONS and POST_PROMOTED_NOTIFICATIONS
- [ ] Notification channel "Journey Updates" is created
- [ ] Notification persists after app is backgrounded

---

## 8. Future Improvements

### 8.1 Android
- **Unit tests:** Add tests for `LiveUpdateManager`, data models, and simulation sequence validation (parity with iOS test suite)
- **Custom RemoteViews:** When the API supports it, use custom layouts for richer notification appearance (route visualization, brand imagery)
- **Start/end/tracker icons:** Add custom icons for ProgressStyle points and segments
- **Foreground service:** For long-haul flights, use a foreground service to ensure the notification persists without being killed by the system

### 8.2 iOS
- **Additional journey statuses:** Gate change, taxi, takeoff, turbulence alerts
- **Push-to-start via APNs:** Start Live Activities remotely via push notifications (iOS 17.2+)
- **Server-driven state updates:** ActivityKit push updates for real-time server → device state sync
- **Stale activity handling:** Detect and clean up activities that outlive their expected duration

### 8.3 Cross-Platform
- **Shared backend:** Real flight data API (e.g., FlightAware, Amadeus) driving both platforms from a single source of truth
- **CI/CD pipeline:** Automated builds, tests, and artifact publishing for both SDKs
- **Analytics:** Track Live Activity engagement, dismissal rates, and status transition timing
- **Localization:** Multi-language support for status text and display names
- **Accessibility:** VoiceOver (iOS) and TalkBack (Android) audit for all live surfaces

---

## 9. Dependencies & Requirements

### 9.1 iOS

| Dependency | Version | Purpose |
|------------|---------|---------|
| Swift | 5.9+ | Language |
| iOS Deployment Target | 16.2+ | Live Activities minimum |
| Xcode | 15+ | Build toolchain |
| XcodeGen | Latest | Project file generation |
| ActivityKit | System framework | Live Activity lifecycle |
| WidgetKit | System framework | Widget extension infrastructure |
| SwiftUI | System framework | UI rendering |

No third-party dependencies. The SDK uses only Apple system frameworks.

### 9.2 Android

| Dependency | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.0.21 | Language |
| Android Gradle Plugin | 8.7.0 | Build system |
| compileSdk / targetSdk | 36 | Android 16 APIs |
| minSdk | 36 | ProgressStyle requires API 36 |
| JDK | 17 | Compilation target |
| androidx.core:core-ktx | 1.15.0 | Kotlin Android extensions |
| androidx.lifecycle:lifecycle-runtime-ktx | 2.8.7 | Lifecycle-aware coroutines |
| androidx.lifecycle:lifecycle-viewmodel-compose | 2.8.7 | ViewModel + Compose integration |
| androidx.activity:activity-compose | 1.9.3 | Compose Activity support |
| androidx.compose:compose-bom | 2025.03.00 | Compose version alignment |
| androidx.compose.ui:ui | BOM-managed | Core Compose UI |
| androidx.compose.ui:ui-graphics | BOM-managed | Graphics primitives |
| androidx.compose.material3:material3 | BOM-managed | Material 3 components |

No third-party dependencies. The SDK uses only `androidx.core:core-ktx` and Android framework APIs.

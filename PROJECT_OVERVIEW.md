# El Al Demo Android Project — Overview

## What does this app do?

Imagine you're on a plane. This app shows you a **live notification** on your phone that follows your flight from the moment you check in until you grab your bags. It updates by itself — like a tracker that knows where your flight is.

---

## The Two Big "Lego Boxes"

The project has **two modules** (think of them like two separate Lego boxes):

```
ElAlDemoAndroid/
├── app/                 ← The Demo App (the thing you install on your phone)
└── elal-flight-sdk/     ← The "Engine" (reusable, can plug into any El Al app)
```

The **SDK** is the engine. The **App** uses the engine.

---

## How the folders are organized

```
app/
├── MainActivity.kt              ← The front door of the app
├── ElAlDemoApplication.kt       ← Runs when app starts, sets up the engine
├── firebase/
│   └── FlightMessagingService   ← Listens for push messages from the server
├── ui/
│   ├── journey/
│   │   ├── JourneyDemoScreen    ← What you SEE on screen (buttons, status)
│   │   └── JourneyViewModel     ← The brain that decides what to show
│   └── theme/                   ← Colors and fonts (El Al blue, etc.)
└── debug/
    └── DebugNextStageReceiver   ← A testing cheat button (TODO: delete before release!)

elal-flight-sdk/
├── manager/
│   ├── LiveUpdateManager        ← The MAIN ENGINE — controls the notification
│   ├── InFlightService          ← Keeps running in background while flying
│   └── JourneyPrefs             ← Remembers the flight if you close the app
├── model/
│   └── JourneyData              ← Describes what a flight looks like (Stage, times, etc.)
├── sample/
│   └── SampleFlightData         ← Fake flights for testing
└── utils/
    └── Extensions               ← Helper tools (time formatting, etc.)
```

---

## The Journey — Step by Step

The flight goes through these **stages** in order:

```
CHECK_IN → GATE_READY → BOARDING → LAST_CALL → DEPARTED → IN_FLIGHT → LANDED → BAGGAGE → COMPLETED
```

Each stage updates the notification on your phone automatically.

---

## How data flows (the story of a notification)

1. **Server sends a push message** (via Firebase/FCM)
2. `FlightMessagingService` catches it and reads the flight info
3. It tells `LiveUpdateManager` — the engine — to update the notification
4. `LiveUpdateManager` shows a **progress bar notification** on your phone
5. While flying (`IN_FLIGHT`), `InFlightService` keeps updating the progress bar every few seconds
6. When the plane lands, it automatically moves to `LANDED`
7. `JourneyPrefs` saves the state so if you close and reopen the app, it remembers

---

## Key Files to Know When Continuing

| File | What it does | Touch it when... |
|------|-------------|-----------------|
| `LiveUpdateManager.kt` | The heart of the SDK | Changing notification logic |
| `JourneyViewModel.kt` | Drives the UI | Changing button behavior or screen logic |
| `JourneyDemoScreen.kt` | The UI itself | Changing what the screen looks like |
| `FlightMessagingService.kt` | Handles push notifications | Changing how server messages are parsed |
| `JourneyData.kt` | The data models | Adding new flight stages or fields |
| `SampleFlightData.kt` | Fake test data | Testing a new stage or flow |

---

## What's left from the previous developer

- There's a **debug cheat button** (`DebugNextStageReceiver`) marked `TODO: remove before release` — don't delete it yet, it's useful for testing
- The app targets **Android 16+** (API 36) for the "Live Update" notification feature — this won't work on older phones
- Hebrew (`-iw`) and English localization are both set up

---

## To continue working on this project

1. Open Android Studio
2. The main SDK logic lives in `elal-flight-sdk/manager/LiveUpdateManager.kt`
3. The screen lives in `app/ui/journey/JourneyDemoScreen.kt`
4. To test a flow without a real server: use `SampleFlightData.kt` — it has two fake journeys (departure TLV→JFK, arrival JFK→TLV)
5. Run the app on an **Android 16 emulator or device** — the Live Update notifications won't show on older Android versions
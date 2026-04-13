# Journey States — Data Reference

This document describes all journey states, the data required for each, and how they are displayed in the Live Activity.

---

## Static Attributes (set once when creating the Live Activity)

These fields are set when the journey starts and do not change.

| Field | Type | Required | Example | Description |
|-------|------|----------|---------|-------------|
| `flightNumber` | String | Yes | `"LY 317"` | Flight number |
| `airline` | String | Yes | `"El Al"` | Airline name |
| `passengerName` | String | Yes | `"Israel Israeli"` | Passenger full name |
| `departureAirportCode` | String | Yes | `"TLV"` | IATA departure airport code |
| `departureCity` | String | Yes | `"Tel Aviv"` | Departure city name |
| `arrivalAirportCode` | String | Yes | `"JFK"` | IATA arrival airport code |
| `arrivalCity` | String | Yes | `"New York"` | Arrival city name |
| `scheduledDepartureTime` | Date | Yes | `2026-03-24T09:41:00Z` | Scheduled departure time |
| `scheduledArrivalTime` | Date | Yes | `2026-03-24T20:00:00Z` | Scheduled arrival time |
| `departureTerminal` | String? | No | `"3"` | Departure terminal (displayed as "T3") |
| `arrivalTerminal` | String? | No | `"6"` | Arrival terminal (displayed as "T6") |

---

## Dynamic Content State Fields (updated as journey progresses)

| Field | Type | Default | Used By | Description |
|-------|------|---------|---------|-------------|
| `status` | JourneyStatus | — | All | Current journey status (see states below) |
| `delayMinutes` | Int | `0` | boardingDelayed | Delay in minutes. `0` = on time |
| `gate` | String? | `nil` | boardingSoon, boardingDelayed, boarding, lastCall | Gate number (e.g. "D3") |
| `terminal` | String? | `nil` | boardingSoon, boarding, lastCall, arrival | Terminal at current location |
| `seat` | String? | `nil` | boardingSoon, boarding, lastCall | Seat assignment (e.g. "12A") |
| `boardingGroup` | String? | `nil` | boardingSoon, boarding, lastCall | Boarding group (e.g. "1") |
| `checkInDesk` | String? | `nil` | checkIn | Desk number (e.g. "A108") |
| `checkInStartTime` | Date? | `nil` | checkIn | When check-in closes |
| `boardingTime` | Date? | `nil` | boardingDelayed, boardingSoon | When boarding starts |
| `gateClosingTime` | Date? | `nil` | boardingSoon, boarding, lastCall | When gate closes |
| `estimatedDepartureTime` | Date? | `nil` | boardingDelayed | New estimated departure (shown as replacement time) |
| `takeoffTime` | Date? | `nil` | takingOff | Estimated takeoff time for countdown |
| `progress` | Double | `0.0` | onTheWay, arrival | Flight progress 0.0–1.0 |
| `currentAltitude` | String? | `nil` | onTheWay | Current altitude (e.g. "36,000 ft") |
| `estimatedArrivalTime` | Date? | `nil` | onTheWay, arrival | ETA for landing countdown |
| `baggageCarousel` | String? | `nil` | arrival | Baggage carousel number (e.g. "25") |

---

## Journey States

### Happy Flow Order

```
checkIn → boardingSoon → boarding → lastCall → takingOff → onTheWay → arrival
```

### Exception States

```
boardingDelayed — can replace boardingSoon at any point before boarding
connectionError, generalError — can occur at any point
```

---

### 1. Check In (`checkIn`)

Passenger can check in for the flight.

| Field | Required | Example |
|-------|----------|---------|
| `status` | Yes | `.checkIn` |
| `checkInDesk` | Yes | `"A108"` |
| `checkInStartTime` | Yes | Check-in closing time |

**Display:**
- Header: El Al logo + flight number (left), "ON TIME" pill green `#2BA45F` (right)
- Top: Dark background `rgba(0,0,0,0.8)`, airport columns with green time text, terminal shown as pill with pin icon
- Center badge: "Baggage drop"
- Bottom bar: Dark `rgba(0,0,0,0.6)` — "Desk A108" / "Check in closes in {countdown}"

---

### 2. Boarding Soon (`boardingSoon`)

Passenger is checked in and has gate/seat info. Boarding is approaching.

| Field | Required | Example |
|-------|----------|---------|
| `status` | Yes | `.boardingSoon` |
| `gate` | Yes | `"D3"` |
| `terminal` | No | `"3"` |
| `seat` | Yes | `"12A"` |
| `boardingGroup` | Yes | `"1"` |
| `boardingTime` | Yes | Boarding start time |
| `gateClosingTime` | No | Gate closing time |

**Display:**
- Header: El Al logo + flight number (left), "ON TIME" pill green `#2BA45F` (right)
- Top: Dark background `rgba(0,0,0,0.8)`, airport columns with green time text, terminal shown as pill with pin icon
- Center badge: "Boarding soon"
- Bottom bar: Dark `rgba(0,0,0,0.6)` — "Gate D3" / "Boarding in {countdown}"

---

### 3. Boarding Delayed (`boardingDelayed`)

Boarding is delayed.

| Field | Required | Example |
|-------|----------|---------|
| `status` | Yes | `.boardingDelayed` |
| `delayMinutes` | Yes | `55` |
| `gate` | No | `"D3"` |
| `boardingTime` | No | New boarding time |
| `estimatedDepartureTime` | Yes | New departure time |
| `estimatedArrivalTime` | Yes | New arrival time |

**Display:**
- Header: El Al logo + flight number (left), "DELAYED" pill yellow `#FEC630` (right)
- Top: Dark background `rgba(0,0,0,0.8)`, airport columns with yellow time text, original times strikethrough + new times below
- Center badge: "Boarding soon"
- Bottom bar: Dark `rgba(0,0,0,0.6)` — "Gate D3" / "Gate Departure in {countdown}"

---

### 4. Boarding (`boarding`)

Boarding is in progress.

| Field | Required | Example |
|-------|----------|---------|
| `status` | Yes | `.boarding` |
| `gate` | Yes | `"D3"` |
| `terminal` | No | `"3"` |
| `seat` | Yes | `"12A"` |
| `boardingGroup` | Yes | `"1"` |
| `boardingTime` | No | Boarding start time |
| `gateClosingTime` | Yes | Gate closing time |

**Display:**
- Header: El Al logo + flight number (left), "ON TIME" pill green `#2BA45F` (right)
- Top: Dark background `rgba(0,0,0,0.8)`, airport columns with green time text, terminal shown as pill with pin icon
- Center badge: "Boarding"
- Bottom bar: Yellow `rgba(254,198,48,0.95)` — "Gate D3" / "Boarding now"

---

### 5. Last Call (`lastCall`)

Last call before gate closes.

| Field | Required | Example |
|-------|----------|---------|
| `status` | Yes | `.lastCall` |
| `gate` | Yes | `"D3"` |
| `terminal` | No | `"3"` |
| `seat` | Yes | `"12A"` |
| `boardingGroup` | Yes | `"1"` |
| `gateClosingTime` | Yes | Gate closing time |

**Display:**
- Header: El Al logo + flight number (left), "ON TIME" pill green `#2BA45F` (right)
- Top: Dark background `rgba(0,0,0,0.8)`, airport columns with green time text, terminal shown as pill with pin icon
- Center badge: "Last call"
- Bottom bar: Yellow `rgba(254,198,48,0.95)` — "Gate D3" / "Gate closing in {countdown}"

---

### 6. Taking Off (`takingOff`)

Aircraft is preparing for takeoff.

| Field | Required | Example |
|-------|----------|---------|
| `status` | Yes | `.takingOff` |
| `takeoffTime` | Yes | Estimated takeoff time |

**Display:**
- Header: El Al logo + flight number (left), "ON TIME" pill green `#2BA45F` (right)
- Top: Dark background `rgba(0,0,0,0.8)`, airport columns with green time text
- Center badge: "Taking off"
- Bottom bar: Dark `rgba(0,0,0,0.6)` — "Taking off in: {countdown}"

---

### 7. On The Way (`onTheWay`)

Aircraft is in flight.

| Field | Required | Example |
|-------|----------|---------|
| `status` | Yes | `.onTheWay` |
| `progress` | Yes | `0.5` (50% of flight) |
| `currentAltitude` | No | `"36,000 ft"` |
| `estimatedArrivalTime` | Yes | ETA for landing |

**Display:**
- Header: El Al logo + flight number (left), "ON TIME" pill green `#2BA45F` (right)
- Top: Dark background `rgba(0,0,0,0.8)`, airport columns with green time text, airplane moves along arc path based on `progress`
- Center badge: "On the way"
- Bottom bar: Dark `rgba(0,0,0,0.6)` — "Landing in: {countdown}"
- Compact Dynamic Island: Shows arrival countdown timer

---

### 8. Arrival (`arrival`)

Aircraft has landed.

| Field | Required | Example |
|-------|----------|---------|
| `status` | Yes | `.arrival` |
| `terminal` | No | `"1"` |
| `progress` | Yes | `1.0` |
| `estimatedArrivalTime` | No | Actual arrival time |
| `baggageCarousel` | Yes | `"25"` |

**Display:**
- Header: El Al logo + flight number (left), "ON TIME" pill green `#2BA45F` (right)
- Top: Dark background `rgba(0,0,0,0.8)`, airport columns with green time text
- Center badge: "Arrived"
- Bottom bar: Dark `rgba(0,0,0,0.6)` — "Baggage Claim: 25"

---

### 9. Connection Error (`connectionError`)

A connection error has occurred.

| Field | Required | Example |
|-------|----------|---------|
| `status` | Yes | `.connectionError` |

**Display:**
- Header: El Al logo + flight number (left), "ERROR" pill grey `#6C6C6C` (right)
- Top: Dark background `rgba(0,0,0,0.8)`, airport columns with grey time text
- Center badge: "Connection is lost"
- Bottom bar: Dark `rgba(0,0,0,0.6)` — "Connection Error"

---

### 10. General Error (`generalError`)

A general error has occurred.

| Field | Required | Example |
|-------|----------|---------|
| `status` | Yes | `.generalError` |

**Display:**
- Header: El Al logo + flight number (left), "ERROR" pill grey `#6C6C6C` (right)
- Top: Dark background `rgba(0,0,0,0.8)`, airport columns with grey time text
- Center badge: "Something went wrong"
- Bottom bar: Dark `rgba(0,0,0,0.6)` — "General Error"

---

## Status Pill Reference (Header Row)

The old per-state badge colors have been replaced by a header-row status pill.

| Condition | Pill Text | Pill Background | Pill Foreground |
|-----------|----------|----------------|----------------|
| On time (most states) | "ON TIME" | Green `#2BA45F` | White |
| Delayed (`boardingDelayed`) | "DELAYED" | Yellow `#FEC630` | Dark text |
| Error (`connectionError`, `generalError`) | "ERROR" | Grey `#6C6C6C` | White |

## Badge Label Reference (Center Badge)

The center badge text (`badgeLabel`) differs from `displayName` for several states:

| Status | displayName | badgeLabel |
|--------|------------|------------|
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

## Bottom Bar Background Reference

| State | Bottom Bar Background |
|-------|----------------------|
| Check In | `rgba(0,0,0,0.6)` dark |
| Boarding Soon | `rgba(0,0,0,0.6)` dark |
| Boarding Delayed | `rgba(0,0,0,0.6)` dark |
| Boarding | `rgba(254,198,48,0.95)` yellow |
| Last Call | `rgba(254,198,48,0.95)` yellow |
| Taking Off | `rgba(0,0,0,0.6)` dark |
| On The Way | `rgba(0,0,0,0.6)` dark |
| Arrival | `rgba(0,0,0,0.6)` dark |
| Connection Error | `rgba(0,0,0,0.6)` dark |
| General Error | `rgba(0,0,0,0.6)` dark |

---

## Platform Keys

| iOS (Swift) | Android (Kotlin) | Display Name | Badge Label | Status Pill |
|-------------|-----------------|--------------|-------------|-------------|
| `.checkIn` | `CHECK_IN` | Check In | Baggage drop | ON TIME |
| `.boardingSoon` | `BOARDING_SOON` | Boarding Soon | Boarding soon | ON TIME |
| `.boardingDelayed` | `BOARDING_DELAYED` | Boarding Delayed | Boarding soon | DELAYED |
| `.boarding` | `BOARDING` | Boarding | Boarding | ON TIME |
| `.lastCall` | `LAST_CALL` | Last Call | Last call | ON TIME |
| `.takingOff` | `TAKING_OFF` | Taking Off | Taking off | ON TIME |
| `.onTheWay` | `ON_THE_WAY` | On The Way | On the way | ON TIME |
| `.arrival` | `ARRIVAL` | Arrival | Arrived | ON TIME |
| `.connectionError` | `CONNECTION_ERROR` | Connection Error | Connection is lost | ERROR |
| `.generalError` | `GENERAL_ERROR` | General Error | Something went wrong | ERROR |

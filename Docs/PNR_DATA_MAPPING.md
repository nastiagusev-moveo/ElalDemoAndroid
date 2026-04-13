# PNR Retrieve — Data Mapping

This document maps each Journey Live Activity field to the PNR retrieve API response, and identifies which fields require additional data sources.

---

## Data Source: PNR Retrieve API

**Endpoint:** El Al SOAP API (`dpel.elal.co.il:441`)
**Operation:** `RetrievePNR`
**Auth:** mTLS (client certificate)

### Response Structure

```
Output_RetrievePNR
├── PNR_Reply
│   ├── pnrHeader                      → Reservation info (PNR code, date)
│   ├── travellerInfo[]                → Passenger names
│   ├── originDestinationDetails[]
│   │   └── itineraryInfo[]            → Flight segments
│   │       ├── travelProduct          → Flight number, airports, times
│   │       ├── flightDetail           → Terminals
│   │       └── relatedProduct         → Booking status
│   ├── dataElementsMaster
│   │   └── dataElementsIndiv[]        → SSRs (docs, contact, baggage, seat requests)
│   └── dcsData                        → DCS check-in data
│       └── legSection[]
│           └── dcsLegInfo             → Seat assignment, passenger status
```

---

## Static Attributes — Field Mapping

| Live Activity Field | Available in PNR? | PNR Path | Format | Example |
|---|---|---|---|---|
| `flightNumber` | Yes | `itineraryInfo[i].travelProduct.companyDetail.identification` + `productDetails.identification` | Airline code + number | `"LY"` + `"337"` → `"LY 337"` |
| `airline` | Yes | `itineraryInfo[i].travelProduct.companyDetail.identification` | IATA code | `"LY"` |
| `passengerName` | Yes | `travellerInfo[i].passengerData[0].travellerInformation.passenger[0].firstName` + `.traveller.surname` | First + Last | `"MIRIAM"` + `"BARAZANI"` |
| `departureAirportCode` | Yes | `itineraryInfo[i].travelProduct.boardpointDetail.cityCode` | IATA code | `"TLV"` |
| `departureCity` | Needs lookup | Not in PNR — requires airport code → city name mapping | — | `"Tel Aviv"` |
| `arrivalAirportCode` | Yes | `itineraryInfo[i].travelProduct.offpointDetail.cityCode` | IATA code | `"AMS"` |
| `arrivalCity` | Needs lookup | Not in PNR — requires airport code → city name mapping | — | `"Amsterdam"` |
| `scheduledDepartureTime` | Yes | `itineraryInfo[i].travelProduct.product.depDate` + `.depTime` | DDMMYY + HHMM | `"260226"` + `"0600"` |
| `scheduledArrivalTime` | Yes | `itineraryInfo[i].travelProduct.product.arrDate` + `.arrTime` | DDMMYY + HHMM | `"260226"` + `"1015"` |
| `departureTerminal` | Yes | `itineraryInfo[i].flightDetail.departureInformation.departTerminal` | String | `"3"` |
| `arrivalTerminal` | Yes | `itineraryInfo[i].flightDetail.arrivalStationInfo.terminal` | String | `"3"` |

---

## Dynamic Content State — Field Mapping

### Available from PNR Retrieve

| Live Activity Field | PNR Path | Notes |
|---|---|---|
| `seat` | `dcsData.legSection[i].dcsLegInfo.seatDelivery.seatDetails.seatNumber` | Available after DCS check-in. Format: `"038B"` (needs parsing to `"38B"`) |
| `delayMinutes` | Can be derived | Compare `estimatedDepartureTime` vs `scheduledDepartureTime` if delay info is present |
| `estimatedDepartureTime` | Partially | Available if delay info exists in PNR |
| `estimatedArrivalTime` | Partially | Available if delay info exists in PNR |

### NOT Available from PNR — Requires Additional Sources

| Live Activity Field | Required Source | Notes |
|---|---|---|
| `gate` | Airport Operations / DCS system | Gate assignment comes from airport operations, not PNR |
| `boardingGroup` | Boarding pass / DCS system | Assigned at check-in or boarding pass generation |
| `checkInDesk` | Airport Operations | Desk assignments are managed by airport operations |
| `checkInStartTime` | Flight operations | Check-in window is operational data |
| `boardingTime` | Flight operations | Boarding time comes from flight operations |
| `gateClosingTime` | Flight operations | Gate closing is operational, not in PNR |
| `takeoffTime` | Flight tracking / ATC | Real-time takeoff data from flight tracking systems |
| `progress` | Flight tracking | Real-time position requires flight tracking API (e.g., FlightAware, FlightRadar24) |
| `currentAltitude` | Flight tracking | Real-time altitude from flight tracking |
| `baggageCarousel` | Airport Operations | Carousel assignment comes from arrival airport operations |
| `status` (journey state) | Multiple sources | See status mapping below |

---

## Journey Status — Data Source per State

| Status | Primary Trigger Source | Key Data Needed |
|---|---|---|
| `checkIn` | Flight operations | `checkInDesk`, `checkInStartTime` |
| `baggageDrop` | Flight operations | `checkInDesk`, closing time |
| `checkedIn` | DCS system (PNR `dcsData`) | `gate`, `seat` (from PNR), `boardingGroup`, `boardingTime` |
| `boarding` | Airport operations | `gate`, `boardingTime`, `gateClosingTime` |
| `finalCall` | Airport operations | `gate`, `gateClosingTime` |
| `takingOff` | Flight operations / ATC | `takeoffTime` |
| `onTheWay` | Flight tracking API | `progress`, `currentAltitude`, `estimatedArrivalTime` |
| `arrived` | Flight tracking / airport ops | `baggageCarousel`, `terminal` |
| `welcome` | Airport operations | `gate`, `baggageCarousel` |
| `cancelled` | Flight operations | `gate` (optional) |
| `delayed` | Flight operations | `delayMinutes`, `estimatedDepartureTime`, `estimatedArrivalTime`, `gate` |

---

## Additional Data from PNR (useful but not directly mapped)

| Data | PNR Path | Potential Use |
|---|---|---|
| PNR code | `pnrHeader[0].reservationInfo.reservation[0].controlNumber` | Display booking reference |
| Cabin class | `itineraryInfo[i].travelProduct.productDetails.classOfService` | Priority/lounge info |
| Booking status | `itineraryInfo[i].relatedProduct.status[]` | `["HK", "B"]` = confirmed |
| Email | `dataElementsIndiv[].serviceRequest.ssr.type === "CTCE"` | Push notification delivery |
| Phone | `dataElementsIndiv[].serviceRequest.ssr.type === "CTCM"` | SMS notification delivery |
| Passport/DOB | `dataElementsIndiv[].serviceRequest.ssr.type === "DOCS"` | Identity verification |
| Baggage allowance | `dataElementsIndiv[].serviceRequest.ssr.type === "NBAG"` | Baggage info display |
| Seat characteristics | `dcsLegInfo.seatDelivery.seatDetails.seatCharacteristic[]` | Seat type (window, aisle, etc.) |
| Passenger DCS status | `dcsLegInfo.paxStatus.statusDetails[]` | Check-in/boarding state indicators |

### DCS Passenger Status Codes

| Code | Meaning |
|---|---|
| `CAC` / `CAS` | Checked In |
| `BDD` / `BDS` | Boarding Done |
| `BGG` / `CBS` | Baggage |
| `NFL` / `TPS` | Not Flown |
| `NRG` / `RGS` | Not Ready / Ready |

---

## Summary

```
PNR Retrieve provides:
  ✅ Flight number, airline
  ✅ Passenger name
  ✅ Departure/arrival airport codes
  ✅ Scheduled departure/arrival times
  ✅ Departure/arrival terminals
  ✅ Seat assignment (after DCS check-in)
  ✅ Contact info (email, phone)
  ✅ Booking status
  ✅ Passenger DCS status codes

Requires additional sources:
  ❌ Gate number                → Airport Operations
  ❌ Boarding group             → DCS / Boarding Pass system
  ❌ Check-in desk              → Airport Operations
  ❌ Boarding/gate closing times → Flight Operations
  ❌ Takeoff time               → ATC / Flight Tracking
  ❌ Flight progress/altitude   → Flight Tracking API
  ❌ Baggage carousel           → Arrival Airport Operations
  ❌ City names                 → IATA airport code lookup table
  ❌ Real-time delay info       → Flight Operations
```

---

## Date/Time Format Notes

- PNR dates are in `DDMMYY` format (e.g., `"260226"` = February 26, 2026)
- PNR times are in `HHMM` 24-hour format (e.g., `"0600"` = 06:00, `"1015"` = 10:15)
- Day change indicator (`dayChangeIndicator: 1`) means arrival is the next calendar day
- All times need timezone conversion based on airport location

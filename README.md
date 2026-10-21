# LIFELANE – Bluetooth offline layer (Member 3)

Sends an ambulance alert from phone to phone over Bluetooth, with **no internet and no GPS** (Android 12+).
Every phone that should get alerts needs this app installed.

## How it works (version 2)
- **Ambulance** broadcasts a signed 20-byte alert every **1 second**. On **Stop** it broadcasts **"trip ended"** for 4 s.
- **Receiver + Relay** phones show the alert and pass it on, up to **3 or 5 hops**.
- **Smart relay:** if 2 other phones already passed the same alert on, a phone stays quiet. Stops jamming on busy roads.
- **Far-first:** phones with a weaker signal (further away) pass it on sooner, so the alert spreads outward faster.
- **Fake ambulance protection:** alerts carry a signature; alerts with a wrong signature are rejected.
- **Auto-expire:** no new alert for 15 s → alert clears. "Trip ended" → green **AMBULANCE PASSED**.
- **Battery saver:** slow scan when idle, fast scan while an alert is active.
- **Long range (Bluetooth 5):** optional extra long-range broadcast on phones that support it.

Message (27 bytes, v4): `magic | version=4 | tripId(4) | seq(2) | maxHops | type | lat(4) | lon(4) | heading | hopsLeft+senderLevel | senderId | signature(6)`
Signature = first 6 bytes of HMAC-SHA256 over the first 19 bytes.
Prototype note: the key is built into the app. A real system would give keys only to ambulances (e.g. issued by 108).

Version 2 is **not compatible** with version 1 – install the new APK on all phones.

**iPhone support:** the main broadcast carries the LifeLane service ID
`C51EB7FB-9202-43B3-B906-DC0E1B471453` (a free 128-bit UUID we generated). The message is in the
**scan response**. iPhones can only scan in the background for a specific service ID, so this is what lets a
locked iPhone get alerts. Long-range broadcasts carry both in one packet.

## iPhone: Source and Relay too (latest)
The iPhone app now has the same buttons and test settings as Android: **Start as SOURCE**, **Start as RECEIVER + RELAY**,
ambulance direction/position, my direction, chain test, FAKE test.

| iPhone role | Works? |
|---|---|
| Receiver (all colours, direction, travel buddy, passed, fake check) | ✅ also when locked |
| Source (ambulance) | ✅ only while the app is **open on screen** |
| Relay | ✅ only while the app is **open on screen** |

Why: Apple only lets apps broadcast a service ID + a name (28 bytes), and nothing Android can read in the background.
So the iPhone sends a **short message**: the alert packed into one 128-bit service ID starting with `LIFE`
(16 bytes) + an 8-character name (rest of the signature). Position precision ~11 m (India range). Android reads it
(`CompactMessage.kt`, scan filter on the `LIFE` prefix) and relays it in the normal 27-byte format. The iPhone
listens to everything while open, and only to the LIFELANE service ID when in the background.

### iPhone Source tests
| Test | Expected |
|---|---|
| iPhone: Start as SOURCE, N / BEFORE, app open. Android B: my direction North | B 🔴 RED, log DIRECT |
| Lock the iPhone Source | B's alert clears after ~15 s (iPhone can't send when locked) |
| iPhone Source → Android B → Android C (chain test 1) | C 🔴 RELAYED 1x |
| Android A Source → iPhone (Receiver, app open) → Android C (chain test 1) | C RELAYED 1x via the iPhone |
| iPhone Source: Stop | B 🟢 passed |
| iPhone Source with FAKE ticked | B log REJECTED fake alert |

## Version 4 – travel buddy + fixes
**Travel buddy:** phones that are SURE (GPS + direction) add their own decision to what they broadcast
(RED = in the ambulance's way, CLEAR = not). A phone that is NOT sure (GPS off, or not moving) follows its
"travel buddy": a sure phone it has heard **steadily for 10 s** with the strongest signal. Same-direction cars
stay near you; opposite-direction cars pass in seconds, so they never become your buddy. If the buddy leaves,
it switches to the next one. Phones in the ambulance's way (RED) always broadcast, so no-GPS phones near them
can follow them. Copied decisions are never re-broadcast as "sure".

**Fixes from testing:**
- Walking now counts as moving (0.7 m/s; also direction from two GPS points 10 m apart).
- Direction remembered for **2 minutes** (car stopped at a signal still gets RED).
- "Passed" rule: ambulance **more than 20 m past you** along its direction → 🟢 green (no need to stop the trip).
- More than 100 m to the side of the ambulance's line → other road → silent.
- Small turns no longer drop the alert: direction is **smoothed** (average of recent GPS readings), same direction = within **60°**, and RED is removed only after **3** "different direction" readings in a row.
- Version 4 is not compatible with version 3 – update all phones.

### Travel-buddy tests
| Phone A (source) | Phone B (sure) | Phone C (not sure) | Expected on C |
|---|---|---|---|
| North, 200 m BEFORE | my direction **North** → B is 🔴 | **Location OFF**, keep near B | 🟡 first, then 🔴 "travel buddy … in the ambulance's way" after ~10 s |
| North, 200 m BEFORE | my direction **South** → B is silent | Location OFF, near B | 🟡 first, then silent "travel buddy … not in the way" |
| North, 200 m BEFORE | North | Location OFF, B switched off after 15 s | goes back to 🟡 |

## Version 3 – same-direction alerts
The message now carries the **ambulance position + direction** (27 bytes, 6-byte signature). Every phone still
relays it, but each phone decides with **its own GPS** (no internet needed) whether to show it:

| Level | When |
|---|---|
| 🔴 RED "Give way" | within 600 m, **ahead** of the ambulance (±60°), and moving the **same direction** (±60°, direction smoothed) |
| 🟡 AMBER "Be alert" | ahead but my direction unknown (not moving), or my GPS is off |
| silent | behind / beside, opposite direction, or more than 600 m away |
| 🟢 "Passed" | was alerted, now behind the ambulance, or trip ended |

- **Source phone (A)** = the ambulance, or an online phone that got the cloud alert. Needs location permission.
- Receivers work without location permission too, but only get AMBER.
- Version 3 is not compatible with version 2 – update all phones.

### Direction tests (no driving needed – all phones in one place, outdoors or near a window)
| Test | Phone A (source) | Phone B (receiver) | Expected on B |
|---|---|---|---|
| Same direction, ahead | direction **North**, position **200 m BEFORE this spot** | my direction **North** | 🔴 RED |
| Opposite direction | North, 200 m BEFORE | my direction **South** | silent |
| Crossing road | North, 200 m BEFORE | my direction **East** | silent |
| Behind ambulance | North, **200 m AFTER this spot** | North | silent |
| Direction unknown | North, 200 m BEFORE | **Auto**, standing still | 🟡 AMBER |
| GPS off | North, 200 m BEFORE | Location OFF | 🟡 AMBER + "turn on location" |
| Relay to wrong-side phone | A North/BEFORE, B = South, C = North with chain test "1 time" | – | B silent but still relays; C 🔴 RED |
| Real drive | Auto + Real GPS in a moving vehicle | Auto, in a car ahead | 🔴 RED, then 🟢 when passed |

## Get the app
Push to GitHub → **Actions** → **Build APK** → download **LifeLane-BLE-apk** → install `app-debug.apk`.

## Tests
| Test | Setup | Expected |
|---|---|---|
| Basic | A = Ambulance, B = Receiver | B shows red alert "DIRECT" |
| Chain A→B→C | C: chain test = "1 time" | C shows "RELAYED 1 time(s)" |
| Chain A→B→C→D | C = "1 time", D = "2 times" | D shows "RELAYED 2 time(s)" |
| 5 hops | A: tick "allow 5 hops" | Alerts can go up to 5 phones deep |
| Ambulance passed | Tap Stop on A | Receivers turn green "AMBULANCE PASSED" |
| Auto-expire | Turn off Bluetooth on A | Receivers clear alert after 15 s |
| Fake ambulance | A: tick "send FAKE alert" | Receivers log "REJECTED fake alert", no alert shown |
| Smart relay | 4+ receivers close together | Some log "SKIPPED relay … other phones already relayed it" |
| Battery saver | Watch the log on a receiver | "Scan: SLOW" when idle, "Scan: FAST" during an alert |
| Long range | Tick "Long range" on A (if enabled) | Compare max distance with/without |

Tip: tick **Short range** on all phones to test chains inside one room.

## Before testing
- Turn on **Bluetooth**. Location is not needed on Android 12+ (needed on Android 11 and older).
- Allow the permissions the app asks for.
- Some brands (Xiaomi, Oppo, Vivo, Realme) may stop background apps: set Battery → "No restrictions" for the app.

## iPhone app (receive only) – folder `ios/`
Apple only lets iPhone apps broadcast a device name and service IDs, and nothing readable by Android while in
the background. So **iPhones receive alerts but cannot relay or act as the ambulance.** Sources: Apple
"Best Practices for Setting Up Your Local Device as a Peripheral" and "Core Bluetooth Background Processing".

What it does: scans for the LifeLane service ID (also when locked), checks the signature, shows a red alert and
a notification, green "AMBULANCE PASSED" on trip end, clears after 15 s with no signal.
In the background iOS may give only the service ID without the message; then the app still warns
("unverified") but cannot check the signature.

### Build and install (no Mac needed)
1. GitHub Actions → **Build iOS** → download **LifeLane-iOS-ipa** → unzip → `LifeLane-unsigned.ipa`.
2. On Windows install **iTunes** (from apple.com, not the Microsoft Store version) and **Sideloadly** (sideloadly.io).
3. Connect the iPhone by cable, open Sideloadly, drop in the .ipa, enter your Apple ID, click Start.
4. On the iPhone: Settings → General → VPN & Device Management → trust your Apple ID.
   On iOS 16+ also turn on Settings → Privacy & Security → **Developer Mode**.
5. Open LifeLane, allow Bluetooth and notifications.

With a free Apple ID the app stops opening after **7 days** – reinstall with Sideloadly.

### iPhone tests
| Test | Expected |
|---|---|
| Android A = Ambulance, iPhone open | Red alert "DIRECT from ambulance" |
| A → Android B relay, iPhone near B only | "RELAYED 1 time(s)" |
| Lock the iPhone | Notification "Ambulance nearby – give way" |
| Stop A | Green "AMBULANCE PASSED" |
| A sends FAKE alert | Log "REJECTED fake alert", no alert |

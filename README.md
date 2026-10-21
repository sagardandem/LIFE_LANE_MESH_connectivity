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

Message (27 bytes, v3): `magic | version=3 | tripId(4) | seq(2) | maxHops | type | lat(4) | lon(4) | heading | hopsLeft | senderId | signature(6)`
Signature = first 6 bytes of HMAC-SHA256 over the first 19 bytes.
Prototype note: the key is built into the app. A real system would give keys only to ambulances (e.g. issued by 108).

Version 2 is **not compatible** with version 1 – install the new APK on all phones.

**iPhone support:** the main broadcast carries the LifeLane service ID
`C51EB7FB-9202-43B3-B906-DC0E1B471453` (a free 128-bit UUID we generated). The message is in the
**scan response**. iPhones can only scan in the background for a specific service ID, so this is what lets a
locked iPhone get alerts. Long-range broadcasts carry both in one packet.

## Version 3 – same-direction alerts (latest)
The message now carries the **ambulance position + direction** (27 bytes, 6-byte signature). Every phone still
relays it, but each phone decides with **its own GPS** (no internet needed) whether to show it:

| Level | When |
|---|---|
| 🔴 RED "Give way" | within 600 m, **ahead** of the ambulance (±60°), and moving the **same direction** (±45°) |
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

# LifeLane – Bluetooth relay prototype (Member 3)

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

Message (20 bytes): `magic | version=2 | tripId(4) | seq(2) | maxHops | type | hopsLeft | senderId | signature(8)`
Signature = first 8 bytes of HMAC-SHA256 over the first 10 bytes.
Prototype note: the key is built into the app. A real system would give keys only to ambulances (e.g. issued by 108).

Version 2 is **not compatible** with version 1 – install the new APK on all phones.

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

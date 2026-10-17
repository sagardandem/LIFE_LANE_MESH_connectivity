# LifeLane – Bluetooth relay prototype (Member 3)

Sends an ambulance alert from phone to phone over Bluetooth, with **no internet**.
Phone A (ambulance) → Phone B (relay) → Phone C.

## How it works
- **Ambulance (Phone A)** broadcasts a small 17-byte alert every 2 seconds.
- **Receiver + Relay (Phones B, C)** listen. When a *new* alert arrives they show it and send it on again.
- Each alert can be passed on at most **3 times** (`hopsLeft` 3 → 2 → 1 → 0).
- Duplicates are ignored using `tripId` + message number.
- A small random wait (50–200 ms) before relaying stops phones sending at the same time.

Alert format (17 bytes): `magic 'L' | version | tripId(4) | seq(2) | hopsLeft(1) | lat(4) | lon(4)`

## Get the app (no Android Studio needed)
1. Push this folder to GitHub.
2. Open the repo → **Actions** tab → **Build APK** → open the latest run.
3. Download **LifeLane-BLE-apk** (zip) at the bottom, unzip, copy `app-debug.apk` to the phones and install it (allow "Install unknown apps").

## 3-phone test
| Phone | What to do |
|---|---|
| A | Tap **Start as AMBULANCE** |
| B | Tap **Start as RECEIVER + RELAY** |
| C | Tick **ignore direct ambulance signal**, then tap **Start as RECEIVER + RELAY** |

Phone C should show **"RELAYED 1 time(s)"**. That proves the alert went A → B → C.
Then stop Phone B: Phone C should stop getting new alerts.

Tip: tick **Short range** on all phones to test distance in a small space.

## Before testing
- Turn on **Bluetooth** and **Location** on all phones.
- Allow all permissions the app asks for.
- Android 8.0 or newer. iPhones are not supported.

## Things to record for the report
- Range: at what distance does B stop receiving? (open area, inside a car)
- Delay per hop (compare log times on each phone)
- Does it work with the screen off?
- Battery used in 1 hour

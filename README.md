# ARGD-OS Dashboard (Android companion)

Companion app for the DotOS watch (LILYGO T-Watch Ultra). It links the phone and
the watch over BLE: health metrics, notifications, Find, and GPX routes.

## What it does

One screen, one card per feature:

- **Watch** - scan and connect (or reconnect to the saved watch with no scan).
- **Health** - reads steps, heart rate (high / low over the last 30 min) and a
  derived sleep score from Health Connect and sends them to the watch. Shows
  when the last sync happened and what it carried. **Auto-sync** does it in the
  background on a schedule (15 / 30 / 60 min; 15 is WorkManager's floor).
- **Notifications** - forwards your phone's notifications to the watch. **Choose
  apps** lets you mute the noisy ones so they never reach the wrist.
- **Find** - ring the watch from the phone, or let the watch ring the phone
  (even with the app closed).
- **Routes** - send a `.gpx` route to the watch, from the in-app picker or by
  sharing a file from any app (for example Gaia GPS) to this one.

Stress is left out: Health Connect has no stress type, and a sleep *score* is
derived from the last sleep session's duration and its deep/REM share (see
`HealthConnectSource`).

### Health sync details

- Auto-sync reconnects to the watch by the address saved on your first manual
  connect (so **connect once** before enabling), by direct address, with no BLE
  scan.
- On Android 14+ it also requests **background** Health Connect access
  (`READ_HEALTH_DATA_IN_BACKGROUND`) so the worker can read while the app is
  closed. The manual **Sync now** button works as before.
- The schedule survives reboots (WorkManager persists it). Runs may be deferred
  a little by Android's Doze batching, which is fine for health metrics.

### Phone setup for Health Connect

1. In Gadgetbridge -> External integrations -> Health Connect, enable
   **Allow connection with Health Connect** (and "Sync after device sync").
2. Grant this app the Health Connect read permissions when it asks (steps,
   heart rate, sleep).

## How it talks to the watch

The firmware exposes a vendor health-input write characteristic on its Alert
Notification Service (a standalone 3rd GATT service did not register reliably on
the watch's BLE stack, so the characteristic rides the always-present ANS):

- Service `00001811-0000-1000-8000-00805f9b34fb` (Alert Notification Service)
- Write characteristic `a2470002-5a4b-4d55-9a3e-1c2d3e4f5a6b`

The app looks for the characteristic under the ANS first, and falls back to the
old standalone service `a2470001-5a4b-4d55-9a3e-1c2d3e4f5a6b` for older firmware.

Packet (little-endian): `[version=1][field mask][fields...]`, mask bits
sleep(1) / steps(2) / goal(4) / stress(8) / hr-avg(16); each present field
follows in bit order: sleep u8, steps u32, goal u32 (ignored by the watch - the
goal is set in the watch's Settings), stress u8, hr u16. See `HealthPacket.kt`.

## Find (ring the watch / ring the phone)

Two directions, over a dedicated characteristic on the ANS
(`a2470003-5a4b-4d55-9a3e-1c2d3e4f5a6b`):

- **Ring watch** (button in the app) → the watch wakes, flashes and buzzes at
  full strength plus a loud chime.
- **Ring phone** (Find on the watch) → the phone rings at **max alarm volume**
  (the alarm stream is used on purpose, so it rings through Do-Not-Disturb) and
  vibrates, with a Stop action and a 60 s safety auto-stop.

### The "Watch link active" persistent notification

Turning on **"Ring even when app is closed"** (Find card) starts a small
**foreground service** that keeps the BLE link so the watch can ring the phone
with the app closed. Android **requires** any such service to show an ongoing
notification while it runs — that is the **"Watch link active"** entry. It is
expected, not a bug or malware.

It is deliberately on the lowest-importance channel (**Watch link**): silent, no
banner, no vibration — it just sits at the bottom of the shade. The separate
**Find ringing** channel is the one that actually alerts when the watch calls.

To change it:

- **Don't want the background link?** Turn **"Ring even when app is closed"**
  OFF. The service stops and the notification disappears — the phone then only
  rings while the app is open.
- **Want the link but not the label?** Long-press the notification →
  turn off notifications for the **"Watch link"** channel only. On current
  Android this hides / minimises it while the service keeps running; leave the
  **"Find ringing"** channel on so the actual ring still alerts.

The service also re-arms after a reboot (`BootReceiver`), and hands off with the
in-app path — it is stopped while the app is open and (re)started when it leaves,
so only one BLE connection to the watch is ever held at a time.

## Building

CI (GitHub Actions) builds a debug APK on every push and uploads it as the
`argd-os-dashboard-debug` artifact - download it from the run's Artifacts
section, then install it (allow "install unknown apps" if prompted).

Locally: `gradle assembleDebug` (Android SDK + JDK 17). No Gradle wrapper is
committed; CI provisions Gradle 8.7.

## Requirements

- Android 8.0+ (minSdk 26)
- Bluetooth LE; grant Nearby-devices (BLUETOOTH_SCAN/CONNECT) when prompted
  (or Bluetooth + Location on Android 11 and older).

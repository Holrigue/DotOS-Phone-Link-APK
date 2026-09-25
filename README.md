# ARGD-OS Dashboard (Android companion)

Companion app for the ARGUS-Design-OS watch (LILYGO T-Watch Ultra). Its job is
to relay the wearer's health metrics from Gadgetbridge to the watch over BLE,
and later to act as a small phone-side control panel.

## Status

**V1.0: manual test harness.** Scan for the watch, connect, and push hand-set
health values so the whole app -> watch BLE path can be validated. This is also
how the "two centrals" question is tested: try sending while Gadgetbridge is
connected to the watch - if the write succeeds, the watch accepts a second
central.

**V1.1: Health Connect.** Reads the Amazfit's metrics from Health Connect (where
Gadgetbridge publishes them) and sends them to the watch:

- **Steps** (today's total) and **heart rate** (latest sample) - read directly.
- **Sleep score** - Health Connect has no score, so one is derived from the last
  sleep session's duration and its deep/REM share (see `HealthConnectSource`).
- **Stress** - left out; Health Connect has no stress type.

**V1.2: background auto-sync.** A toggle enables a periodic background job
(WorkManager) that reads Health Connect and pushes to the watch on its own -
no need to open the app each time.

- It reconnects to the watch by the address saved on your first manual connect
  (so **connect once** before enabling), by direct address, with no BLE scan.
- Interval is selectable (15 / 30 / 60 min; 15 is WorkManager's floor).
- On Android 14+ it also requests **background** Health Connect access
  (`READ_HEALTH_DATA_IN_BACKGROUND`) so the worker can read while the app is
  closed. The manual "Sync from Health Connect" button still works as before.

The schedule survives reboots (WorkManager persists it). Runs may be deferred a
little by Android's Doze batching, which is fine for health metrics.

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

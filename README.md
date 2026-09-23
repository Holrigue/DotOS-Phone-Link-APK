# ARGD-OS Dashboard (Android companion)

Companion app for the ARGUS-Design-OS watch (LILYGO T-Watch Ultra). Its job is
to relay the wearer's health metrics from Gadgetbridge to the watch over BLE,
and later to act as a small phone-side control panel.

## Status

**V1.0 (this version): manual test harness.** Scan for the watch, connect, and
push hand-set health values (sleep / steps / stress / heart) so the whole
app -> watch BLE path can be validated before any Gadgetbridge reading is wired
in. This is also how the "two centrals" question is tested: try sending while
Gadgetbridge is connected to the watch - if the write succeeds, the watch
accepts a second central.

**V1.1 (next): read Gadgetbridge.** Point the app at Gadgetbridge's auto-export
SQLite database, parse the latest samples, and send them automatically on an
interval.

## How it talks to the watch

The firmware exposes a vendor health-input GATT service:

- Service `a2470001-5a4b-4d55-9a3e-1c2d3e4f5a6b`
- Write characteristic `a2470002-5a4b-4d55-9a3e-1c2d3e4f5a6b`

Packet (little-endian): `[version=1][field mask][fields...]`, mask bits
sleep(1) / steps(2) / goal(4) / stress(8) / hr-avg(16); each present field
follows in bit order: sleep u8, steps u32, goal u32 (ignored by the watch - the
goal is set in the watch's Settings), stress u8, hr u16. See `HealthPacket.kt`.

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

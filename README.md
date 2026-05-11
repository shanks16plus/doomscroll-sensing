# Doomscroll Sensing App

Passive sensor and behaviour logger for Android. Built as research instrumentation for a Bachelor's thesis at the University of Twente.

## What it does

Runs silently in the background on a provided phone for the duration of a study week. Collects sensor and usage data locally — no network, no cloud.

## What it collects

- Accelerometer and gyroscope (50 Hz, screen-on only)
- Screen on / off / unlock events
- Foreground app and app switches
- Scroll events and direction
- Tap and double-tap interactions (social apps only)
- Logging pauses on password fields and lock screen

All data is encrypted on-device (AES-256) and exported via USB at the end of the study.

## Supported devices

| Device | OS | Notes |
|---|---|---|
| Google Pixel 4 | Android 13 (API 33) | Primary test device |
| OnePlus Nord CE 2 | Android 11–12 (API 30–32) | Fully compatible. OxygenOS requires one extra setup step: go to Settings → Battery → App Battery Management → find the app → enable **Allow auto-launch** and set background activity to **No restrictions**. Without this, OxygenOS may kill the service despite battery optimisation being disabled. |

## Tech stack

- Kotlin — Android API 26–35
- Foreground Service + AccessibilityService
- Moshi (JSONL serialisation)
- AndroidX Security (EncryptedFile, EncryptedSharedPreferences)
- WorkManager (watchdog), Coroutines

## Build

```bash
./gradlew assembleDebug
```

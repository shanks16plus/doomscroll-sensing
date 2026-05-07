# Researcher Setup Guide

## Prerequisites

- Android Studio Hedgehog (2023.1) or later
- JDK 17
- Google Pixel 4 running Android 13 (API 33)
- USB cable for sideloading and data export

## Build and install

```bash
git clone <repo-url>
cd application
./gradlew assembleDebug
```

Install the APK via Android Studio **Run** or via adb:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Preparing a device for a participant

1. Open the app. Note the auto-generated **Participant ID** shown at the top.
2. Grant all four permissions in order:
   - **Usage Access** -- redirects to system settings; find "Doomscroll Sensing" and enable.
   - **Accessibility Service** -- find "Doomscroll Sensing" under installed services and enable.
   - **Notifications** -- allow when prompted (required for the foreground service notification).
   - **Battery Optimization** -- confirm "Allow" to exempt the app from Doze.
3. Once all four show a checkmark, tap **Start Logging**.
4. The status line should read "LOGGING ACTIVE". The app now logs in the background even if the participant navigates away.

## Collecting data after the study period

1. Open the app on the participant's device.
2. Tap **Stop Logging** (optional but recommended before export).
3. Tap **Export Data**. The status text will show how many files were exported and the output path.
4. Connect via USB and copy the folder from the device:

```bash
adb pull /sdcard/Download/doomscroll_export/ ./participant_<ID>/
```

5. The exported files are plain-text JSONL (one JSON object per line, UTF-8). Each file covers one calendar day (Europe/Amsterdam timezone).

## Synthetic data for development

Generate test data without a real device:

```bash
python3 tools/synthetic_data_generator.py \
  --participant test-001 \
  --days 3 \
  --outdir ./test_data
```

## Log file format

Each line is a JSON object with an `event_type` discriminator. See `docs/SCHEMA.docx` for the full specification. Event types:

| event_type | Description |
|---|---|
| schema_version | Written once per file; contains schema version string |
| screen_state | Screen on / off / unlocked |
| app_session | Foreground / background transitions with app category |
| scroll | Scroll start/end with direction |
| tap | Single tap (Instagram and TikTok only) |
| accelerometer | 3-axis acceleration at ~50 Hz |
| gyroscope | 3-axis rotation rate at ~50 Hz |
| logging_pause | Logging paused (password field or keyguard) |
| logging_resume | Logging resumed |

## Troubleshooting

- **Logging stops after a few hours**: check that battery optimization is disabled for the app. Some OEMs add extra restrictions beyond stock Android -- on Pixel 4 this should not be an issue.
- **No scroll/tap events**: verify the Accessibility Service is enabled. It can be toggled off by the system after updates.
- **Export shows 0 files**: logging has not produced any data yet, or the log directory was cleared.

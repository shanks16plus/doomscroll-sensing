# Doomscroll Sensing App

Passive sensor and usage data logger for Android, built as research instrumentation for two parallel Bachelor's theses at the University of Twente (EEMCS faculty).

## Purpose

Logs accelerometer, gyroscope, screen state, foreground app, and gesture events on provided Google Pixel 4 phones distributed to study participants for ~1 week. Data supports:

- **Project A** — doomscrolling episode detection from passive sensing
- **Project B** — compulsive smartphone-checking pattern detection from passive sensing

## Privacy by design

- Zero network behaviour — no cloud, no telemetry, no upload
- All logs encrypted at rest (AES-256 via AndroidX Security)
- No raw touch coordinates, screen content, keystrokes, or location
- Logging pauses on password fields and lock screen
- Data export via USB only

## Tech stack

- Kotlin, single-activity architecture
- Target: Google Pixel 4 (Android 13, API 33)
- Min SDK 26, compile SDK 35
- Foreground service + AccessibilityService
- Moshi for JSONL serialisation
- Coroutines for async I/O

## Building

```bash
./gradlew assembleDebug
```

## Repo structure

```
app/src/main/kotlin/nl/utwente/doomscroll/
  ├── MainActivity.kt
  ├── service/          # Foreground sensor logging service
  ├── accessibility/    # Gesture detection AccessibilityService
  ├── storage/          # Encrypted JSONL event logger
  ├── model/            # Event data model (sealed class hierarchy)
  ├── classifier/       # App categorisation lookup
  └── util/
app/src/main/assets/
  └── app_categories.json
docs/
  ├── SCHEMA.docx       # Data schema specification
  └── ETHICS.docx       # Privacy and ethics commitments
tools/
  └── synthetic_data_generator.py
```

## Researchers

- Shashank Bajoria — doomscrolling detection
- Supervisor: Gwenn Englebienne (University of Twente)

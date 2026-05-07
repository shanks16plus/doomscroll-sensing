#!/usr/bin/env python3
"""Generate synthetic JSONL files matching the doomscroll-sensing-app schema.

Produces plausible sensor and usage data so the analysis pipeline can be
developed without waiting for real data collection.

Usage:
    python synthetic_data_generator.py --participant P01 --days 3 --outdir ./synthetic
"""

import argparse
import json
import math
import os
import random
from datetime import datetime, timedelta, timezone

SCHEMA_VERSION = "1.1.0"

SOCIAL_APPS = [
    ("com.instagram.android", "SOCIAL", "com.instagram.android.activity.MainTabActivity"),
    ("com.zhiliaoapp.musically", "SOCIAL", "com.ss.android.ugc.aweme.main.MainActivity"),
    ("com.twitter.android", "SOCIAL", "com.twitter.android.HomeTabTimeline"),
    ("com.reddit.frontpage", "SOCIAL", "com.reddit.frontpage.MainActivity"),
    ("com.facebook.katana", "SOCIAL", "com.facebook.katana.LoginActivity"),
    ("com.snapchat.android", "SOCIAL", "com.snap.mushroom.MainActivity"),
]
YOUTUBE_MAIN = ("com.google.android.youtube", "ENTERTAINMENT", "com.google.android.youtube.HomeActivity")
YOUTUBE_SHORTS = ("com.google.android.youtube", "SOCIAL", "com.google.android.youtube.shorts.ui.ShortsSfvActivity")
OTHER_APPS = [
    ("com.whatsapp", "MESSAGING", "com.whatsapp.HomeActivity"),
    ("org.telegram.messenger", "MESSAGING", "org.telegram.ui.LaunchActivity"),
    ("com.google.android.gm", "PRODUCTIVE", "com.google.android.gm.ConversationListActivity"),
    ("com.microsoft.teams", "PRODUCTIVE", "com.microsoft.teams.MainActivity"),
    YOUTUBE_MAIN,
    ("com.spotify.music", "ENTERTAINMENT", "com.spotify.music.MainActivity"),
    ("com.android.chrome", "BROWSER", "com.google.android.apps.chrome.Main"),
    ("com.google.android.apps.maps", "UTILITY", "com.google.android.maps.MapsActivity"),
    ("com.android.settings", "UTILITY", "com.android.settings.Settings"),
]
ALL_APPS = SOCIAL_APPS + OTHER_APPS + [YOUTUBE_SHORTS]

INTERACTION_TYPES = ["LIKE", "DOUBLE_TAP_LIKE", "COMMENT_OPEN", "SHARE", "SAVE", "OTHER"]

AMS = timezone(timedelta(hours=2))


def ts_ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


def schema_version_event(t: datetime, pid: str) -> dict:
    return {
        "event_type": "schema_version",
        "timestamp_ms": ts_ms(t),
        "participant_id": pid,
        "schema_version": SCHEMA_VERSION,
    }


def generate_imu(t: datetime, pid: str, duration_s: float) -> list[dict]:
    """Generate 50 Hz accelerometer + gyroscope pairs."""
    events = []
    samples = int(duration_s * 50)
    for i in range(samples):
        ms = ts_ms(t) + i * 20
        base_ax, base_ay, base_az = 0.1, 9.81, -0.2
        events.append({
            "event_type": "accelerometer",
            "timestamp_ms": ms,
            "participant_id": pid,
            "x": round(base_ax + random.gauss(0, 0.3), 4),
            "y": round(base_ay + random.gauss(0, 0.5), 4),
            "z": round(base_az + random.gauss(0, 0.3), 4),
        })
        events.append({
            "event_type": "gyroscope",
            "timestamp_ms": ms,
            "participant_id": pid,
            "x": round(random.gauss(0, 0.05), 5),
            "y": round(random.gauss(0, 0.05), 5),
            "z": round(random.gauss(0, 0.05), 5),
        })
    return events


def generate_session(t: datetime, pid: str) -> tuple[list[dict], datetime]:
    """Generate one unlock-to-lock session with app usage, scrolls, taps, IMU."""
    events = []
    events.append({"event_type": "screen_state", "timestamp_ms": ts_ms(t),
                    "participant_id": pid, "state": "ON"})
    t += timedelta(seconds=random.uniform(0.5, 2))
    events.append({"event_type": "screen_state", "timestamp_ms": ts_ms(t),
                    "participant_id": pid, "state": "UNLOCKED"})

    session_duration = random.uniform(30, 600)
    session_end = t + timedelta(seconds=session_duration)
    cursor = t

    num_apps = random.randint(1, 5)
    for _ in range(num_apps):
        if cursor >= session_end:
            break
        app_pkg, app_cat, app_activity = random.choice(ALL_APPS)
        events.append({
            "event_type": "app_session", "timestamp_ms": ts_ms(cursor),
            "participant_id": pid, "event": "FOREGROUND",
            "package_name": app_pkg, "category": app_cat,
            "activity_class": app_activity,
        })

        app_duration = random.uniform(10, session_duration / num_apps)
        app_end = min(cursor + timedelta(seconds=app_duration), session_end)

        # Scrolls with dwell time
        num_scrolls = random.randint(0, 15)
        scroll_times = sorted([
            cursor + timedelta(seconds=random.uniform(0, (app_end - cursor).total_seconds()))
            for _ in range(num_scrolls)
        ])
        prev_scroll_ts = None
        for st in scroll_times:
            direction = random.choice(["UP", "DOWN", "DOWN", "DOWN"])
            dwell = int((st - prev_scroll_ts).total_seconds() * 1000) if prev_scroll_ts else None
            scroll_start = {
                "event_type": "scroll_event", "timestamp_ms": ts_ms(st),
                "participant_id": pid, "event": "SCROLL_START",
                "direction": direction, "foreground_app": app_pkg,
            }
            if dwell is not None:
                scroll_start["dwell_time_ms"] = dwell
            events.append(scroll_start)
            events.append({
                "event_type": "scroll_event",
                "timestamp_ms": ts_ms(st + timedelta(seconds=random.uniform(0.3, 3))),
                "participant_id": pid, "event": "SCROLL_END",
                "direction": direction, "foreground_app": app_pkg,
            })
            prev_scroll_ts = st

        # Taps with interaction types (social apps only)
        is_social = app_pkg in [a[0] for a in SOCIAL_APPS] or app_activity == YOUTUBE_SHORTS[2]
        if is_social:
            num_taps = random.randint(0, 8)
            for _ in range(num_taps):
                tt = cursor + timedelta(seconds=random.uniform(0, (app_end - cursor).total_seconds()))
                tap_type = random.choice(["SINGLE", "SINGLE", "SINGLE", "DOUBLE"])
                if tap_type == "DOUBLE":
                    interaction = "DOUBLE_TAP_LIKE"
                else:
                    interaction = random.choices(
                        INTERACTION_TYPES,
                        weights=[30, 0, 10, 5, 5, 50],
                    )[0]
                tap_ev = {
                    "event_type": "tap_event", "timestamp_ms": ts_ms(tt),
                    "participant_id": pid, "tap_type": tap_type,
                    "foreground_app": app_pkg,
                    "interaction_type": interaction,
                }
                if interaction == "LIKE":
                    tap_ev["view_description"] = "Like"
                elif interaction == "COMMENT_OPEN":
                    tap_ev["view_description"] = "comment_field_focused"
                elif interaction == "SHARE":
                    tap_ev["view_description"] = "Share"
                events.append(tap_ev)

        # IMU snippet (2 seconds per app, to keep file size reasonable)
        events.extend(generate_imu(cursor, pid, min(2.0, app_duration)))

        events.append({
            "event_type": "app_session", "timestamp_ms": ts_ms(app_end),
            "participant_id": pid, "event": "BACKGROUND",
            "package_name": app_pkg, "category": app_cat,
            "activity_class": app_activity,
        })
        cursor = app_end

    events.append({"event_type": "screen_state", "timestamp_ms": ts_ms(session_end),
                    "participant_id": pid, "state": "OFF"})
    return events, session_end


def generate_day(date: datetime, pid: str) -> list[dict]:
    """Generate a full day of synthetic data."""
    events = []
    day_start = date.replace(hour=7, minute=0, second=0, microsecond=0)
    day_end = date.replace(hour=23, minute=30, second=0, microsecond=0)

    events.append(schema_version_event(day_start, pid))

    cursor = day_start
    while cursor < day_end:
        gap = timedelta(minutes=random.uniform(5, 90))
        cursor += gap
        if cursor >= day_end:
            break
        session_events, cursor = generate_session(cursor, pid)
        events.extend(session_events)

        # Occasional logging pause (simulate password field)
        if random.random() < 0.05:
            events.append({
                "event_type": "logging_pause", "timestamp_ms": ts_ms(cursor),
                "participant_id": pid, "reason": "PASSWORD_FIELD",
            })
            cursor += timedelta(seconds=random.uniform(5, 30))
            events.append({
                "event_type": "logging_resume", "timestamp_ms": ts_ms(cursor),
                "participant_id": pid, "reason": "PASSWORD_FIELD",
            })

    events.sort(key=lambda e: e["timestamp_ms"])
    return events


def main():
    parser = argparse.ArgumentParser(description="Generate synthetic doomscroll sensing data")
    parser.add_argument("--participant", default="P01", help="Participant ID")
    parser.add_argument("--days", type=int, default=7, help="Number of days")
    parser.add_argument("--outdir", default="./synthetic", help="Output directory")
    parser.add_argument("--start-date", default=None, help="Start date YYYY-MM-DD (default: today)")
    args = parser.parse_args()

    os.makedirs(args.outdir, exist_ok=True)
    start = datetime.strptime(args.start_date, "%Y-%m-%d").replace(tzinfo=AMS) if args.start_date else datetime.now(AMS)

    for day_offset in range(args.days):
        date = start + timedelta(days=day_offset)
        date_str = date.strftime("%Y-%m-%d")
        events = generate_day(date, args.participant)
        filename = f"{args.participant}_{date_str}.jsonl"
        filepath = os.path.join(args.outdir, filename)
        with open(filepath, "w", encoding="utf-8") as f:
            for event in events:
                f.write(json.dumps(event) + "\n")
        print(f"  {filename}: {len(events)} events")

    print(f"Done. {args.days} files written to {args.outdir}/")


if __name__ == "__main__":
    main()

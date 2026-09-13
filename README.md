# Meeting Minder

An android-only episode6 app for one job: **every morning, look at today, decide which meetings you're actually attending, get loud alarms for those, and text your partner when you're busy.** During the day it watches the calendar and nags you to re-send the schedule when meetings appear or move.

> **Status: scaffold.** The build system, CI and release tooling are in place; the app itself currently launches to a placeholder screen. `TODO.md` is the source of truth for what gets built and in what order.

## Planned features

- **Single-day itinerary** — a Google-Calendar-style timeline with an hour grid, proportional event heights, side-by-side overlaps, and horizontal swiping between days
- **Tap to select** — tapping an event means "I'm going to this"; the FAB reads "Set alarms (N)"
- **Loud alarms** — in-app, exact, alarm-clock class: a full-screen activity that wakes the screen and plays a randomized, obnoxious alert so you never habituate to it
- **Share the schedule** — the system share sheet with generated text listing only your busy time ranges, no titles
- **Change monitoring** — after you've shared, new/moved/cancelled meetings raise a "your schedule changed since you shared it" notification with one-tap re-share
- **RSVP** — setting alarms marks each chosen meeting "Yes, going" in the calendar

Every calendar on every account is read through the Android Calendar Provider. **The app has no network access — ever**, and must never request the `INTERNET` permission; the only thing it writes back is your RSVP.

## Tech stack

| Layer | Choice |
|-------|--------|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 (episode6 orange, dynamic colour off) |
| State | [redux-store-flow](https://github.com/episode6/redux-store-flow) — one app-wide `StoreFlow` plus thin per-screen ViewModels |
| Navigation | Navigation Compose with type-safe `@Serializable` routes |
| Persistence | Room (day plans, scheduled alarms, change snapshots) + DataStore (settings) |
| DI | [Metro](https://github.com/ZacSweers/metro) |
| Async | Kotlin Coroutines & Flow |
| Background | AlarmManager (exact alarms) + WorkManager (change detection) |

## Requirements

- Any recent JDK to launch gradlew; the Gradle daemon itself is pinned to Azul 21 by `gradle/gradle-daemon-jvm.properties` and provisioned automatically
- Android SDK platform 37 (`compileSdk`; `targetSdk` is 36)
- minSdk 31

## Build & run

```bash
./gradlew assembleDebug     # compile
./gradlew check             # lint, unit tests, release verification
./gradlew :app:installDebug # install on a connected device
```

Local builds are always **snapshot** builds: they install side-by-side with the released app as "Meeting Minder (SNAPSHOT)". Each build flavor has its own applicationId so none of them clobber each other: `com.episode6.meetingminder` (release), `com.episode6.meetingminder.snapshot` (CI snapshot APKs), plus a `.debug` suffix on debug builds. The app version comes from `self.versions.toml`; the versionCode is derived automatically (see [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)).

## Releases

Signed release APKs are attached to [GitHub releases](https://github.com/episode6/meeting-minder/releases) by CI on `v*` tags. The release process (release branches, version bumps, hotfixes) is documented in [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

## Project layout

```
app/src/main/java/com/episode6/meetingminder/
├── di/            Metro graph and ViewModel factory
├── model/         Calendar events, day plans, busy ranges
├── store/         AppState, actions, reducer, side effects
├── data/          Calendar provider repository, Room, settings
├── alarm/         Scheduling, receivers, ringing service + activity
├── monitor/       Change detection worker and notifications
├── share/         Share-text formatting
├── permissions/   Permission state and request intents
└── ui/            Theme, navigation, day view, onboarding, alarm screen
```

See [AGENTS.md](AGENTS.md) for the conventions this repo expects from contributors and AI assistants, and `TODO.md` for the full spec and work plan.

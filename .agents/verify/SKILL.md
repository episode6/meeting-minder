---
name: verify
description: How to verify meeting-minder changes end-to-end by driving the real Android app on a device/emulator (install debug build, launch, swipe days, select meetings, set alarms, share, screenshots). Use when verifying UI, calendar or alarm changes in this repo, or when asked to run/screenshot the Android app.
---

# Verifying meeting-minder on Android

The app is a single-module Compose app (`app/`). The only real-runtime surface is a
device/emulator: unit tests (`./gradlew test`) cover pure logic and Roborazzi covers the
previews, but neither exercises the Calendar Provider, `AlarmManager` or the
full-screen ringing activity.

## Build + install

```bash
./gradlew :app:installDebug
# local builds are snapshots and debug builds append a .debug suffix, so they coexist
# with snapshot/release installs (the activity class keeps the base package — it
# follows the fixed namespace)
adb shell am start -n com.episode6.meetingminder.snapshot.debug/com.episode6.meetingminder.MainActivity
adb shell pm clear com.episode6.meetingminder.snapshot.debug   # reset to empty state
```

Check for a connected device first (`adb devices` — state must be `device`). With
multiple devices, set `ANDROID_SERIAL` or pass `adb -s <serial>`.

## Granting what the app needs

A fresh install has nothing granted, which is what the onboarding screen is for — but
when you want to skip straight to the day view:

```bash
PKG=com.episode6.meetingminder.snapshot.debug
adb shell pm grant $PKG android.permission.READ_CALENDAR
adb shell pm grant $PKG android.permission.WRITE_CALENDAR
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
# exact alarms and full-screen intents are "special access", not runtime permissions:
adb shell appops set $PKG SCHEDULE_EXACT_ALARM allow
adb shell appops set $PKG USE_FULL_SCREEN_INTENT allow
```

## Seeding calendar data

An emulator has no calendars until an account exists. The fastest reproducible route is
to insert rows straight into the provider with `content insert` (a local calendar with
`ACCOUNT_TYPE=LOCAL` needs no sign-in), then add `Events` and let the provider expand
`Instances`. Prefer doing this from an instrumented test when the scenario needs to be
repeatable; drive it by hand only for one-off visual checks.

## Core flow to exercise

> **Current state:** until the pager and data loading land (TODO.md PR-6) the app launches to
> an empty day timeline (date title, Today action, all-day row hidden, hour grid with no
> events, overflow menu with Permissions/Settings placeholders, Check for updates and the
> license notices). The chip states are reviewed through the Roborazzi previews under
> `app/src/test/screenshots/` rather than on device, so verifying a change today
> means confirming it builds, installs, launches to that screen and the overflow routes
> work. `adb shell setprop log.tag.MeetingMinderStore DEBUG` logs every dispatched
> store action's type. The flow below is the target; exercise
> whichever parts of it exist when you verify.

Day view (launch screen) → swipe left/right between days → tap meetings to select them
(chip fills, check appears) → FAB reads "Set alarms (N)" → tap it (chips gain a bell +
alarm time, the RSVP goes to "Yes, going" in the calendar) → FAB becomes
"Share schedule" → tap and confirm the share text lists busy ranges only, no titles.
Then move or add an event in Google Calendar and confirm the "changed since you shared"
notification and in-app banner arrive.

Alarms: set one a minute or two out, lock the screen, and confirm the ringing activity
comes up over the lock screen with the screen woken and a sound playing. Dismiss and
snooze both need checking — snooze must reschedule, not cancel.

## Driving + screenshots

- `adb shell input tap X Y`, `adb shell input swipe X1 Y1 X2 Y2`, screenshots via
  `adb exec-out screencap -p > shot.png`.
- The ringing screen shows over the lock screen: `adb shell input keyevent 26` to sleep
  the display first, and screenshot after it fires.
- `adb shell dumpsys alarm | grep meetingminder` shows what's actually scheduled — the
  quickest way to tell a scheduling bug from a UI bug.
- `adb shell dumpsys notification --noredact | grep -A5 meetingminder` for the
  change-detection notification.
- Landscape probe: `settings put system user_rotation 1` (and back to 0).

## Gotchas

- Debug, snapshot and release builds all install side-by-side (three distinct
  applicationIds) — make sure you're looking at the install you just built.
- "Today" moves. Seed events relative to `now` rather than at fixed dates, or screenshots
  stop reproducing tomorrow.
- Exact alarms are throttled if the app is force-stopped — relaunch rather than
  `am force-stop` between runs.
- Changing selection after alarms are set deliberately reverts the FAB to "Set alarms";
  that is the reconcile state, not a bug.

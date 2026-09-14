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

```bash
CAL='content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=me@test.com&account_type=LOCAL'
adb shell content insert --uri "$CAL" --bind account_name:s:me@test.com --bind account_type:s:LOCAL \
  --bind name:s:test --bind calendar_displayName:s:"Test Cal" --bind calendar_color:i:-16776961 \
  --bind calendar_access_level:i:700 --bind ownerAccount:s:me@test.com --bind visible:i:1 --bind sync_events:i:1 \
  --bind calendar_timezone:s:America/New_York
adb shell content query --uri content://com.android.calendar/calendars --projection _id:name   # note the id
# a solo block (no attendees): rendered, selectable, alarm-able, never RSVP'd
adb shell content insert --uri content://com.android.calendar/events --bind calendar_id:i:<cal> --bind title:s:"Focus" \
  --bind dtstart:l:<ms> --bind dtend:l:<ms> --bind eventTimezone:s:America/New_York --bind hasAttendeeData:i:1
```

### Seeding an RSVP-able invite

"Set alarms" answers "Yes, going" for every meeting it arms (TODO.md §4.6). To exercise
that path the event needs an organizer other than you, `hasAttendeeData = 1`, and two
attendee rows — you (`INVITED`, relationship `ATTENDEE`) and the organizer
(`ACCEPTED`, relationship `ORGANIZER`); the provider mirrors your row into
`selfAttendeeStatus` on insert. Constants: relationship `1` attendee / `2` organizer,
type `1` required, status `1` accepted / `3` invited.

```bash
adb shell content insert --uri content://com.android.calendar/events --bind calendar_id:i:<cal> \
  --bind title:s:"Design review" --bind dtstart:l:<ms> --bind dtend:l:<ms> --bind eventTimezone:s:America/New_York \
  --bind hasAttendeeData:i:1 --bind organizer:s:boss@test.com
adb shell content query --uri content://com.android.calendar/events --projection _id:title   # note the event id
adb shell content insert --uri content://com.android.calendar/attendees --bind event_id:i:<eid> \
  --bind attendeeEmail:s:me@test.com --bind attendeeRelationship:i:1 --bind attendeeType:i:1 --bind attendeeStatus:i:3
adb shell content insert --uri content://com.android.calendar/attendees --bind event_id:i:<eid> \
  --bind attendeeEmail:s:boss@test.com --bind attendeeRelationship:i:2 --bind attendeeType:i:1 --bind attendeeStatus:i:1
```

For the recurring shape, insert the event with `--bind rrule:s:"FREQ=DAILY;COUNT=5"` and
`--bind duration:s:PT30M` instead of `dtend`; "Set alarms" then inserts an exception for
the one occurrence rather than touching the series.

Select the chip, tap "Set alarms (1)", and check:

```bash
# plain event: our attendee row and the mirrored selfAttendeeStatus both read accepted (1),
# and the event is dirty (1) for the (here non-existent) sync adapter — it stays dirty on
# a LOCAL calendar, so the chip's tick never promotes to SYNCED there; that's expected
adb shell content query --uri content://com.android.calendar/events/<eid> --projection selfAttendeeStatus:dirty
adb shell content query --uri content://com.android.calendar/attendees --where "event_id=<eid>" \
  --projection attendeeEmail:attendeeStatus
# recurring event: a new exception row pointing back at the series, itself accepted
adb shell content query --uri content://com.android.calendar/events --where "original_id=<eid>" \
  --projection _id:original_id:originalInstanceTime:selfAttendeeStatus:dirty
```

The chip shows a small tick after its alarm time once the write went through. A solo
block, an event you organise, or one you already accepted gets no RSVP and no mark; a
calendar with `calendar_access_level` below 300, or an invite whose attendee rows don't
include `ownerAccount` (an alias), shows the "couldn't RSVP" hint instead. On a real
Google account the response should appear on calendar.google.com within a minute or so
of the next sync — that is the release gate for this feature, and it can't be checked on
a `LOCAL` calendar.

## Core flow to exercise

> **Current state:** the day view, selection and alarm scheduling are live (TODO.md
> PR-8): the app launches to today's page of the day pager with the device's visible
> calendars loaded — swipe between days (the app-bar date and "N meetings" subtitle follow
> the settled page), Today scrolls back, long-press a chip to open it in the calendar app,
> and an event inserted with `content insert` (see "Seeding calendar data") appears within
> a second or two while the day is on screen. Tap chips to select them, tap "Set alarms (N)"
> (chips gain a bell + alarm time, the subtitle reads "N alarms set · not shared yet" in
> orange with a bell, the FAB flips to a solid orange "Share schedule" — a placeholder
> snackbar until PR-9). Deselecting an armed chip reverts the FAB to "Set alarms (N)"
> without cancelling anything; deselecting every armed chip leaves it as "Clear alarms",
> and the tap cancels them (`dumpsys alarm` should then show none). Setting alarms also
> RSVPs "Yes, going" for each armed meeting that has an invite (see "Seeding an RSVP-able
> invite"): the chip gains a small tick after its alarm time. A fired alarm posts
> a plain high-priority notification for now (the full-screen ringing screen is PR-10).
> Onboarding needs calendar, notifications and exact alarms granted before
> the day view shows. The chip states are also reviewed through the Roborazzi previews
> under `app/src/test/screenshots/`. `adb shell setprop log.tag.MeetingMinderStore DEBUG` logs every dispatched
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
- `adb shell dumpsys alarm | grep -A12 meetingminder` shows what's actually scheduled — the
  quickest way to tell a scheduling bug from a UI bug. Each alarm is an `RTC_WAKEUP`
  alarm-clock entry whose operation carries `meetingminder://alarm/{alarmId}`.
- `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p $PKG` exercises the
  re-arm path without rebooting (check `dumpsys alarm` again afterwards); `adb shell cmd
  deviceidle force-idle` checks that a scheduled alarm still fires in Doze.
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

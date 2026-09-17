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
block, an event you organise, one you already accepted or declined, or one the organizer
cancelled gets no RSVP and no mark (a declined or cancelled one gets no alarm either: the
snackbar reads "1 set, 1 skipped (declined or cancelled)"); a
calendar with `calendar_access_level` below 300, or an invite whose attendee rows don't
include `ownerAccount` (an alias), shows the "couldn't RSVP" hint instead. On a real
Google account the response should appear on calendar.google.com within a minute or so
of the next sync — that is the release gate for this feature, and it can't be checked on
a `LOCAL` calendar.

### Seeding a "Family" calendar for the busy-block sync

The busy-calendar sync (TODO.md §4.7) writes one bare `busy` event per merged busy range
of a shared day to the calendar chosen in Settings → Busy calendar, defaulting to the one
named "Family". A second `LOCAL` calendar with that display name is enough to exercise the
write path on an emulator (the write and its bookkeeping landed in PR-15b; the Share wiring
and the Settings toggle's cleanup are PR-15c's, so until that lands the sync can't be
triggered from the UI):

```bash
FAM='content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=me@test.com&account_type=LOCAL'
adb shell content insert --uri "$FAM" --bind account_name:s:me@test.com --bind account_type:s:LOCAL \
  --bind name:s:family --bind calendar_displayName:s:"Family" --bind calendar_color:i:-43776 \
  --bind calendar_access_level:i:700 --bind ownerAccount:s:me@test.com --bind visible:i:1 --bind sync_events:i:1 \
  --bind calendar_timezone:s:America/New_York
```

Then Settings → Busy calendar → turn on "Sync busy times to a calendar" (Family is
auto-picked; the FAB reads "Sync & Share"), select a meeting, set alarms, tap "Sync &
Share" and dismiss the chooser. Check the calendar:

```bash
# one row per busy range: title=busy, the range's dtstart/dtend, eventTimezone = the device zone,
# availability=0 (busy), hasAlarm=0, customAppPackage = the installed build's applicationId,
# an EMPTY description/eventLocation/eventColor/organizer, and dirty=1 (a plain insert waiting
# for the — here non-existent — sync adapter)
adb shell content query --uri content://com.android.calendar/events --where "title='busy'" \
  --projection _id:calendar_id:title:dtstart:dtend:eventTimezone:availability:hasAlarm:customAppPackage:description:eventLocation:eventColor:organizer:dirty
adb shell content query --uri content://com.android.calendar/attendees --where "event_id=<busy id>"   # no rows
```

No "busy" chip may appear in the day view, and re-sharing the same selection must leave the
same `_id` in place (an unchanged range keeps its event); deselecting and re-sharing, or
"Mark as not shared", must remove it (`deleted=1` on a synced calendar, gone outright on a
`LOCAL` one).

**Human gate before PR-15b merges — a real Google "Family" calendar.** A `LOCAL` calendar
has no sync adapter, so it can't answer the one question the emulator can't: whether
`customAppPackage` survives a sync round trip (the block goes up, Google's adapter writes it
back down). On a phone with a Google account that has a Family calendar, share a day with the
sync on, wait for the sync (or pull to refresh in Google Calendar), then run the same
`content query` with `--where "title='busy'"`: `dirty` should now read `0`, the block should
appear on calendar.google.com with the title `busy` and nothing else, and `customAppPackage`
should still equal the app's package. If the column comes back empty, the marker degrades to
a same-device hint and the `busy_block` table alone does the hiding (spec §2.6, decision 8):
nothing else changes, but say so in the PR. Two more things only a real account can answer,
checked on the same block: that it shows as **busy** on calendar.google.com (the
`availability` mapping is what the partner's free/busy view relies on), and that **no
reminder fires** on the phone at its start — `hasAlarm=0` with no `reminders` rows doesn't
necessarily reach Google as `reminders.useDefault = false`, and the calendar's default
notification would make every busy block buzz.

## Core flow to exercise

The full v1.0 flow (TODO.md PR-1 through PR-13) is implemented, so every step below should
work end to end. The app launches to today's page of the day pager with the device's
visible calendars loaded — swipe between days (the app-bar date and "N meetings" subtitle
follow the settled page), Today scrolls back, long-press a chip for its menu ("Open in
calendar" opens it in the calendar app; "Respond Yes / No / Maybe" appear only for an invite
with a self-attendee row and write that answer for the one occurrence, with a "Responded …"
snackbar and the chip updating at once — a No makes it dashed and unselectable), the Refresh
button shows "Refreshing calendars…" and reloads the shown days (on an emulator with only a
`LOCAL` calendar there is no sync adapter, so nothing else happens), and an event inserted
with `content insert` (see "Seeding calendar data") appears
within a second or two while the day is on screen. Tap chips to select them, tap
"Set alarms (N)" (chips gain a bell + alarm time, the subtitle reads
"N alarms set · not shared yet" in orange with a bell, the FAB flips to a solid orange
"Share schedule"). Deselecting an armed chip reverts the FAB to "Set alarms (N)" without
cancelling anything; deselecting every armed chip leaves it as "Clear alarms", and the tap
cancels them (`dumpsys alarm` should then show none). Setting alarms also RSVPs
"Yes, going" for each armed meeting that has an invite (see "Seeding an RSVP-able invite"):
the chip gains a small tick after its alarm time. A fired alarm rings: `AlarmRingingService`
plays a randomised sound on the alarm stream and vibrates, and its notification's
full-screen intent brings up the dark ringing screen (clock, countdown, Dismiss /
"Snooze 2 min" / "Open meeting", and a subtle "Sound: …" line naming the random sound) over
the lock screen — or a heads-up with Snooze/Dismiss while the phone is in use. Unanswered
for 3 minutes it snoozes itself once, then gives up with a "Missed alarm" notification.
Onboarding needs calendar, notifications, exact alarms and full-screen alarms granted
before the day view shows. Two optional rows are conditional, so a fresh emulator shows
neither: "Ignore battery optimization" appears only while the app isn't exempt, and the
"Background use restricted" warning only while Android restricts the app (the restricted
standby bucket, or battery usage set to Restricted — see "Robustness checks" for how to
force each). The chip states are also reviewed through the Roborazzi previews
under `app/src/test/screenshots/`. `adb shell setprop log.tag.MeetingMinderStore DEBUG`
logs every dispatched store action's type.

Day view (launch screen) → swipe left/right between days → tap meetings to select them
(chip fills, check appears) → FAB reads "Set alarms (N)" → tap it (chips gain a bell +
alarm time, the RSVP goes to "Yes, going" in the calendar) → FAB becomes
"Share schedule" → tap and confirm the share text lists busy ranges only, no titles.
Then move or add an event in Google Calendar and confirm the "changed since you shared"
notification and in-app banner arrive.

Settings (overflow → Settings): change the lead time, snooze length, auto-timeout and the
"Alarm sounds" row (All / Bundled only / System only), toggle a calendar's include switch
(the day view should stop/start showing that calendar's events), toggle the "Show declined
events" switch, and tap the "Test alarm" button (snackbar reads "Test alarm rings in 10
seconds"; it fires about 10 seconds later without touching real calendar data — the test
alarm never appears in the day view's FAB armed count, and its ringing screen has no
"Open meeting"). The permissions row reflects onboarding state and re-enters onboarding
when tapped.

Alarms: set one a minute or two out, lock the screen, and confirm the ringing activity
comes up over the lock screen with the screen woken and a sound playing. Dismiss and
snooze both need checking — snooze must reschedule, not cancel (`dumpsys alarm` shows the
same `meetingminder://alarm/{alarmId}` re-armed at the snooze time). Also worth a look:

- With the phone unlocked and in use, the alarm is a heads-up notification with Snooze and
  Dismiss instead; tapping it opens the ringing screen.
- `adb shell appops set $PKG USE_FULL_SCREEN_INTENT deny` → the heads-up fallback even when
  locked (and onboarding's "Full-screen alarms" row flips back to "Allow").
- Leave it ringing: after 3 minutes it snoozes itself; unanswered again, it stops with a
  "Missed alarm: …" notification.
- Two alarms at the same minute ring one after the other — Dismiss the first and the second
  starts.
- `adb shell cmd audio set-enable-hardening throw` (Android 17) must not break playback: every
  sound is on `USAGE_ALARM`.

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
- Change detection (after sharing a day): edit the test calendar with `adb shell content
  update`/`insert`/`delete` and expect the "Your schedule changed since you shared it"
  notification within about a minute, and the banner on the day view.
  `adb shell dumpsys jobscheduler | grep -A30 $PKG` shows the content trigger (a job with a
  `content://com.android.calendar` trigger); `adb shell cmd jobscheduler run -f $PKG <jobId>`
  forces a check. Tapping "Share update" should open the chooser with `Update:` text.
- Landscape probe: `settings put system user_rotation 1` (and back to 0).

## Robustness checks (PR-13)

- `adb shell am broadcast -a android.intent.action.PROVIDER_CHANGED -d content://com.android.calendar -p $PKG`
  fires the accelerator receiver directly (it's manifest-disabled and only enabled while a
  day is shared — check first with
  `adb shell dumpsys package $PKG | grep -B1 -A3 CalendarProviderChangedReceiver`, which
  shows the component under `enabledComponents`/`disabledComponents` once
  `CalendarProviderChangedReceiver.kt`'s `setComponentEnabledSetting` has run; `pm list
  receivers` isn't a real `pm` subcommand). `PROVIDER_CHANGED` is a protected broadcast,
  but the shell uid is one of the callers allowed to send those, so no `adb root` is
  needed: on an API 36 `user` (Play-store) image the command reports
  `Broadcast completed: result=0` and the receiver's work is enqueued.
- Midnight rollover / anchor date: `adb shell settings put global auto_time 0` then
  `adb shell cmd alarm set-time <epochMillis>` (AlarmManager's shell command; the shell
  uid holds `SET_TIME`, so it works on any build — verified on the API 36 Play-store
  image, where `adb root` is refused and `adb shell date MMDDhhmmYYYY.ss` fails with
  "Operation not permitted"). On a rootable image (`google_apis`, not `_playstore`)
  `adb root && adb shell date MMDDhhmmYYYY.ss` also works; Extended Controls has a clock
  control too. Put `auto_time` back to 1 afterwards. Either way, the viewed day and "Today"
  target should follow.
- Timezone change: `adb shell settings put global auto_time_zone 0` (not `content
  update` — that's not how settings are written), then
  `adb shell cmd alarm set-timezone Europe/London` (AlarmManager's shell command; the
  shell uid holds `SET_TIME_ZONE`, so no root needed — verified on the API 36 Play-store
  image, `persist.sys.timezone` flips at once), or on a rootable image
  `adb shell setprop persist.sys.timezone Europe/London`, or by hand via Extended
  Controls → Settings → Time / `Settings > System > Date & time`. What to look for:
  `BootReceiver` handles `TIMEZONE_CHANGED`, so `dumpsys alarm` should show every armed
  alarm re-timed to the new local wall-clock, and the day view's hour gutter and chips
  should shift with it.
- Battery optimisation / restricted standby: `adb shell dumpsys deviceidle whitelist -$PKG`
  removes the app from the allowlist so onboarding's battery row shows "Allow"; `adb shell
  am set-standby-bucket $PKG restricted` simulates the "restricted" bucket warning.
- Dark theme / large font / TalkBack: `adb shell "cmd uimode night yes"` (and `no` after),
  `adb shell settings put system font_scale 1.5`, and for TalkBack first check it's there
  at all — `adb shell pm list packages | grep -i talkback` — since plain `google_apis`
  emulator images usually don't ship it (it's a Play component; the
  `google_apis_playstore` image does, as `com.google.android.marvin.talkback`, or use a
  real phone). The service component differs between builds
  (`com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService`
  on Google builds, `com.android.talkback/…` on AOSP), so use whichever the package list
  shows in `adb shell settings put secure enabled_accessibility_services <component>`,
  and also `adb shell settings put secure accessibility_enabled 1` or the service won't
  start (verified on the API 36 Play-store image: `dumpsys accessibility` then lists
  TalkBack under "Enabled services" and its `ServiceRecord` is running). Revert with
  `settings put secure accessibility_enabled 0` and
  `settings delete secure enabled_accessibility_services` — walk the day view and
  onboarding under each.

## Gotchas

- Debug, snapshot and release builds all install side-by-side (three distinct
  applicationIds) — make sure you're looking at the install you just built.
- "Today" moves. Seed events relative to `now` rather than at fixed dates, or screenshots
  stop reproducing tomorrow.
- Exact alarms are throttled if the app is force-stopped — relaunch rather than
  `am force-stop` between runs.
- Changing selection after alarms are set deliberately reverts the FAB to "Set alarms";
  that is the reconcile state, not a bug.

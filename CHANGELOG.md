# Meeting Minder Changelog

### v1.0.10 - Unreleased

### v1.0.0 - 2026-09-15

- Long-pressing an event chip now opens a menu instead of going straight to the calendar app: "Open in calendar" first (what the long-press used to do), then "Respond Yes", "Respond No" and "Respond Maybe" for an invite the app can answer (`canRespond` in `model/Rsvp.kt`: an invite with a self-attendee row on a calendar that lets you respond, and not one you organised). The answer is written for that one occurrence only, through the same two write shapes as the automatic "Yes, going" (`CalendarRepository.respondToInstance`, which replaces `acceptInstance`), the menu ticks your current answer, a snackbar confirms, and the day reloads at once. A "Yes" on an armed selection gives the chip its "RSVP sent" tick; a "No" or "Maybe" drops that tick and leaves the selection alone, and a "No" makes the chip declined and unselectable with its alarm cancelled in the same reload by the automatic maintenance, as a decline made in Google Calendar would. Picking the answer the calendar already holds is confirmed without a write. A `SecurityException` on the write re-checks permissions like the loads do. `RespondToEvent`/`RespondToEventSideEffects`.
- The day view's app bar gained a "Refresh calendars" button (before "Today"): it asks the sync framework to sync every account's calendars now (`CalendarSyncRequester`, a `ContentResolver.requestSync` for the calendar authority; the app itself still never touches the network) and reloads the shown days straight away. `RefreshCalendars`/`RefreshCalendarsSideEffects`.
- Compact (one-line) event chips always show at least the event's start time: the time range gives way to the start time alone when the whole title wouldn't fit beside it, and a title too long even for that is ellipsized rather than pushing the start time off the line. An armed chip shows its alarm time instead.
- Chip times that fall on the hour read "9a" / "12p" (12-hour devices) instead of "9:00" / "12:00", so a time range takes less of the chip. The ringing screen's clock and TalkBack's "Alarm set for …" keep the full form, and locales whose AM and PM markers start alike (Japanese, Korean) keep "9:00".
- Stack review fixes (the outstanding `@claude review` findings on PR-4 through PR-14, in one follow-up PR):
  - **Permissions:** a calendar or notification permission denied for good before the app was relaunched no longer leaves onboarding on a dead "Allow" button: the app remembers which permissions it has ever asked for (`SettingsRepository.requestedPermissions`, in the preferences DataStore), so the system's silent auto-deny is recognised and the row flips to "Open settings". The launch-time redirect to onboarding also no longer re-creates the onboarding screen on every ungranted launch.
  - **Sharing:** a fast double tap on "Share schedule" (or "Share again"/"Re-share" while the chooser is still coming up) opens one chooser, not two: a share is "in flight" (`AppState.shareInFlight`) from the tap until the share sheet closes, and no second one starts meanwhile. The app-bar subtitle names the day when a schedule was shared on a different day ("shared Sep 13, 9:00 PM" on tomorrow's page), so a bare time can't read as that day's evening.
  - **Ringing:** dismissing or snoozing an alarm in the same instant its auto-timeout fired no longer leaves the ringing notification and foreground service behind; the sound player releases a `MediaPlayer`/`AudioTrack` whose playback fails to start (and closes the ringtone-list cursor); the wake lock is held from the moment a fire reaches the service, so back-to-back alarms can't drop it; a snooze written by the app because the service couldn't be reached, and then refused by the OS, posts the same "Missed alarm" notification the service would; the ringing screen's "Open meeting" toasts when no calendar app can open the event. `SoundPool` is renamed `AlarmSoundPool` (it collided with `android.media.SoundPool`).
  - **Automatic alarm maintenance:** an alarm cancelled because its meeting was declined or cancelled now also puts its day back to "Set alarms", so re-accepting the meeting can be re-armed from the FAB — and "Set alarms" itself no longer arms a selection whose meeting has since been declined or cancelled (the snackbar counts it: "1 set, 1 skipped (declined or cancelled)"), reading the day from every calendar with declined events included, as the maintenance does, so the two can't bounce a day between "alarms set" and "Set alarms". The Settings duration and "Alarm sounds" chips announce as radio buttons, the "changed since you shared" banner's live region sits on its text, and the `PROVIDER_CHANGED` receiver skips the package-manager write a fresh install doesn't need.
  - **CI:** `record-screenshots.yml` pins both checkouts to the PR head's SHA (a push mid-run can't split the recording and the commit), lists a renamed preview as one deleted plus one added PNG, reports `target` failures on the PR, and documents that a manual run records in main's image.
  - **Docs:** the `verify` skill's Settings walkthrough and Robustness checks now match the app (conditional onboarding rows, "Alarm sounds", the test alarm's real special-casing, a working timezone recipe, TalkBack availability on emulator images); TODO.md §4.4 records the in-progress-move rule and the day-crossing-move limit; AGENTS.md lists the PR-9 fakes and tests and the record-on-head vs verify-on-merge-ref caveat.
- Release prep (PR-14):
  - README gained a "Screenshots" section (a day-view-selecting and alarms-set pair, hosted in episode6/screenshots) and a status line reflecting the completed feature set through PR-13.
  - The `verify` skill's "Core flow to exercise" no longer hedges on "current state", and gained Settings-screen steps and a "Robustness checks (PR-13)" section; three of its adb recipes that didn't work (`pm list receivers`, an `adb emu geo` clock control, a `content update` settings write) were replaced with working ones, and the Settings walkthrough's button label and timing ("Test alarm", rings after 10 seconds) corrected.
  - Launcher icon, `project-icon.svg` and `THIRD_PARTY_LICENSES.md` were reviewed and needed no change; the stale "Placeholder launcher art" comment was dropped. Cutting the first `release/v1.0.0` branch happens from `main` once the whole PR-1..PR-14 stack has merged (`RELEASE_CHECKLIST.md`), not in this PR.
- Robustness (PR-13):
  - **Change monitoring accelerator:** the calendar provider's `PROVIDER_CHANGED` broadcast now triggers a change check a few seconds after a sync (`monitor/CalendarProviderChangedReceiver`, a new `calendar-change-broadcast` unique work). The receiver is enabled only while some day is shared.
  - **Battery optimisation onboarding row:** the "Ignore battery optimization" row is live. It is optional and shown only while the app isn't exempt (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` added to the manifest and `expected-permissions.txt`).
  - **Background-restriction warning:** a "Background use restricted" warning row appears while Android restricts the app (restricted standby bucket, or battery usage set to Restricted). Settings → Permissions says so too.
  - **Phone-maker card:** Samsung, Xiaomi, Huawei and OnePlus phones get an onboarding card that opens dontkillmyapp.com in the browser.
  - **Midnight rollover:** a day view left open past midnight now moves "today" (`SetAnchorDate`, `AnchorDateSideEffects`) and keeps showing the page the user was on.
  - **Automatic alarm maintenance:** armed alarms now follow their meetings. On a calendar change while the app is open, and after boot or a clock/timezone change, `alarm/AlarmMaintainer` (TODO.md §4.4's `MaintainAlarms`) re-times alarms whose meetings moved and cancels those since declined; vanished events keep their alarms.
  - **Timezone changes:** the graph's `Clock` (`di/DeviceClock`) re-reads the device zone on every call. `Clock.systemDefaultZone()` left every long-lived holder in the old zone.
  - **Dark theme pass:** the colour scheme now sets every neutral role (outline, secondary container, inverse and surface-container colours), so switches, outlined buttons, menus and snackbars no longer fall back to Material's baseline purple. New dark previews for the day screen, event chips, onboarding, Settings and licences.
  - **Large font pass:** the day view's app bar title and subtitle ellipsise instead of clipping. New 1.5× previews for the day screen and onboarding.
  - **TalkBack pass:** chips read "title, start to end, place" with their selected/alarm/declined state and labelled select and "open in calendar" actions. Hour gutter and all-day labels are cleared from semantics. App bar, onboarding and Settings sections are headings, Settings' switch rows toggle as a whole, and the change banner is a polite live region.
  - **New tests:** `AnchorDateSideEffectsTest`, `DeviceClockTest`, `DayScreenRolloverTest`, `DayTimelineSemanticsTest`, `AlarmMaintainerTest`, `CalendarProviderChangedReceiverTest`, `SleepyManufacturerTest`, `PermissionsStatusTest`, and new `PermissionCheckerTest`/`OnboardingViewModelTest`/`AppStoreReducerTest` cases.
- Settings screen (PR-12): a new Settings screen, reachable from the day view's overflow
  menu, makes lead time, snooze length, auto-timeout and the alarm sound pool (the "Alarm sounds" row: "all",
  "bundled only", "system only") editable for the first time — `SettingsRepository` gained
  setters for all four alongside the existing lead time one — plus a "Test alarm" button
  that arms a real exact alarm ten seconds out (`TestAlarm`/`TestAlarmSideEffects`,
  independent of any selection, so the whole ringing path can be checked end to end), a
  Settings → Calendars list with a per-calendar include switch that overrides the
  provider's `VISIBLE` flag (and a "not syncing" hint for a calendar whose sync is off),
  and a "show declined events" toggle (on by default, per TODO.md §4.1). The new
  `effectiveCalendarFilter`/`excludeDeclined` (`data/calendar/EffectiveCalendars.kt`) apply
  both to the day view's loaded window (`LoadDayEventsSideEffects`) and to
  `monitor.ChangeMonitor`'s fresh read, so a shared day's baseline and its background
  re-checks always agree on which calendars and which declined events are in play — the
  seam TODO.md left open when PR-11 landed. Toggling a calendar or the declined switch
  dispatches the existing `CalendarContentChanged` action so the change is reflected
  immediately rather than waiting for the next provider notification. Settings also links
  back into Permissions (Onboarding) and the licence notices. No new permissions or
  dependencies. New tests: `EffectiveCalendarsTest`, `TestAlarmSideEffectsTest`,
  `SettingsViewModelTest`, new `DataStoreSettingsRepositoryTest` cases, and new cases in
  `LoadDayEventsSideEffectsTest`/`ChangeMonitorTest` for the calendar filter and declined
  wiring. `ui/util/ComingSoonScreen.kt` is gone now that nothing routes to it.
- Settings screen fixes (PR-12 review): selected `FilterChip`s (lead time, snooze,
  auto-timeout, alarm sounds) now render in episode6 orange (`primaryContainer`) instead of
  M3's default lavender `secondaryContainer`, which the theme never defined; those chip
  rows wrap in a `FlowRow` instead of scrolling horizontally, so no chip is clipped at the
  screen edge at any font scale; the Permissions row now shows a status subtitle ("All
  granted" / "N not granted") derived from `AppState.permissions`, closing the "permissions
  status re-entry to onboarding" ask from TODO.md §5 PR-12. A cold-process "Share update"
  (`ShareDaySideEffects.readDay`) and a cold-process day load with a calendar override
  stored (`LoadDayEventsSideEffects`, whose `state.calendars` can still be empty) both now
  apply the same `effectiveCalendarFilter`/`excludeDeclined` `monitor.ChangeMonitor` uses,
  reading the calendar list straight from the provider when `state.calendars` isn't
  populated yet — closing the rest of the PR-11 seam and fixing a cold-process load that
  could come up with zero events. The Settings "Test alarm" row is excluded from
  `DayPlan.armedKeys` (`data/db/DayPlanMapping.buildDayPlans`) so it no longer shows the day
  view's "Clear alarms" FAB with nothing selected, and the ringing screen hides "Open
  meeting" for it (`TEST_ALARM_EVENT_ID`, moved to `model/` so both can reference it)
  instead of opening the calendar app at a non-existent event. `ui/settings/` added to the
  AGENTS.md and TODO.md §3.3 package maps.
- Change detection + notification (PR-11): once a day is shared, Meeting Minder watches it
  until its midnight and says when it changes. A pure `monitor/ChangeDetector` diffs the
  day's `change_snapshot` baseline against a fresh read of the whole day using the TODO.md
  §4.3 table: **New** for any meeting added since the share (selected or not, not yet
  started), **Moved** / **Cancelled** / **Declined** only for events that were selected at
  share time (a move into the past, a cancellation after the start, and anything already
  over are ignored, as are title/colour/attendee edits, identical sync rewrites and all-day
  events). `monitor/ChangeMonitor` runs it for every shared day (today and later), drops the
  baselines of days that have ended, records what changed in the new
  `change_snapshot.changes_json` column (database version 5; baseline rows also store
  `allDay` now), promotes waiting RSVPs to `SYNCED`, and posts, updates or cancels the
  `schedule_updates` notification (render 6: one per day, `InboxStyle` with one time-only
  line per change, titled with the weekday when it isn't today, alerting only when a change
  is new and the notification isn't already showing, with **Review** → `meetingminder://day/{date}` and **Share update** →
  `meetingminder://share/{date}` straight into `MainActivity`). It runs in the background
  from `CalendarChangeWorker` on WorkManager (a content-URI trigger on the calendar provider
  that re-arms itself after every run, a 30-minute periodic safety net, and a one-time work at
  the last shared day's midnight that disarms everything), and in the foreground on every
  `CalendarContentChanged`. A share or "Share again" takes a fresh baseline, cancels the
  notification and arms monitoring; "Mark as not shared" disarms it. `Navigation.kt` handles
  the deep links (`ui/navigation/DeepLinks`, queued by `MainActivity` from its launch intent
  and `onNewIntent`; a link replayed from Recents is ignored): the pager jumps to the day and "Share
  update" opens the chooser; the missed-alarm notification now opens its day the same way.
  The day view shows a "changed since you shared" banner (`ScheduleChangeBanner`: "2 changes
  since you shared", the change lines and **Re-share**) from the new
  `AppState.scheduleChanges`, and also when the selection no longer matches what was shared.
  A re-share of a day that has changed sends the `Update:` text. `ShareDay` now reads the
  selection from Room and the day from the provider when the store hasn't loaded them, so a
  share from the notification into a cold process shares the right thing. New dependency:
  WorkManager (`work-runtime-ktx`, plus `work-testing` for tests); its merged
  `ACCESS_NETWORK_STATE` permission is removed from the manifest, so the permission list is
  unchanged. New tests: `ChangeDetectorTest` (every row of the §4.3 table),
  `ChangeMonitorTest`, `WorkManagerChangeWorkSchedulerTest` and `CalendarChangeWorkerTest`
  (WorkManager's test driver), `ScheduleChangeNotificationsTest`, `DeepLinksTest`,
  `ChangeDetectionSideEffectsTest`, and new cases in `ShareDaySideEffectsTest`,
  `ChangeSnapshotDaoTest`, `ChangeSnapshotMappingTest`, `DayViewModelTest`,
  `NavigationViewModelTest` and `AppStoreReducerTest`; Roborazzi previews of the banner on the
  day screen and in dark theme.
- Alarm ringing experience (PR-10): a fired alarm now rings instead of posting a plain
  notification. `AlarmReceiver` takes a wake lock and immediately starts the new
  `AlarmRingingService`, a `mediaPlayback` foreground service that marks the row `FIRED`,
  posts the ringing notification (silent `alarms` channel, `CATEGORY_ALARM`, public, with a
  full-screen intent to the new `AlarmActivity` and Snooze/Dismiss actions straight back to
  the service; swiping it away snoozes), plays the randomised obnoxious alert and vibrates.
  `AlarmActivity` (`showWhenLocked`, `turnScreenOn`, keeps the screen on, `singleInstance`,
  out of recents, back disabled) hosts the Compose `AlarmRingingScreen` (render 5: countdown,
  big clock, pulsing alarm, title/time/place, Dismiss, "Snooze 2 min", "Open meeting" — which
  asks the keyguard to go away, dismisses and opens the event in the calendar — and a subtle
  "Sound: …" line) from the store's new `AppState.ringing` (`SetRinging`, published by the
  service); its `SnoozeAlarm(alarmId)`/`DismissAlarm(alarmId)` reach the service through
  `AlarmRingingSideEffects`. The sound (`AlarmSoundPlayer` over the pure, seeded
  `AlarmSoundRecipe`): 60% a device alarm ringtone, 30% one of eight bundled AOSP alarm OGGs,
  10% a synthesised siren (`renderSiren`), each at a random speed (0.85–1.35) and pitch
  (0.8–1.5), re-rolled every ~10 s, ramped from 25% to 100% over 15 s with a `VolumeShaper`,
  never opening with a sound one of the last five alarms opened with (`RecentAlarmSounds`),
  on `USAGE_ALARM` with transient audio focus and the alarm stream raised to at least half;
  plus a random 4–8-segment vibration waveform. Snooze (default 2 min) re-arms the same alarm
  with `setAlarmClock` as a `SNOOZED` row, which now counts as armed everywhere (boot re-arm,
  `DayPlan.armedKeys`, and the "Set alarms" reconcile, which keeps a still-selected snoozed
  alarm instead of reading it as moved into the past). Unanswered for the auto-timeout
  (default 3 min) an alarm snoozes itself once, then gives up with a "Missed alarm"
  notification; alarms that fire while one rings wait their turn. The service's rules —
  answering every `startForegroundService` with `startForeground` in time (a placeholder
  when the row is slow to load or there is nothing to ring), the queue, awaiting each row
  write before leaving the foreground, the timeout — live in the Android-free
  `AlarmRingingSession`. Onboarding's "Full-screen alarms" row is live and required
  (`PermissionState.fullScreenIntentGranted`, `canUseFullScreenIntent()` on 34+,
  `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`). `scheduled_alarm` gains `location` (for the
  ringing screen) and `timed_out` (database version 4); `Settings` gains `snoozeLength`,
  `autoTimeout` and `soundPool` (defaults only until PR-12). Permissions added (manifest +
  `expected-permissions.txt`): `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `VIBRATE`, `WAKE_LOCK`. No new dependency; the bundled
  sounds are attributed in `THIRD_PARTY_LICENSES.md`. New tests: `AlarmRingingSessionTest`,
  `AlarmRingerTest`, `AlarmSoundRecipeTest`, `AlarmSirenTest`, `RecentAlarmSoundsTest`,
  `AlarmNotificationsTest`, `AlarmReceiverTest`, `AlarmRingingSideEffectsTest`,
  `AlarmRingingViewModelTest`, new cases in `AlarmReconcilerTest`, `ScheduledAlarmDaoTest`,
  `AlarmReschedulerTest`, `DayPlanMappingTest`, `AppStoreReducerTest`, `PermissionCheckerTest`,
  `OnboardingViewModelTest`, `NavigationViewModelTest` and `DataStoreSettingsRepositoryTest`,
  Roborazzi previews of the ringing screen (render 5, started, 1.5× font), and the device test
  `AlarmRingingDeviceTest` (an alarm 10 s out wakes the screen into the ringing activity;
  Dismiss closes it). The other device tests pin the full-screen-intent grant.
- Share schedule (PR-9): the "Share schedule" FAB now works. A pure `share/ScheduleTextFormatter`
  merges the day's selected events into busy ranges and builds the TODO.md §4.2 message
  ("Mon Sep 14 — I'm in meetings: • 9:00 – 9:30 AM … Free the rest of the day.", 12-hour
  clock with AM/PM shown only where it changes, "No meetings today." when nothing is
  selected, or `Update:` + the ranges alone for PR-11's future re-share). Tapping the FAB
  (or the new overflow items "Share again"/"Mark as not shared", shown once a day has been
  shared) dispatches `ShareDay(date)`; `ShareDaySideEffects` formats the text, records
  `day_plan.shared_at`/`shared_snapshot` (the merged ranges, for PR-11's "Update:" text)
  and a `change_snapshot` row — every event on the day, selected or not, meeting or not —
  as the baseline PR-11's differ will read (new table, database version 3). The text
  itself is handed to `Navigation.kt` through a new one-shot `AppState.pendingShare`
  (mirrors `transientMessage`), which is what actually calls
  `share/ShareLauncher.kt`'s `Context.shareSchedule` (`ShareCompat`'s chooser): launching
  an Activity belongs in the UI layer, never a side effect or receiver. The app bar
  subtitle reads "shared 8:12 AM" once a day has been shared (`TimelineTimeFormat
  .timeWithPeriod`). New tests: `ScheduleTextFormatterTest` (merging, AM/PM elision, empty
  day, midnight-spanning, the update form), `ShareDaySideEffectsTest`,
  `ChangeSnapshotMappingTest`/`DayPlanMappingTest` (JSON round-trips), `ChangeSnapshotDaoTest`,
  new `DayPlanDaoTest`/`AppStoreReducerTest`/`DayViewModelTest` cases. No new permission or
  dependency. Fixed: the busy-range text and `shared_snapshot` now use each selected
  event's freshly loaded begin/end (falling back to the stored selection when the
  provider no longer has it), the same re-timing rule `reconcileAlarms` uses — previously
  they used the stale stored times even when a meeting had moved since it was selected,
  while `change_snapshot`'s baseline (already built from the fresh read) recorded the new
  time, so a share after a move sent the wrong busy range and PR-11's differ could never
  detect it.
- RSVP on set-alarms (PR-8b): "Set alarms" now also tells the calendar "Yes, going" for
  every meeting it just armed (TODO.md §4.6), so Google Calendar renders it accepted and
  the organizer gets a response through Google's own sync — one occurrence at a time,
  never a series, never a decline, and never reversed (deselecting only cancels the
  alarm). The pure `rsvpDecision(event)` (`model/Rsvp.kt`) is the §4.6 skip table:
  self-only attendee data, a solo block, being the organizer, already accepted, declined by
  you (never un-responded on your behalf) or cancelled by the organizer are
  `NOT_APPLICABLE` (silent); a calendar below `CAL_ACCESS_RESPOND` or an invite sent to an
  alias (attendees, but no row matching `OWNER_ACCOUNT`) are `UNRESPONDABLE`; everything
  else is `PENDING` and gets written. `CalendarRepository.acceptInstance(event)` does the
  write, addressed by the occurrence's own `eventId`: a recurring occurrence inserts an
  `exception/{eventId}` with `ORIGINAL_INSTANCE_TIME = begin` + `SELF_ATTENDEE_STATUS =
  ACCEPTED` (the built-in calendar app's "This event" answer), anything else updates our
  own `attendees/{selfAttendeeId}` row. The alarm reconcile records the decision on the
  `selected_event` row (`rsvp_state`, now the `RsvpState` enum; a row already answered on
  an earlier tap keeps its answer and its tick when re-armed) and fans out one
  `RsvpAccept(date, key)` per newly armed `PENDING` event; the new
  `RsvpAcceptSideEffects` writes on IO, reports `RsvpAccepted(date, key, result)`, stores
  `ACCEPTED_LOCALLY` + `rsvp_event_id` (or `FAILED`), and promotes `ACCEPTED_LOCALLY` to
  `SYNCED` when, on a reload of the day, the new batched
  `CalendarRepository.syncedEventIds` reports the written event with `DIRTY = 0` (queried
  on `Events` directly — the provider's `Instances` view does not expose `DIRTY`, as the
  device tests showed). Alarms never wait on the write. Chips show a small tick after the alarm time once the RSVP went through and a
  subtle "couldn't RSVP" hint when it couldn't (`TimelineEvent.rsvp`, `ChipRsvp`), both
  read out in the chip's state description. No schema change: the columns existed since
  PR-7. New tests: `RsvpDecisionTest` (one per table row, plus the solo-vs-alias
  distinction and table order), `ContentResolverCalendarRepositoryRsvpTest` (Robolectric,
  both write shapes, the exception-event addressing, the failure path, `syncedEventIds`),
  `RsvpAcceptSideEffectsTest`, new cases in `ScheduleAlarmsSideEffectsTest`,
  `DayPlanDaoTest`, `DayPlanMappingTest`, `DayViewModelTest` and `TimelineEventTest`;
  `FakeCalendarProvider` now accepts and records the two writes. The `verify` skill gains
  the emulator seeding recipe for an RSVP-able invite.
- Alarm scheduling core (PR-8): "Set alarms (N)" now does it. A new `scheduled_alarm` Room
  table (database version 2) is the source of truth for what is armed with `AlarmManager`;
  `alarm/AndroidAlarmScheduler` arms each row with `setAlarmClock` (Doze-exempt,
  alarm-clock class) through one `PendingIntent` per alarm identified by its
  `meetingminder://alarm/{alarmId}` data + request code. `ScheduleAlarmsSideEffects` handles
  the new `SetAlarms(date)` action with the pure `reconcileAlarms` (`alarm/AlarmReconciler.kt`):
  arms newly selected events at begin − lead time, cancels rows for deselected ones, re-times
  a moved event in place, skips (and counts in the snackbar, "2 alarms skipped, already
  started") any whose alarm time has passed, then records `day_plan.alarms_set_at` so the FAB
  flips to "Share schedule" and the subtitle reads "3 alarms set · not shared yet". Any later
  change of selection clears `alarms_set_at` (in `DayPlanDao.toggleSelectedEvent`'s
  transaction) so the FAB reverts to "Set alarms" until the next reconcile, per the §2
  interaction rules. The lead time (default 5 min) comes from the new DataStore-backed
  `data/settings/SettingsRepository`. `alarm/BootReceiver` re-arms every `SCHEDULED` row from
  Room on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` and the
  exact-alarm permission-state broadcast, under `goAsync()` so the broadcast stays open until
  every alarm is re-set; `alarm/AlarmReceiver` marks a fired row `FIRED` and, until PR-10's
  ringing service, posts a plain high-priority notification on the new `alarms` channel.
  Permissions added (manifest + `expected-permissions.txt`): `USE_EXACT_ALARM`,
  `SCHEDULE_EXACT_ALARM` (maxSdkVersion 32), `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`.
  Onboarding's "Notifications" and "Alarms & reminders" rows are live (`PermissionState`
  gains `notificationsGranted`/`exactAlarmsGranted`; both are required, so launch routes to
  Onboarding unless all three required grants are held, and Continue waits for them).
  `UiMessage` can now carry a plurals quantity. New tests: `AlarmReconcilerTest` (alarm time
  math + every reconcile case), `AndroidAlarmSchedulerTest` (Robolectric `ShadowAlarmManager`:
  `setAlarmClock` used, PendingIntent identity, replace/cancel, denied path, manifest
  receivers), `AlarmReschedulerTest`, `FiredAlarmHandlerTest`, `ScheduledAlarmDaoTest`,
  `ScheduleAlarmsSideEffectsTest`, `DataStoreSettingsRepositoryTest`, plus new cases in
  `DayPlanDaoTest`, `PermissionCheckerTest`, `DayViewModelTest`, `OnboardingViewModelTest` and
  `NavigationViewModelTest`. New Roborazzi previews: `DayScreenAlarmsSetPreview` (render 3)
  and `OnboardingScreenPartlyGrantedPreview`.
- Fix (PR-8 review): deselecting every armed event no longer strands its alarms. The toggle
  clears `alarms_set_at` and an empty selection hid the FAB, so the still-`SCHEDULED` rows
  could never be cancelled (and rang, and were re-armed after boot). `DayPlan` now carries
  `armedKeys` (the day's `SCHEDULED` `scheduled_alarm` rows, streamed by
  `ObserveDayPlansSideEffects` via the new `ScheduledAlarmDao.observeScheduled()`), the FAB
  stays as "Clear alarms" (`FabState.SetAlarms(0)`) while any are armed, and a reconcile
  that only cancels leaves `alarms_set_at` null (snackbar "2 alarms cleared") so the day goes
  back to nothing-picked rather than "0 alarms set". A `setAlarmClock` call the OS refuses
  likewise no longer records `alarms_set_at`: the FAB keeps reading "Set alarms (N)" and the
  next tap inserts a fresh row for the `CANCELLED` one. The "Share schedule" FAB is
  primary-filled and the alarms-set subtitle is an orange bell accent, as in render 3.
  TODO.md §3.2/§4.4 gain NBs recording that `BootCompleted`/`TimeChanged` are not store
  actions (`BootReceiver` calls `AlarmRescheduler` directly under `goAsync()`), the
  `RescheduleReceiver` → `BootReceiver` name, and `DayPlan.armedKeys`. New tests:
  `ScheduleAlarmsSideEffectsTest` (refused arm leaves `alarms_set_at` null and is retried
  by the next tap; deselect-all cancels and clears), `DayViewModelTest` (clear-alarms FAB
  state and tap), `DayPlanMappingTest`/`ObserveDayPlansSideEffectsTest`/`ScheduledAlarmDaoTest`
  (`armedKeys`/`observeScheduled`), and a `DayScreenClearAlarmsPreview` screenshot.
- Fix: `ToggleEventSideEffects`' read-then-write across two DAO calls could let a fast
  double tap on the same chip leave it selected instead of unselected, since `flatMapMerge`
  runs concurrent toggles and both could read "not selected" before either wrote.
  `DayPlanDao.toggleSelectedEvent` now wraps the delete-or-insert in one `@Transaction`, so
  a repeated toggle of the same key is atomic. `DayFab`'s `AnimatedContent` also now keys
  on the `FabState` subclass so a `SetAlarms(n)` count change updates the label in place
  instead of crossfading the whole FAB, and keeps rendering its last visible content while
  `AnimatedVisibility` animates it out (rather than racing an empty `Hidden` frame). New
  tests: a `ToggleEventSideEffectsTest` case for two back-to-back toggles of the same key,
  and `SelectionPersistenceStoreTest`, a `runStoreTest` pinning `ObserveDayPlansSideEffects`
  + `ToggleEventSideEffects` end to end over a `FakeDayPlanDao`.
- Selection persistence (PR-7): tapping a chip now sticks. A new Room database
  (`MeetingMinderDatabase`, schemas exported to `app/schemas/`) adds the `day_plan` and
  `selected_event` tables (TODO.md §3.4); `ObserveDayPlansSideEffects` streams both into
  the new `AppState.dayPlans: Map<LocalDate, DayPlan>`, and `ToggleEventSideEffects`
  writes `ToggleEvent(date, key)` (inserting a denormalised copy of the tapped
  `CalendarEvent`'s title/times, or deleting the existing row) — Room is the source of
  truth, so a toggle round-trips through the DAO flows back into the UI rather than being
  applied optimistically. Selection is per `(date, event_id, instance_time)`, not just the
  `EventKey`, so an event spanning midnight can be selected independently on each of the
  two day pages it appears on; `DayViewModel.onEventToggle` and the new `DayPager`/
  `DayScreen` `onEventClick` signature carry the tapped page's date accordingly. The day
  view's FAB now appears (`ExtendedFloatingActionButton`, animated via `AnimatedContent`)
  reading "Set alarms (N)" once ≥1 event is selected — tapping it is a placeholder
  snackbar until PR-8 — and the app-bar subtitle grows a "· N selected" suffix while it
  does; both flip to "Share schedule" once `DayPlan.alarmsSetAt` is set, which nothing yet
  writes. New tests: `DayPlanMappingTest` (pure `buildDayPlans`/`toSelectedEventEntity`),
  a Robolectric `DayPlanDaoTest` against a real in-memory database, the two new side
  effects (`FakeDayPlanDao`), the reducer's `SetDayPlans` case, and `DayViewModel` cases
  for `onEventToggle`, `onFabClick`, `toFabState` and the chip selection/alarm-time
  mapping. New Roborazzi preview: `DayScreenSelectingPreview`.
- Fix: the day view could launch showing stale (or no) events until the store next changed.
  `createAppStore` now hands each new collector the current state with `onSubscription`
  instead of redux-store-flow's `SubscriberAwareStoreFlow` `onStart` hand-over, which runs
  before the collector is registered with the shared flow — so a load that finished while
  the UI was still busy with its first frame (the `combine` in `DayViewModel` yields after
  every value) was emitted to nobody. Found by the device test relaunching in a warm
  process on a slow emulator; pinned by a new `AppStoreTest` case. The device test now
  prints the full semantics tree (not just the roots) when a wait times out.
- Day pager wired to the store (PR-6): the day view now shows your real calendar. A
  `HorizontalPager` (anchor page = today) swipes between days; each settled page dispatches
  `LoadDay`, and the new `LoadDayEvents` side effect (`transformLatest`) loads that day and
  the day either side into `AppState.eventsByDay`, which the reducer keeps to the settled
  date ± 1. `LoadCalendars` fills `AppState.calendars`. All pages share one vertical scroll
  position, which opens an hour before today's first meeting (else 8 AM). The Today action
  scrolls the pager back, the app-bar subtitle counts the settled day's meetings
  ("3 meetings", per `isMeeting`), today's page draws the now-line from a once-a-minute
  clock, and long-pressing a chip opens that occurrence in the calendar app (falling back to
  the calendar at that time, with a snackbar if no app handles either; a manifest
  `<queries>` entry makes the calendar app visible, no new permission). A foreground
  `ContentObserver` (`CalendarObserver` side effect over the new `CalendarChangeSource`) is
  registered only while the store has subscribers and calendar access is granted, and
  turns debounced provider changes into `CalendarContentChanged` reloads; the day screen's
  snackbar collection is now lifecycle-aware so a backgrounded app releases the observer.
  New tests: the three side effects, the window-pruning reducer, `DayViewModel`'s
  mapping (meeting count, now-line, midnight-ending events, initial scroll), page/date
  maths, Robolectric tests for the observer, the open-in-calendar intents (pure builders in
  `data/calendar/CalendarIntents`) and their launcher (`ui/navigation/OpenInCalendar`, so
  `data/` never imports `ui/`), and a device
  test that inserts an event into the real provider before launch and while the day is on
  screen and waits for its chip. New Roborazzi previews: `DayScreenBusyPreview` and
  `DayScreenLoadingPreview`.

- Screenshot references are now recorded in CI: applying the `record-screenshots` label to a PR
  runs the new `record-screenshots.yml`, which records the Roborazzi reference PNGs inside the
  CI image and opens a PR with any changes against that PR's branch, to be reviewed image by
  image and merged into it. Nobody records locally any more (running the CI image under local
  Docker was heavy enough to crash a laptop).
- Review fixes on PR-5: at 1.5× font scale the armed chip's time range now ellipsizes so the
  bell and alarm time always survive a half-width column (the Dentist chip showed a cut-off
  "11"), and the hour-gutter and all-day labels shrink to fit (then ellipsize) instead of
  being truncated to "all-da" or pushed past the screen edge; the large-font reference PNG
  was re-recorded inside the CI image. Also from review: one shared
  `DayViewDefaults.chipHeight` for placement and content choice, named constants for the
  last literal paddings, `LocalResources` instead of the deprecated `LocalConfiguration`,
  zero-padded 24-hour chip times ("09:30") to match the gutter, a documented
  `timedEvents` contract, and new `TimelineTimeFormatTest` / `ChipContentLayoutTest` plus a
  three-column expansion case in `LayoutDayTest`.
- Day timeline UI, static (PR-5): the day view now renders a Google-Calendar-style timeline
  instead of the empty placeholder — an all-day row, a scrolled hour gutter and grid, event
  chips laid out by the new custom `DayEventsLayout`, and the red now-line. Overlapping events
  sit side by side via the pure `layoutDay()` packing (sort → cluster connected overlaps →
  first-fit columns → expand into free columns, with short events packed by their 24dp chip
  height), unit-tested alongside the midnight clamping. `EventChip` covers every visual state
  from renders 2/3: unselected (12% fill + calendar-colour border), tentative (40% fill),
  selected (solid + check), armed (bell + alarm time), declined/cancelled (dashed,
  strikethrough, not selectable) and past (60% alpha), with checkbox semantics and haptic
  ticks. Dimensions live in `DayViewDefaults`; times follow the device's 12/24-hour setting.
  No data is wired yet — the app shows an empty timeline until PR-6 loads events, and the
  new Roborazzi previews (busy, selecting, alarms set, dark, 1.5× font, overlaps, empty, chip
  states) are recorded inside the CI image. The launch smoke test now asserts the timeline's
  test tag instead of the removed empty-day text.
- Review fixes on PR-4: the start-destination decision and the `ON_RESUME` permission
  refresh now go through a new `ui/navigation/NavigationViewModel` instead of
  `Navigation.kt` reaching `context.appGraph.appStore` directly, per AGENTS.md
  ("Composables do not see the store"). Revoking calendar access and relaunching from
  recents (which restores the saved back stack rather than re-evaluating the launch
  destination) now redirects to Onboarding via a `LaunchedEffect` on `calendarGranted`.
  The Onboarding "Allow" flow now snapshots `shouldShowRequestPermissionRationale` before
  `launch()`, so dismissing the very first permission dialog with Back (no denial
  recorded, rationale already `false`) no longer flips the row to "Open settings" after
  zero real denials.
- Calendar permission + minimal onboarding (PR-4): `permissions/PermissionState`,
  `PermissionChecker` (checks `READ_CALENDAR` + `WRITE_CALENDAR` together, since they
  share the `CALENDAR` group and one runtime dialog grants both) and `PermissionRequester`
  (the "open app settings" intent). `AppState.permissions` is seeded synchronously in
  `AppGraph` so launch routing never flashes the wrong screen, and refreshed afterwards by
  a new `PermissionsMaybeChanged` action dispatched on every `ON_RESUME`. The Onboarding
  screen (render 1) now has a live calendar row — Allow, Granted, or "Open settings" once
  Android stops showing the dialog after two denials — with the alarms/notifications/
  full-screen/battery rows stubbed "coming soon" until PR-8/8b/10/13. Launch now routes to
  Onboarding first if calendar access isn't granted, else straight to the day view; the
  overflow's existing "Permissions" entry reaches the same screen with a back button.
- Calendar repository (PR-3): the `model/` types (`EventKey`, `CalendarEvent` with the
  canonical `isMeeting` rule, `CalendarInfo`), the `CalendarRepository` interface and its
  `ContentResolverCalendarRepository` over the Calendar Provider — every calendar on every
  account, a day's events from `Instances` with the ±1-day window re-filtered on
  `START_DAY`/`END_DAY` (so an all-day event never leaks into the evening before in a
  negative-offset zone), one batched `Attendees` query per day for human counts, the
  self-attendee row and organizer detection, and `EventKey` normalisation that maps an
  exception event back to the occurrence it replaced. Hidden calendars are skipped by
  default (`CalendarFilter.Visible`) with `CalendarFilter.Only(ids)` as the seam for the
  Settings override. `READ_CALENDAR` and `WRITE_CALENDAR` are now declared (and pinned in
  `expected-permissions.txt`); nothing requests them yet — that is PR-4.
- Internal: Robolectric tests drive the repository against a `FakeCalendarProvider`
  (in-memory SQLite behind `com.android.calendar`, so real projections and selections are
  honoured) covering timed, recurring, moved (`ORIGINAL_ID`), all-day-near-midnight,
  ends-at-midnight, cancelled/deleted, declined, hidden-calendar, duplicate-owner-row and
  attendee-chunk-boundary cases; plain unit tests
  cover `isMeeting` and `EventKey`; a `FakeCalendarRepository` is ready for store tests; and
  one instrumented test inserts a `LOCAL` calendar + event into the real provider on the
  emulator and reads it back.
- DI + store + navigation shell (PR-2): the Metro `AppGraph` (app `CoroutineScope`,
  settings DataStore, the app-wide redux-store-flow `AppStore`) created by the new
  `MeetingMinderApp` and reachable via `Context.appGraph`; `AppMetroViewModelFactory`;
  `AppState` with its `UpdateStateAction`/`AsyncAction` split and reducer; the
  `@IntoSet` side-effect contribution pattern with a no-op action-log side effect;
  type-safe `Routes` and `Navigation.kt`. The app now launches to an empty day view
  (date, Today action, overflow menu with Permissions, Settings, Check for updates and
  the third-party license notices screen); Permissions and Settings are "coming soon"
  placeholders. "Check for updates" shows a snackbar instead of crashing when no app can
  open the page. Bold-title typography joins the orange theme.
- Internal: Roborazzi screenshot tests are wired up via the plugin's generated
  preview tests (`generateComposePreviewRobolectricTests`), so every non-private
  `@Preview` — today the empty day, licenses and "coming soon" screens — gets a
  Robolectric native-graphics screenshot with no hand-written test. Reference PNGs live
  under `app/src/test/screenshots/` and CI now runs `verifyRoborazziDebug`. Unit tests cover the reducer, the store
  wiring, the side-effect test helper, `DayViewModel` and the markdown renderer.
- Internal: the `build-installers.yml` gradle job now runs inside a prebuilt CI image
  (`.github/docker/ci.Dockerfile`, resolved or built by the reusable `ci-image.yml`
  workflow and tagged by content hash on GHCR), the same scheme collins uses. The
  Dockerfile is the single canonical list of build dependencies — JDK, Android SDK
  components, the pinned gradle distribution — so a toolchain bump that forgets it fails
  the PR instead of quietly downloading on the runner. The emulator job stays on the bare
  runner.
- Repo scaffold from the episode6 app-repo template: gradle wrapper (9.5.1),
  `settings.gradle.kts` + root `build.gradle.kts` with the derived-versionCode /
  snapshot-identity scheme, `self.versions.toml`, a version catalog, the `build-logic`
  included build with the `release-verification` convention plugin, the `:app` module
  (signing configs, build types, snapshot-aware resValues, licence-notice generation),
  the committed `debug.keystore`, an adaptive launcher icon with yellow debug / dark
  charcoal snapshot backgrounds, the episode6-orange Material 3 theme, all five CI
  workflows, the release scripts and the `.agents/` skills. `MainActivity` launches to
  a placeholder screen showing the app name.
- Internal: hardened the scaffold after review — the licence-notice unit test now
  compares the generated constant against the whole document instead of spot-checking
  it, the release signing config fails fast when only some of its keystore env vars are
  set, and `scripts/verify-docs-updated.sh` prints real newlines instead of literal
  `\n`.
- Internal: the pre-compose window theme now uses a platform `android:Theme.Material`
  parent instead of `Theme.Material3` from `com.google.android.material`, dropping that
  library and the View-system stack behind it (appcompat, fragment, recyclerview,
  constraintlayout, …) from the release dependency set.

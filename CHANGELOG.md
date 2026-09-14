# Meeting Minder Changelog

### v1.0.0 - Unreleased

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
  dependency.
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

# Meeting Minder Changelog

### v1.0.0 - Unreleased

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

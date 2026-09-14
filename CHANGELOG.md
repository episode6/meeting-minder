# Meeting Minder Changelog

### v1.0.0 - Unreleased

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
  ends-at-midnight, cancelled/deleted, declined and hidden-calendar cases; plain unit tests
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

# AGENTS.md — Meeting Minder

Guidance for AI assistants and contributors working in this codebase. Read this before making structural changes.

## Product summary

Meeting Minder is an android-only episode6 app for one job: **every morning, look at today, decide which meetings you're actually attending, get loud alarms for those, and send your partner a "here's when I'm busy" text.** During the day it watches the calendar and nags you to re-send the schedule when meetings appear or move.

- Reads **every calendar on every account** through the Android Calendar Provider. The only thing it ever writes back is your **RSVP**: setting alarms marks each chosen meeting "Yes, going".
- Main screen: a Google-Calendar-style **single-day itinerary** (hour grid, proportional event heights, overlapping events side by side) with horizontal swiping between days. Tapping an event selects it ("I'm going to this").
- Alarms are **in-app, exact, alarm-clock class**: a full-screen activity that wakes the screen and plays a randomized, obnoxious alert.
- Sharing opens the system share sheet with generated text listing only **busy time ranges** — no titles.

Not on Google Play; distributed as APKs from GitHub releases. That's what lets us use `USE_EXACT_ALARM` and full-screen intents without Play policy review.

`TODO.md` is the spec and work plan — the source of truth for what gets built, in what order, and why. Read the relevant section before implementing a feature, and tick the PR checkbox when it merges.

**No network access — ever.** This app must never request the `INTERNET` permission; being fully offline is part of the product spec (the calendar reaches it through the provider, which Google Calendar syncs into). Anything update- or web-related goes through the browser instead: the "Check for updates" menu item opens the GitHub commits page (snapshot builds) or latest-release page (release builds) via an `ACTION_VIEW` URL, chosen at build time through the `check_for_updates_url` resValue in `app/build.gradle.kts`.

### Permissions

Unlike its sibling headache-tracker, this app genuinely needs permissions — calendar read **and** write (for RSVP), notifications, exact alarms, boot-completed, vibrate, and a foreground service for the ringing alarm. Every one of them is pinned in `app/expected-permissions.txt`, which the `release-verification` convention plugin in `build-logic/` (an included build) checks against the **merged release manifest** on every `check`:

- `:app:verifyReleasePermissions` — merged-manifest permissions must exactly match `app/expected-permissions.txt`
- `:app:verifyReleaseDependencies` — the release APK's transitive dependency set must exactly match `app/expected-dependencies.txt` (regenerate with `./gradlew :app:writeExpectedDependencies`)

Adding a permission is therefore a deliberate, reviewable act: add the `<uses-permission>` **and** the allowlist line in the same PR, with a sentence in the PR body saying why. `INTERNET` never goes in either file.

Convention plugins must stay in the `build-logic` included build, **never buildSrc**: buildSrc's parent classloader isolates AGP from the Kotlin compiler plugins, which silently disables Metro codegen and the app then crashes on launch.

---

## Package map

This is the **target** layout from `TODO.md` §3.3; each package arrives with the PR that first needs it (so far: `di/`, `model/`, `store/` + `store/sideeffects/`, `data/calendar/`, `permissions/`, `ui/navigation/`, `ui/theme/`, `ui/day/` (the empty day-view shell), `ui/onboarding/`, `ui/licenses/` and `ui/util/`). New code goes where this map says, not wherever is convenient.

| Package | Responsibility |
|---------|----------------|
| `di/` | Metro `AppGraph`, `AppMetroViewModelFactory`, `@ContributesTo` modules |
| `model/` | `CalendarEvent`, `CalendarInfo`, `EventKey`, `DayPlan`, `BusyRange`, `ScheduleChange` |
| `store/` | `AppState`, actions, reducer, the `AppStore` typealias |
| `store/sideeffects/` | One `SideEffect<AppState>` per concern (loading, toggling, alarms, RSVP, sharing, change detection) |
| `data/calendar/` | `CalendarRepository` interface + `ContentResolverCalendarRepository` |
| `data/db/` | Room: `MeetingMinderDatabase`, `DayPlanDao`, entities, migrations |
| `data/settings/` | DataStore-backed `SettingsRepository` (lead time, snooze, sound set) |
| `alarm/` | `AlarmScheduler`, `AlarmReceiver`, `BootReceiver`, `AlarmRingingService`, `AlarmSoundPlayer`, `AlarmActivity` |
| `monitor/` | `CalendarChangeWorker` (WorkManager), `ChangeDetector`, notifications |
| `share/` | `ScheduleTextFormatter`, `ShareLauncher` |
| `permissions/` | `PermissionChecker`, `PermissionRequester`, `PermissionState` |
| `ui/navigation/` | `Routes` (`@Serializable`), `Navigation.kt` (NavHost, VM wiring, launchers) |
| `ui/theme/` | `MeetingMinderTheme`, M3 colour scheme, typography |
| `ui/<feature>/` | One folder per screen: Composable(s) + ViewModel (`day/`, `onboarding/`, `alarm/`, `licenses/`) |

Root types:

- `MeetingMinderApp` — `Application` that creates the `AppGraph` on startup
- `Context.appGraph` — extension to reach the graph from Composables, activities, receivers and services
- `MainActivity` — edge-to-edge + `MeetingMinderTheme` + navigation

---

## Architectural patterns

### redux-store-flow is the shared state, ViewModels are the screen adapters

This app's state is genuinely cross-cutting: the day view, the ringing alarm activity, the alarm `BroadcastReceiver`, the boot receiver, the calendar-change worker and the notification actions all read and write the same "which events are selected / armed / shared" state. So there is **one** app-scoped `AppStore` (`StoreFlow<AppState>`), and non-UI components reach it directly:

```kotlin
typealias AppStore = StoreFlow<AppState>

@Provides @SingleIn(AppScope::class)
fun provideAppStore(scope: CoroutineScope, sideEffects: Set<SideEffect<AppState>>): AppStore =
    SubscriberAwareStoreFlow(
        scope = scope,
        initialValue = AppState(),
        reducer = AppState::reduce,
        middlewares = listOf(SideEffectMiddleware(sideEffects)),
    )
```

Conventions:

- Actions split into `sealed interface UpdateStateAction : Action` (the **only** actions the reducer touches) and `sealed interface AsyncAction : Action` (handled only by side effects).
- Side effects are contributed per feature: `@ContributesTo(AppScope::class) interface XSideEffects { @Provides @IntoSet fun ...: SideEffect<AppState> }`. One file per concern under `store/sideeffects/`.
- **Room is the source of truth for persisted state.** An observe-only side effect streams DAO flows into `Set…` actions. Two gotchas, both learned in podcast-hacker: an observe-only effect must still subscribe to `actions` (`merge(actions.filter { false }, dao.observe().map { … })`) or every effect starves; and never suspend inline in the relay path — do IO inside `flatMapMerge`/`transformLatest`.
- `SubscriberAwareStoreFlow` emits `SubscriberStatusChanged`, which is how the calendar `ContentObserver` gets registered only while UI is visible.

**ViewModels still exist**, one thin one per screen, and they are the only thing a Composable sees:

```kotlin
@Composable
fun SomeScreen(
    state: SomeState,          // immutable data class derived from the store
    onSomething: () -> Unit,   // callbacks that dispatch actions
)
```

- ViewModels expose `StateFlow<XxxUiState>` built with `store.mapStore { … }.stateIn(viewModelScope, WhileSubscribed(5_000), …)`, plus `on…` callbacks that dispatch.
- Composables **do not** see the store, call DAOs, launch coroutines for business logic, or hold mutable domain state. Collect state in the navigation/wiring layer.
- One-shot UI events (snackbars) come from the store's `transientMessage`, not long-lived `StateFlow` state: the ViewModel exposes a cold `Flow<UiMessage>` (`store.map { it.transientMessage }.filterNotNull().distinctUntilChanged` by id), and the wiring layer collects it in a `LaunchedEffect`, dispatching `ClearMessage(id)` through the ViewModel before showing the snackbar. Clearing is explicit, so nothing is lost when the effect restarts (e.g. returning from another screen).

### Dependency injection (Metro)

- **`AppGraph`** (`@DependencyGraph`, `@SingleIn(AppScope::class)`) provides app-scoped singletons: context, the app `CoroutineScope`, DataStore, the `AppStore` (built by `createAppStore`, which store tests call too), and from PR-7 the Room database (Room won't compile a database with no entities, so the provider arrives with the first table).
- ViewModels use `@Inject` + `@ViewModelKey` + `@ContributesIntoMap` (or `@AssistedInject` when they need runtime parameters) and are reached from Compose via `metroViewModel()` / `assistedMetroViewModel()` with `LocalMetroViewModelFactory` provided in `MainActivity`.
- Receivers, services and workers reach the graph through `Context.appGraph`.
- Do **not** introduce Hilt/Dagger.

### Event identity

`EventKey(eventId, instanceTime)` is the identity of one event *occurrence* and deliberately **survives the occurrence being moved** — a plain event dragged to a new time keeps its key, and a single occurrence edited in Google (which becomes an exception event with a new `Events._ID`) maps back to the same key. `CalendarEvent.eventId` is separate and is what every provider **write** and dirty check uses. `CalendarEvent.isMeeting` is the single canonical definition of "meeting"; every count, share line and change-detection rule refers to it rather than restating the conditions.

---

## UI conventions

### Material 3

- Use `MaterialTheme.colorScheme`, `MaterialTheme.typography`, `MaterialTheme.shapes`
- `MeetingMinderTheme` is built on the **episode6 orange** (`#FF6600`) with **dynamic colour deliberately off** — the brand colour is the point, and event chips already carry each calendar's own colour from the provider, so orange is reserved for chrome (app bar accents, FAB, checks, selected states of non-calendar controls, the alarm screen). The alarm screen is always dark.
- Edge-to-edge is enabled in `MainActivity` via `enableEdgeToEdge()`
- User-facing strings belong in `res/values/strings.xml`
- Previews: `@Preview` composables at the bottom of screen files, wrapped in `MeetingMinderTheme`

### Screen structure

- Top-level screens use `Scaffold` with `TopAppBar` where appropriate
- The day timeline is our own custom `Layout` (`DayEventsLayout`) — no third-party week-view library. Its overlap packing is a **pure function** (`layoutDay(events)`) so it stays unit-testable.

---

## Testing

Like the package map, this is the **target**, and each convention below arrives with the PR that first needs it. In place so far: plain unit tests for the reducer, store wiring, `DayViewModel`, `isMeeting` and `EventKey`; the side-effect `output(...)` helper (`app/src/test/.../store/sideeffects/SideEffectTestSupport.kt`); `FakeCalendarRepository` and the Robolectric `FakeCalendarProvider` (both under `app/src/test/.../data/calendar/`); Roborazzi's generated preview tests (`generateComposePreviewRobolectricTests` in `app/build.gradle.kts`, covering every non-private `@Preview` under `com.episode6.meetingminder`); the launch smoke test; and one instrumented repository test against the real provider.

- Pure logic (overlap packing, share text formatting, change-detection diff, alarm time math, the reducer) — plain JUnit 4 + **assertk**, no Android.
- Side effects — podcast-hacker's mockk-free `output(vararg actions, state)` helper over `SideEffectContext`; assert emitted actions with `containsExactly`. **Turbine** for flow assertions.
- Prefer hand-written fakes (`FakeCalendarRepository`) over mockk.
- `ContentResolver` code gets Robolectric tests against `FakeCalendarProvider`, registered for `com.android.calendar` via `Robolectric.setupContentProvider`. It keeps its rows in an in-memory SQLite database so the production projections, selections and bound arguments run for real; the only provider logic it emulates is the `instances/when/{begin}/{end}` overlap match and the `START_DAY`/`END_DAY` computation (local time for timed events, UTC for all-day ones, an event ending at midnight belongs to the previous day). Seed it with `addCalendar`/`addInstance`/`addAttendee`. One instrumented test (`ContentResolverCalendarRepositoryDeviceTest`) inserts a `LOCAL` calendar + event into the real provider.
- Screenshot tests use **Roborazzi** over every `@Preview`: the plugin's `generateComposePreviewRobolectricTests` generates one Robolectric test per preview, so previews must be `internal` (the scanner skips private ones) and a new preview needs no hand-written test. Reference PNGs are committed under `app/src/test/screenshots/` and CI runs `verifyRoborazziDebug`. Record them with `./gradlew recordRoborazziDebug` **inside the CI image** (font rendering differs between machines); a plain `test`/`check` renders but neither records nor compares.
- Device tests run on an API 36 emulator via `android-device-tests.yml`.

---

## Build & tooling

```bash
./gradlew assembleDebug     # compile
./gradlew check             # lint + unit tests + release verification
./gradlew :app:installDebug # install on a connected device
```

- **compileSdk 37 / targetSdk 36 / minSdk 31.** minSdk 31 because exact-alarm permissions, full-screen-intent behaviour and `VibrationAttributes` all start there, and it removes a pile of version branches. compileSdk is one ahead of targetSdk only because the pinned androidx libraries demand it in their aar-metadata; compiling against 37 changes nothing at runtime.
- **KSP** for Room codegen; the `androidx.room` gradle plugin owns the schema directory (`app/schemas/`, `exportSchema = true` so migrations are reviewable in the diff).
- **Pre-1.0 database policy**: `fallbackToDestructiveMigration` until the first `v1.0.0` tag; real migrations from the first release onward.
- **Compose BOM** pins Compose library versions; the version catalog is `gradle/libs.versions.toml`, and `self.versions.toml` holds the app's own version (single source of truth).
- Gradle is pinned at **9.5.1**, which caps AGP at **9.3.x** — AGP 9.4+ requires Gradle 9.6. Bump both together or neither.

### CI image

The gradle job in `build-installers.yml` runs inside a prebuilt Docker image, the same scheme as collins. `.github/docker/ci.Dockerfile` is the **single canonical list of build dependencies** (Zulu 21 JDK, which is also the pinned daemon JVM; Android command-line tools, `platforms;android-37.0`, `build-tools;36.0.0`, `platform-tools`; the gradle distribution the wrapper pins, pre-downloaded). The reusable `ci-image.yml` workflow tags the image `ghcr.io/episode6/meeting-minder-ci:<hash>` where the hash covers the Dockerfile, `gradlew`, `gradle/wrapper/**` and `gradle/gradle-daemon-jvm.properties`, and only builds when GHCR lacks that tag — a PR that edits any of those files builds and uses its own image; every other PR finds the tag in seconds.

- **Bumping `compileSdk`, AGP's default build-tools, the Gradle version or the daemon JVM means editing the Dockerfile in the same PR.** The SDK is root-owned inside the image on purpose, so AGP's auto-install of a missing component fails loudly instead of quietly downloading something the Dockerfile doesn't list.
- Nothing rebuilds on its own: bump the `refreshed:` date comment in the Dockerfile to pick up base-image or package updates.
- `android-device-tests.yml` stays on the bare runner (the emulator needs `/dev/kvm` and the host's udev rule); the tiny verification workflows do too.
- To try the image locally: `docker buildx build -f .github/docker/ci.Dockerfile -t meeting-minder-ci:local .`, then run `./gradlew check` inside it as the `runner` user.

### Versioning & releases

This repo follows the episode6 app-repo shape (see `RELEASE_CHECKLIST.md`, the source of truth):

- The app version lives in `self.versions.toml` (`MAJOR.MINOR.PATCH`, plain numeric). The android versionCode is **derived** in the root `build.gradle.kts` — never set versionCode/versionName by hand.
- Every build is a **snapshot** except CI builds off a release tag: snapshots install side-by-side with the release app under `com.episode6.meetingminder.snapshot` with a ` (SNAPSHOT)` display-name suffix, and derive their versionCode from the git commit count (full history required — shallow clones fail the build). Debug builds additionally append a `.debug` applicationIdSuffix, so a local `installDebug` never clobbers an installed CI-built snapshot APK.
- Every code change needs a `CHANGELOG.md` (or other docs) update — enforced by the `verify-docs` CI workflow. Add bullets under the top `### v<next> - Unreleased` section.
- Releases ship a signed APK to a GitHub release via `build-installers.yml`; the process is automated by the agent skills in `.agents/` (`release-branch-skill`, `ship-release-skill`, `update-docs-skill`, `verify`).

---

## Common pitfalls

| Pitfall | Guidance |
|---------|----------|
| INTERNET permission | Never add it (or any network dependency). The app is offline by spec; open URLs in the browser instead. |
| New permission | Manifest **and** `app/expected-permissions.txt` in the same PR, or `check` fails. |
| New dependency | Must appear in `THIRD_PARTY_LICENSES.md` (grouped by license, embedded into the app at build time) **and** `app/expected-dependencies.txt` (regenerate with `./gradlew :app:writeExpectedDependencies`). |
| Convention plugins in buildSrc | Never. buildSrc's classloader silently disables Metro codegen; keep them in the `build-logic` included build. |
| Suspending inline in a side effect's relay path | Starves the effect. Do IO inside `flatMapMerge`/`transformLatest`. |
| Observe-only side effects | Must still subscribe to `actions` (`merge(actions.filter { false }, …)`) or every effect starves. |
| Store in Composables | Composables take `state` + callbacks. Only ViewModels (and non-UI components) touch the store. |
| `EventKey` vs `eventId` | The key survives moves and identifies a *plan*; `eventId` is what provider writes and dirty checks use. They differ for exception events. |
| Alarms in the past | Skipped, with a snackbar — never silently dropped. |
| Shallow clones | Snapshot versionCodes come from the git commit count; every gradle-running CI checkout needs `fetch-depth: 0`. |
| Toolchain bump without the CI image | `compileSdk`, build-tools, Gradle or the daemon JVM changed but `.github/docker/ci.Dockerfile` didn't: the gradle job fails inside the image. Edit the Dockerfile in the same PR. |

---

## Code style expectations

- Kotlin idioms: data classes for state, sealed interfaces for actions/results/events
- Prefer `mapStore` / `stateIn` over manual collection for derived UI state
- Constants at file top or in a `Defaults` object (e.g. `DayViewDefaults`); no magic numbers in layout code
- No comments for obvious code; comment non-obvious business rules (the `isMeeting` definition, `EventKey` normalisation, alarm reconciliation)
- Do not commit secrets, `.env`, or local IDE paths. `debug.keystore` is the one deliberate exception — it holds the standard android debug credentials and is un-ignored in `.gitignore` on purpose.

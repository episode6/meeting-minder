# AGENTS.md — Meeting Minder

Guidance for AI assistants and contributors working in this codebase. Read this before making structural changes.

## Product summary

Meeting Minder is an android-only episode6 app for one job: **every morning, look at today, decide which meetings you're actually attending, get loud alarms for those, and send your partner a "here's when I'm busy" text.** During the day it watches the calendar and nags you to re-send the schedule when meetings appear or move.

- Reads **every calendar on every account** through the Android Calendar Provider. It writes back two things only: your **RSVP** (setting alarms marks each chosen meeting "Yes, going", and a chip's long-press menu writes the Yes / No / Maybe you pick for that one occurrence) and, with the opt-in busy-calendar sync (TODO.md §4.7), bare **`busy` blocks** on one calendar of your choosing — times only, never titles — which it later deletes again, and only ever the ones it wrote.
- Main screen: a Google-Calendar-style **single-day itinerary** (hour grid, proportional event heights, overlapping events side by side) with horizontal swiping between days. Tapping an event selects it ("I'm going to this"); long-pressing opens a menu: "Open in calendar", then "Respond Yes / No / Maybe" for an invite the app can answer. The app bar's "Refresh calendars" button asks the sync framework for a sync and reloads the shown days.
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

This is the **target** layout from `TODO.md` §3.3; each package arrives with the PR that first needs it (so far: `di/`, `model/`, `store/` + `store/sideeffects/`, `data/calendar/`, `data/db/`, `data/settings/`, `alarm/` (`AlarmScheduler`, `AlarmReconciler`, `AlarmRescheduler`, `AlarmNotifications`, `AlarmReceiver`, `BootReceiver`, and PR-10's ringing: `AlarmRingingService` over the Android-free `AlarmRingingSession`, `AlarmRinger` for the row transitions, `AlarmSoundPlayer` playing the pure `AlarmSoundRecipe`/`renderSiren` with `BundledAlarmSounds` and `RecentAlarmSounds`, `AlarmVibration`, `AlarmWakeLock`, `AlarmRingingCommands`, and `AlarmActivity`), `share/` (`ScheduleTextFormatter`, `ShareLauncher`, `selectedBusyRanges`, and PR-15's busy-calendar sync: the pure `BusyBlockReconciler` and `BusyCalendarSyncer` over `data/db/BusyBlockDao`, driven by `store/sideeffects/BusyCalendarSyncSideEffects`), `monitor/` (PR-11's change detection: the pure `ChangeDetector`, `ChangeMonitor` running it for every shared day, `CalendarChangeWorker` + `WorkManagerChangeWorkScheduler`, `ScheduleChangeNotifications`), `permissions/`, `ui/navigation/` (`Navigation.kt`, `Routes`, `NavigationViewModel`, and `DeepLinks` with the `DeepLinkInbox` that `MainActivity` feeds the notifications' `meetingminder://day|share/{date}` links into), `ui/theme/`, `ui/day/` (`DayScreen`, `DayPager`, `DayTimeline`, `DayEventsLayout`, `EventChip`, `NowLine`, `DayViewDefaults`, `FabState`, `DayViewModel`), `ui/onboarding/`, `ui/alarm/` (`AlarmRingingScreen`, `AlarmRingingViewModel`), `ui/settings/` (PR-12's `SettingsScreen`/`SettingsViewModel`, over `data/settings/SettingsRepository` plus `AppState.calendars`), `ui/licenses/` and `ui/util/`). New code goes where this map says, not wherever is convenient.

| Package | Responsibility |
|---------|----------------|
| `di/` | Metro `AppGraph`, `AppMetroViewModelFactory`, `@ContributesTo` modules |
| `model/` | `CalendarEvent`, `CalendarInfo`, `EventKey`, `DayPlan`, `BusyRange`, `ScheduleChange` |
| `store/` | `AppState`, actions, reducer, the `AppStore` typealias |
| `store/sideeffects/` | One `SideEffect<AppState>` per concern (loading, toggling, alarms, RSVP — the automatic `RsvpAccept` and the menu's `RespondToEvent` — sharing, change detection, the Refresh button's `RefreshCalendars`, the busy-calendar sync's `SyncBusyCalendar`/`BusySyncSettingChanged`) |
| `data/calendar/` | `CalendarRepository` interface + `ContentResolverCalendarRepository` (reads, the RSVP write, and the busy-block `insertBusyBlock`/`deleteOwnEvent`); `CalendarSyncRequester` (the Refresh button's `requestSync`); `BusyCalendars.kt` (`writable`/`defaultBusyCalendar`/`effectiveBusyCalendar`, TODO.md §4.7); `EffectiveCalendars.kt` (`effectiveCalendarFilter`, `excludeDeclined`, `excludeOwnBlocks`) |
| `data/db/` | Room: `MeetingMinderDatabase`, `DayPlanDao`, `ScheduledAlarmDao`, `ChangeSnapshotDao`, `BusyBlockDao` (the record of every busy block the app wrote), entities, migrations |
| `data/settings/` | DataStore-backed `SettingsRepository` (lead time, snooze, sound set, `BusySync`) |
| `alarm/` | `AlarmScheduler`, `AlarmReceiver`, `BootReceiver`, `AlarmMaintainer` (the automatic `MaintainAlarms` reconcile), `AlarmRingingService`, `AlarmSoundPlayer`, `AlarmActivity` |
| `monitor/` | `CalendarChangeWorker` (WorkManager), `CalendarProviderChangedReceiver` (the `PROVIDER_CHANGED` accelerator), `ChangeDetector`, notifications |
| `share/` | `ScheduleTextFormatter`, `ShareLauncher`, `selectedBusyRanges`; the busy-calendar sync's pure `reconcileBusyBlocks` (`BusyBlockReconciler.kt`) and `BusyCalendarSyncer` (TODO.md §4.7) |
| `permissions/` | `PermissionChecker`, `PermissionRequester`, `PermissionState`, `SleepyManufacturer` (the onboarding phone-maker card) |
| `ui/navigation/` | `Routes` (`@Serializable`), `Navigation.kt` (NavHost, VM wiring, launchers) |
| `ui/theme/` | `MeetingMinderTheme`, M3 colour scheme, typography |
| `ui/<feature>/` | One folder per screen: Composable(s) + ViewModel (`day/`, `onboarding/`, `alarm/`, `settings/`, `licenses/`) |

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
    createAppStore(scope, AppState(anchorDate = LocalDate.now(), …), sideEffects)   // store/AppStore.kt
```

`createAppStore` builds the store the way redux-store-flow's `SubscriberAwareStoreFlow` does (a `StoreFlow` + `SideEffectMiddleware`, shared with `WhileSubscribed()` and `replay = 0`, dispatching `SubscriberStatusChanged` as collectors come and go) with one deliberate difference: each new collector is handed the current state via `onSubscription`, not the library's `onStart`. `onStart` runs before the collector is registered with the shared flow, so a state change reduced while the collector is still busy with that first value (a `combine` downstream `yield()`s after every value, which on the main thread means "after the first frame") is emitted to nobody and the collector stays on stale state until the next change — the day view launched with an empty day whenever the load finished during the first frame. Keep building the store here, not with the library call, until the library adopts `onSubscription`.

Conventions:

- Actions split into `sealed interface UpdateStateAction : Action` (the **only** actions the reducer touches) and `sealed interface AsyncAction : Action` (handled only by side effects).
- Side effects are contributed per feature: `@ContributesTo(AppScope::class) interface XSideEffects { @Provides @IntoSet fun ...: SideEffect<AppState> }`. One file per concern under `store/sideeffects/`.
- **Room is the source of truth for persisted state.** An observe-only side effect streams DAO flows into `Set…` actions. Two gotchas, both learned in podcast-hacker: an observe-only effect must still subscribe to `actions` (`merge(actions.filter { false }, dao.observe().map { … })`) or every effect starves; and never suspend inline in the relay path — do IO inside `flatMapMerge`/`transformLatest`.
- The store emits `SubscriberStatusChanged` (redux-store-flow's `subscriber-aware` action) when its first collector arrives and its last one leaves, which is how the calendar `ContentObserver` gets registered only while UI is visible.

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

- **`AppGraph`** (`@DependencyGraph`, `@SingleIn(AppScope::class)`) provides app-scoped singletons: context, the app `CoroutineScope`, DataStore, the `AppStore` (built by `createAppStore`, which store tests call too), and (since PR-7) the Room `MeetingMinderDatabase` + `DayPlanDao` (`di/DatabaseModule.kt`).
- ViewModels use `@Inject` + `@ViewModelKey` + `@ContributesIntoMap` (or `@AssistedInject` when they need runtime parameters) and are reached from Compose via `metroViewModel()` / `assistedMetroViewModel()` with `LocalMetroViewModelFactory` provided in `MainActivity`.
- Receivers, services and workers reach the graph through `Context.appGraph`.
- Do **not** introduce Hilt/Dagger.

### Event identity

`EventKey(eventId, instanceTime)` is the identity of one event *occurrence* and deliberately **survives the occurrence being moved** — a plain event dragged to a new time keeps its key, and a single occurrence edited in Google (which becomes an exception event with a new `Events._ID`) maps back to the same key. `CalendarEvent.eventId` is separate and is what every provider **write** and dirty check uses. `CalendarEvent.isMeeting` is the single canonical definition of "meeting"; every count, share line and change-detection rule refers to it rather than restating the conditions.

### The RSVP write

The first thing the app ever writes to the calendar is a response to one occurrence of an event (TODO.md §4.6): `CalendarRepository.respondToInstance(event, response)`. It has two callers. The automatic one is "Yes, going" for an event just armed: `RsvpAcceptSideEffects` calls it with `EventResponse.YES` for each `RsvpAccept(date, key)` the alarm reconcile fans out, and the pure `rsvpDecision(event)` (`model/Rsvp.kt`) is the §4.6 skip table returning the `RsvpState` a newly armed selection starts in; only `PENDING` gets written. The explicit one is the chip's long-press menu: `RespondToEventSideEffects` writes the `RespondToEvent(date, key, response)` the user picked, gated by the pure `canRespond(event)` (the rows of the skip table that would crash or be rejected, plus the organizer; an already answered invite stays respondable, since changing the answer is the point). Rules that are easy to break: the automatic decision runs only on the explicit "Set alarms" tap, never from a background reconcile; the write is addressed by `CalendarEvent.eventId`, never `key.eventId`; `Events.SELF_ATTENDEE_STATUS` is only writable through the `exception/{eventId}` insert (a recurring occurrence) — a plain event goes through our own `attendees/{selfAttendeeId}` row; and the app never reverses an answer on its own (deselecting cancels the alarm and leaves the RSVP; only the user's own menu pick changes it). `selected_event.rsvp_state` tracks the outcome and `rsvp_event_id` the row it went to (the new exception's id), which is what the `DIRTY == 0` promotion to `SYNCED` checks on the next reload of the day; a menu "Yes" reports `RsvpAccepted` too so an armed selection gets its tick, and a "No"/"Maybe" resets the row to `NOT_APPLICABLE` (a plain `setRsvp` update, so nothing is selected by it) so an earlier automatic Yes's tick doesn't sit on a declined or tentative chip. The menu's reload also runs `MaintainAlarms`, which cancels a declined selection's alarm at once; picking the answer the calendar already holds writes nothing. The decision is written through `DayPlanDao.recordRsvpDecision`, which leaves an `ACCEPTED_LOCALLY`/`SYNCED` row alone so a re-armed selection keeps its "sent" tick; a selection toggled off loses its row (and so its tick) by design.

### The busy-block writes

The other thing the app writes is the opt-in busy-calendar sync's bare `busy` blocks (TODO.md §4.7): `CalendarRepository.insertBusyBlock(calendarId, range)` and `deleteOwnEvent(eventId)`, both plain non-sync-adapter writes so the account's own adapter carries them upstream (the app never touches the network). The insert writes **exactly** `CALENDAR_ID`, `DTSTART`, `DTEND`, `TITLE = "busy"`, `EVENT_TIMEZONE`, `AVAILABILITY_BUSY`, `HAS_ALARM = 0`, `ACCESS_DEFAULT` and `CUSTOM_APP_PACKAGE = packageName` — never a description, location, colour, organizer, guests or recurrence; `ContentResolverCalendarRepositoryBusyBlockTest` pins the column set. The Room table `busy_block` (`BusyBlockEntity`, keyed on the returned `Events._ID`) is the source of truth for what the app wrote: `BusyCalendarSyncer` records each row the moment its insert returns and drops it the moment its delete is asked for, and **`deleteOwnEvent` is only ever fed ids read from that table** — there is no selection on the delete precisely because the id is already known to be ours. `reconcileBusyBlocks(existing, desired, calendarId)` is the pure keep/delete/insert decision by exact instants (a moved range is delete + insert, never an update; a row on another calendar is deleted, never re-homed), and the syncer runs deletes before inserts, treats a `false` from `deleteOwnEvent` (the provider had no row — on a `LOCAL` calendar the user deleted it by hand; on a synced one a hand-deleted row still answers true until the adapter uploads the deletion, so `false` means nothing more than "no row") as done, keeps whatever succeeded before a failure (`BusySyncResult.Failed`), and rethrows `SecurityException` for the caller's permission re-check. The one accepted gap: a crash between `insertBusyBlock` returning and the `busy_block` upsert right after it leaves an **orphan** — an event on the calendar the table never learns about, which the app can never delete and which only the `CUSTOM_APP_PACKAGE` marker hides on this device; the window is one Room write wide. The insert's `EVENT_TIMEZONE` is the repository's own zone source (the one its reads use), not a caller-supplied zone, so there is one zone authority in the class. `CalendarEvent.ownedByApp` reads the `CUSTOM_APP_PACKAGE` marker back and `excludeOwnBlocks(ownedIds)` hides an event by marker **or** by table id; the marker is only a hint (it may not survive a sync round trip, and each build flavour's `applicationId` marks only its own blocks), the table is the authority. PR-15c wires `ShareDay`, "Mark as not shared" and the setting's cleanup to the syncer and applies `excludeOwnBlocks` at every read site.

The syncer is reached only through `store/sideeffects/BusyCalendarSyncSideEffects.kt`. `ShareDaySideEffects` fans out `SyncBusyCalendar(date, ranges)` with the ranges it just formatted, **after** `SetPendingShare` (so the chooser never waits on provider IO) and after the baseline was written (so §4.7's blocks can't be read back as changes) — the same shape as the alarm reconcile's `RsvpAccept` fan-out, whose handler also lives in its own file. A `BusySyncResult.Failed` is a snackbar and nothing more: a sync never blocks or reverses a share. "Mark as not shared" clears the day's blocks from inside the existing `markNotShared` effect rather than a second effect on the same action, so the two clears can't interleave; `BusySyncSettingChanged` clears from `LocalDate.now(clock)` forward (the whole future when the toggle went off, the old calendar's blocks when the target changed, nothing when it was only turned on), never from `AppState.anchorDate`.

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

Like the package map, this is the **target**, and each convention below arrives with the PR that first needs it. In place so far: plain unit tests for the reducer, store wiring, `DayViewModel`, `isMeeting`, `EventKey`, the `layoutDay` overlap packing, pager page/date maths and the chip mapping; side-effect tests with the `output(...)` helper (`app/src/test/.../store/sideeffects/SideEffectTestSupport.kt`; its `Flow` overload plus Turbine covers timing-dependent effects such as the debounced `CalendarObserver`); `FakeCalendarRepository`, `FakeCalendarChangeSource` and the Robolectric `FakeCalendarProvider` (all under `app/src/test/.../data/calendar/`; the provider also accepts and records the two RSVP writes, exercised by `ContentResolverCalendarRepositoryRsvpTest` for Yes, No and Maybe), `testCalendarEvent(...)` for `CalendarEvent` fixtures, `RsvpDecisionTest` for the §4.6 skip table and `CanRespondTest` for the long-press menu's gate; for the menu itself, `EventChipMenuTest` (a Robolectric compose test: long-press opens it, "Open in calendar" first, the answers only for a respondable chip), `RespondToEventSideEffectsTest` and `RefreshCalendarsSideEffectsTest` (a counting `CalendarSyncRequester` lambda); `FakeDayPlanDao` (`app/src/test/.../data/db/`, a `DayPlanDao` backed by two `MutableStateFlow`s) for side-effect tests, plus a Robolectric `DayPlanDaoTest` against a real in-memory Room database for the DAO/entities themselves; `SelectionPersistenceStoreTest` (`app/src/test/.../store/sideeffects/`), a `runStoreTest` wiring `ObserveDayPlansSideEffects` and `ToggleEventSideEffects` together over a `FakeDayPlanDao` to pin the Room round trip into `AppState.dayPlans` end to end; for sharing (PR-9), `ScheduleTextFormatterTest` (`app/src/test/.../share/`, the pure merge/format rules), `ShareDaySideEffectsTest`, `FakeChangeSnapshotDao` (beside `FakeDayPlanDao`) with a Robolectric `ChangeSnapshotDaoTest` and `ChangeSnapshotMappingTest` for the baseline JSON; `FakeSettingsRepository` (`app/src/test/.../data/settings/`) with a Robolectric `DataStoreSettingsRepositoryTest`; Roborazzi's generated preview tests (`generateComposePreviewRobolectricTests` in `app/build.gradle.kts`, covering every non-private `@Preview` under `com.episode6.meetingminder`); the launch smoke test; one instrumented repository test against the real provider; the instrumented `DayViewDeviceTest`, which inserts events into the real provider and waits for their chips; and, for the ringing experience (PR-10), `AlarmRingingSessionTest` (the service's rules over a recording `RingingOutputs`, in virtual time), `AlarmRingerTest`, `AlarmSoundRecipeTest`/`AlarmSirenTest` (the seeded sound draw and the synthesised siren), `RecentAlarmSoundsTest`, Robolectric `AlarmNotificationsTest`/`AlarmReceiverTest`, and the instrumented `AlarmRingingDeviceTest`, which arms a real alarm 10 s out, switches the screen off (a full-screen intent only launches straight away when the phone isn't in use) and waits for `AlarmActivity`. For change detection (PR-11): `ChangeDetectorTest` (one case per row of the TODO.md §4.3 table), `ChangeMonitorTest` over the fakes in `app/src/test/.../monitor/MonitorFakes.kt`, `WorkManagerChangeWorkSchedulerTest` (WorkManager's test instance from `WorkManagerTestInitHelper`, its `TestDriver` releasing the content trigger), `CalendarChangeWorkerTest` (the worker through `TestListenableWorkerBuilder` and the real graph, with `FakeCalendarProvider` registered and a baseline in the app's Room database), `ScheduleChangeNotificationsTest` and `DeepLinksTest`. For robustness (PR-13): `AlarmMaintainerTest` (the pure `maintainAlarms` rules plus the writer over the fakes), `AnchorDateSideEffectsTest` (a clock that runs on the test's virtual time), `CalendarProviderChangedReceiverTest` (WorkManager's test instance and the receiver's enabled state), and Robolectric compose tests driven by `createComposeRule()` — `DayScreenRolloverTest` (the pager keeps its date when the anchor moves), `DayScreenSubtitleTest` (the "shared …" subtitle names the day when the share happened on another one), `DayTimelineSemanticsTest` (what TalkBack is given: chip descriptions, states and action labels, gutter labels cleared) and `SettingsChipsSemanticsTest` (the single-choice chip rows read as radio buttons) — plus `EventChipCompactTest` (the compact chip's time range gives way to the start time, which always stays), which runs under `@GraphicsMode(NATIVE)` because legacy Robolectric graphics measure every glyph at about a pixel, so everything "fits". Device tests that route through onboarding hold `FullScreenIntentRule` (`app/src/androidTest/.../DeviceTestSupport.kt`), since "Full-screen alarms" is a required row and special access can't be granted with `GrantPermissionRule`. For busy-calendar sync (PR-15a): `BusyCalendarsTest` (`app/src/test/.../data/calendar/`, one case per `writable`/`defaultBusyCalendar`/`effectiveBusyCalendar` rule from TODO.md §4.7 — Family found, Family read-only, two Family calendars, case/whitespace, none), `DataStoreSettingsRepositoryTest`'s `BusySync` case, `SettingsViewModelTest`'s `onBusySyncToggle`/`onBusyCalendarSelected` cases, `SettingsChipsSemanticsTest`'s busy-calendar radio-row case and `DayScreenFabLabelTest` (the FAB reads "Sync & Share" only while the sync is effective). For the write path (PR-15b): `ContentResolverCalendarRepositoryBusyBlockTest` (the exact column set of a busy block and nothing that could leak the meeting, the plain non-sync-adapter insert, the returned id read back as `ownedByApp`, the bare `events/{id}` delete answering true only for a row that was there) over `FakeCalendarProvider`'s new `events` insert / `events/{id}` delete, `FakeCalendarRepository`'s recorded `busyBlockInserts`/`deletedEventIds`, `FakeBusyBlockDao` (beside `FakeDayPlanDao`) with a Robolectric `BusyBlockDaoTest`, `BusyBlockReconcilerTest` (one case per keep/delete/insert rule, the one-minute move, the other-calendar row, the repeated range that can't double-insert) and `BusyCalendarSyncerTest` (skipped when off / unset / calendar gone, deletes before inserts with each row recorded as its write returns, a `false` delete still dropping the row, an insert failing half way keeping the successful rows and returning `Failed`, `SecurityException` propagating, the three clears). For the wiring (PR-15c): `BusyCalendarSyncSideEffectsTest` (a successful sync says nothing, a failure names the calendar in the snackbar, a `SecurityException` re-checks permissions, and the cleanup rules — the toggle off deleting today and later on every calendar while yesterday survives, a switch deleting only the old calendar's, re-picking the calendar it already had deleting nothing), `ShareDaySideEffectsTest`'s fan-out cases (the `SyncBusyCalendar` lands after `SetPendingShare`, never with the feature off, and an empty selection still syncs an empty day) plus its cold-process case proving a block reaches neither the share text nor the baseline, `ChangeMonitorTest`'s case proving one never reads as a change, `BusyBlockHidingStoreTest` (a `runStoreTest` over the real `LoadDayEvents`/`ObserveDayPlans`/`ToggleEvent` wiring: a block never reaches `eventsByDay`, so tapping where its chip would be selects nothing), `UiMessageTextTest` (the snackbar's plain, formatted and plurals shapes against real resources) and the instrumented `BusyCalendarSyncDeviceTest`, which seeds a `LOCAL` "Family" calendar and a meeting just after midnight (so "Set alarms" always skips it and no alarm is left armed), taps the real "Sync & Share" FAB, and asserts one bare `busy` row with matching times, no description, location or attendees, no `busy` chip in the itinerary, and nothing left on the calendar after a re-share with the meeting deselected.

- Pure logic (overlap packing, share text formatting, change-detection diff, alarm time math, the reducer) — plain JUnit 4 + **assertk**, no Android.
- Side effects — podcast-hacker's mockk-free `output(vararg actions, state)` helper over `SideEffectContext`; assert emitted actions with `containsExactly`. **Turbine** for flow assertions.
- Prefer hand-written fakes (`FakeCalendarRepository`) over mockk.
- `ContentResolver` code gets Robolectric tests against `FakeCalendarProvider`, registered for `com.android.calendar` via `Robolectric.setupContentProvider`. It keeps its rows in an in-memory SQLite database so the production projections, selections and bound arguments run for real; the only provider logic it emulates is the `instances/when/{begin}/{end}` overlap match and the `START_DAY`/`END_DAY` computation (local time for timed events, UTC for all-day ones, an event ending at midnight belongs to the previous day). Seed it with `addCalendar`/`addInstance`/`addAttendee`; a busy-block `insert` on `events` expands one instance row with the calendar's columns joined in, and a `delete` on `events/{id}` removes it (`inserts`/`deletes` record every call). One instrumented test (`ContentResolverCalendarRepositoryDeviceTest`) inserts a `LOCAL` calendar + event into the real provider.
- Screenshot tests use **Roborazzi** over every `@Preview`: the plugin's `generateComposePreviewRobolectricTests` generates one Robolectric test per preview, so previews must be `internal` (the scanner skips private ones) and a new preview needs no hand-written test. Reference PNGs are committed under `app/src/test/screenshots/` and CI runs `verifyRoborazziDebug`. References must be recorded **inside the CI image** (font rendering differs between machines), and **never locally** — not on the host and not by running the CI image under local Docker, which is heavy enough to crash a laptop. Instead, push the code and apply the `record-screenshots` label to the PR: `record-screenshots.yml` runs `recordRoborazziDebug` in the image and, if anything changed, opens a PR from `screenshots/<branch>` into the PR's branch (and comments on the PR either way). **View every added or changed PNG in that PR before merging it** — clipped, overlapping or truncated content, blank or error renders, and wrong theme or colours all get recorded as happily as correct ones. If any image is wrong, close it, fix the code and re-apply the label; if all are right, merge it into the branch, which reruns CI. The recording is made from the PR branch's own head, while `verifyRoborazziDebug` in `build-installers.yml` runs on the PR's merge commit with its base, so in a stacked PR a base that changes how a preview renders makes freshly recorded references fail verify: merge the base into the branch first, then record. A plain `test`/`check` renders but neither records nor compares.
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
| `SubscriberAwareStoreFlow(...)` from the library | Don't: its `onStart { emit(state) }` hand-over loses changes made while a new collector is busy with its first value (the first frame). `createAppStore` uses `onSubscription`; `AppStoreTest` pins it. |
| Store in Composables | Composables take `state` + callbacks. Only ViewModels (and non-UI components) touch the store. |
| `EventKey` vs `eventId` | The key survives moves and identifies a *plan*; `eventId` is what provider writes and dirty checks use. They differ for exception events. |
| RSVP without the skip table | Never call `respondToInstance` for an event the gate didn't pass — `rsvpDecision == PENDING` on the automatic path, `canRespond` for the menu: the exception insert crashes the provider ("Status update WTF") when the event has no self-attendee row, and a read-only calendar takes the local write only for the server to reject it on sync. `EventChip` hides the "Respond …" items unless `TimelineEvent.respondable`, and `RespondToEventSideEffects` re-checks before writing. |
| `deleteOwnEvent` fed anything but a `busy_block` id | The delete is a bare `events/{id}` with no selection, on a calendar other people read. Every id it gets must come out of `BusyBlockDao` (`BusyCalendarSyncer` is the only caller); never pass a `CalendarEvent.eventId` from a read, an `ownedByApp` match or a title match. Likewise `insertBusyBlock`'s column set is closed: adding a column (a description, a colour, a reminder) leaks the meeting or changes how the partner's calendar shows the block — extend `ContentResolverCalendarRepositoryBusyBlockTest` first. |
| `excludeOwnBlocks` missing at a read site | Every provider read that can reach a selection, a share or a diff must apply it next to `excludeDeclined` — the day load, `ChangeMonitor.check` and the share's cold-process read. The chosen calendar is usually visible, so a block that slips through draws a chip, can be selected, folds back into the next share as a busy range, and reads as a New meeting on the next change check. Add the ids from `BusyBlockDao.eventIds()` (read once per pass, like the calendar filter), not a title match. |
| Network in the name of "refresh" | The Refresh button must stay `ContentResolver.requestSync` (the accounts' own sync adapters do the fetching) plus a `CalendarContentChanged` reload. No `INTERNET`, no fetching of our own. |
| Selection keyed only on `EventKey` | `selected_event`'s primary key is `(date, event_id, instance_time)`: an event spanning midnight can appear on two adjacent day pages with its own selection on each, so `ToggleEvent`/`onEventToggle` always carry the tapped page's date, not just the key. |
| Read-then-write across two DAO calls under `flatMapMerge` | Two concurrent invocations of the same effect (e.g. a fast double tap dispatching the same `ToggleEvent` twice) can each read the pre-write state before either writes. Put the read and the write in one `@Transaction` DAO method (`DayPlanDao.toggleSelectedEvent`) rather than a separate `selectedEventsOn(...)` check followed by a separate insert/delete. |
| `runStoreTest` + a `combine`-based observe-only effect | The default `context` param creates a second, unrelated `TestCoroutineScheduler`; `combine`'s internal `yield()` then trips kotlinx-coroutines-test's "different schedulers" check. Pass `context = EmptyCoroutineContext` to keep everything on the one scheduler `runStoreTest`'s own `runTest` already provides (see `SelectionPersistenceStoreTest`). |
| Alarms in the past | Skipped, with a snackbar — never silently dropped. |
| Hiding the FAB on an empty selection | Deselecting an armed event doesn't cancel its alarm; only the next "Set alarms" reconcile does. `DayPlan.armedKeys` (the day's `SCHEDULED` rows, streamed by `ObserveDayPlans`) keeps the FAB visible as "Clear alarms" (`FabState.SetAlarms(0)`) until nothing is armed. `alarms_set_at` is only written when every selection was armed or skipped — a refused `setAlarmClock` or a clear-only reconcile leaves it null so the tap stays available. |
| Receivers and the store | A `BroadcastReceiver` (or a `Worker`: `CalendarChangeWorker` calls `ChangeMonitor` directly) can't await a store dispatch, so work that must finish before the broadcast ends (`BootReceiver`'s re-arm) calls the injected handler directly under `goAsync()`. `AlarmReceiver` does no work at all: it takes `AlarmWakeLock` and calls `startForegroundService` at once, since the alarm-clock allowlist window for starting a foreground service is short. Dispatch to the store only for state the UI needs to see. |
| `startForegroundService` without `startForeground` | Every start of `AlarmRingingService` from `AlarmReceiver` must be answered with `startForeground` within seconds — including a fire that turns out to have nothing to ring — or the OS kills the app. `AlarmRingingSession` re-posts at once when already foreground, posts a placeholder when the row is slow to load (`FOREGROUND_WATCHDOG_MILLIS`) or there is nothing to ring, and only stops when no command is still queued; keep those rules (and `AlarmRingingSessionTest`) when changing the service. Commands that aren't foreground starts (Snooze/Dismiss) use plain `startService`/`getService`. |
| Ringing audio without `USAGE_ALARM` | Every player, audio-focus request and vibration of a ringing alarm uses `AlarmAudioAttributes` (`USAGE_ALARM`): it's what plays through DND and media volume, and Android 17 only lets an exact-alarm app play from the background on alarm streams. |
| Snoozed alarms | `SNOOZED` is armed, like `SCHEDULED` (`AlarmState.armed`; the DAO queries use `state IN ('SCHEDULED', 'SNOOZED')`). Never compare a snoozed row's `fire_at` (its snooze time) with its event's alarm time — the reconcile would read every snooze as moved into the past. Snooze and the auto-timeout re-arm through `setAlarmClock`, never an in-process delay. |
| Polling for UI changes in a device test | Under a compose test rule the frame clock only advances while the test synchronises with Compose (`waitUntil`, `onNode…`, `performClick`). A plain `sleep` loop waiting for something a recomposition causes — `AlarmActivity` closing itself once nothing rings — waits forever. Wait with `composeRule.waitUntil { … }` even when the condition itself isn't a compose query (see `AlarmRingingDeviceTest`/`DayViewDeviceTest`). |
| Infinite animations in previews | The ringing screen's pulse is an infinite transition; its previews pass `animated = false` so the generated Roborazzi tests capture a still frame. Do the same for any new infinitely animated composable. |
| Re-arming unique work from inside its own worker | `ExistingWorkPolicy.REPLACE` cancels the run that is asking and `KEEP` does nothing while it runs; the content trigger re-arms itself with `APPEND_OR_REPLACE` and everything else uses `KEEP` (`WorkManagerChangeWorkScheduler`). A periodic work with no initial delay runs at once — in WorkManager's test driver too, where it runs the real worker. |
| Permissions merged in from a library | `verifyReleasePermissions` checks the **merged** manifest. Remove a permission the app never uses with `tools:node="remove"` in `AndroidManifest.xml` (WorkManager's `ACCESS_NETWORK_STATE`) rather than allowlisting it, and say why in a comment. |
| `scheduled_alarm` vs `AlarmManager` | The Room row is the source of truth; `AlarmScheduler` only mirrors it. Never arm an alarm without a row, and identify its `PendingIntent` by `alarm_id` (data URI + request code), never by extras. |
| `Clock.systemDefaultZone()` | Never provide or capture one: it fixes the zone when it's created, and app-scoped singletons keep theirs for the life of the process, so a timezone change would leave them in the old zone. Inject the graph's `Clock` (`di/DeviceClock`, which re-reads the zone on every call) and derive "today" from it each time. |
| `AppState.anchorDate` as "today" | It is kept current only while UI is visible (`AnchorDateSideEffects`). Receivers, workers and services must use `LocalDate.now(clock)`, never the anchor. A new pager or page-index computation must survive the anchor moving under it (`DayScreenRolloverTest`). |
| Text width in a Robolectric compose test | Legacy Robolectric graphics measure every glyph at about a pixel, so a test that depends on text *not* fitting (ellipsis, an overflow branch such as the compact chip dropping its time range for the start time alone) passes vacuously. Annotate the test class `@GraphicsMode(GraphicsMode.Mode.NATIVE)` for real font metrics (`EventChipCompactTest`). |
| Chip text in tests vs TalkBack | `EventChip` sets a `contentDescription` that TalkBack reads *instead of* the merged text, but the text stays in the semantics tree because `DayViewDeviceTest` finds chips with `hasText`. Don't `clearAndSetSemantics` the chip content. |
| `TEST_ALARM_EVENT_ID` (`model/RingingAlarm.kt`) | Settings' "Test alarm" row's `event_id` is `-1`, never a real `Events._ID`. Code that folds every armed `scheduled_alarm` row into UI state (`buildDayPlans`'s `armedKeys`, the ringing screen's "Open meeting") must exclude or special-case it, or it leaks into the day view's FAB and tries to open a non-existent calendar event. |
| Shallow clones | Snapshot versionCodes come from the git commit count; every gradle-running CI checkout needs `fetch-depth: 0`. |
| Toolchain bump without the CI image | `compileSdk`, build-tools, Gradle or the daemon JVM changed but `.github/docker/ci.Dockerfile` didn't: the gradle job fails inside the image. Edit the Dockerfile in the same PR. |

---

## Code style expectations

- Kotlin idioms: data classes for state, sealed interfaces for actions/results/events
- Prefer `mapStore` / `stateIn` over manual collection for derived UI state
- Constants at file top or in a `Defaults` object (e.g. `DayViewDefaults`); no magic numbers in layout code
- No comments for obvious code; comment non-obvious business rules (the `isMeeting` definition, `EventKey` normalisation, alarm reconciliation)
- Do not commit secrets, `.env`, or local IDE paths. `debug.keystore` is the one deliberate exception — it holds the standard android debug credentials and is un-ignored in `.gitignore` on purpose.

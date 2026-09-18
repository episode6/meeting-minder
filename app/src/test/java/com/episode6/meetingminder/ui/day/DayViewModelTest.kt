package com.episode6.meetingminder.ui.day

import com.episode6.meetingminder.monitor.ScheduleChangeLine
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.model.BusyRange
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.prop
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.calendar.ShareMode
import com.episode6.meetingminder.data.settings.BusySync
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.Settings
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.DayPlan
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.RsvpState
import com.episode6.meetingminder.model.SelectedEvent
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.LoadDay
import com.episode6.meetingminder.store.MarkNotShared
import com.episode6.meetingminder.store.PendingShare
import com.episode6.meetingminder.store.SetPendingShare
import com.episode6.meetingminder.store.SetAlarms
import com.episode6.meetingminder.store.SetCalendars
import com.episode6.meetingminder.store.ShareDay
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.ToggleEvent
import com.episode6.meetingminder.store.UiMessage
import com.episode6.meetingminder.store.createAppStore
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import com.episode6.redux.testsupport.runStoreTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModelTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 9, 14)
    private val tomorrow = today.plusDays(1)
    private val now = today.atTime(9, 10)
    private val clock = Clock.fixed(now.toInstant(zone), zone)
    private val loadedAt = Instant.EPOCH

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).toInstant(zone)

    private val standup = testCalendarEvent(1, at(today, 9, 30), at(today, 10), title = "Standup")
    private val dentist = testCalendarEvent(2, at(today, 7), at(today, 8), title = "Dentist", meeting = false)
    private val designReview = testCalendarEvent(3, at(today, 14), at(today, 15), title = "Design review")
    private val holiday = testCalendarEvent(4, at(today, 0), at(tomorrow, 0), title = "Holiday", meeting = false, allDay = true)
    private val lateShow = testCalendarEvent(5, at(today, 23), at(tomorrow, 0), title = "Late show", meeting = false)

    private val family = CalendarInfo(
        id = 7,
        accountName = "me@example.com",
        accountType = "com.google",
        displayName = "Family",
        color = 0,
        visible = true,
        syncEvents = true,
        ownerAccount = "me@example.com",
        isPrimary = false,
        accessLevel = 700,
        canOrganizerRespond = false,
    )

    /** A [SettingsRepository] whose flow never emits, like DataStore before its first disk read completes. */
    private class NeverEmittingSettingsRepository : SettingsRepository by FakeSettingsRepository() {
        override val settings: Flow<Settings> = flow { awaitCancellation() }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun state_beforeAnythingLoads_hasNoCountDaysOrInitialScroll() {
        assertThat(AppState(anchorDate = today).toDayUiState(now, zone)).isEqualTo(DayUiState(anchorDate = today))
        assertThat(AppState(anchorDate = today, settledDate = tomorrow).toDayUiState(now, zone))
            .isEqualTo(DayUiState(anchorDate = today, date = tomorrow, isToday = false))
    }

    @Test
    fun state_mapsEachLoadedDay_andCountsOnlyMeetingsOnTheSettledDay() {
        val state = AppState(
            anchorDate = today,
            eventsByDay = mapOf(
                today to DayEvents(today, listOf(holiday, dentist, standup, designReview), loadedAt),
                tomorrow to DayEvents(tomorrow, listOf(lateShow), loadedAt),
            ),
        )

        val ui = state.toDayUiState(now, zone)

        assertThat(ui.meetingCount).isEqualTo(2)
        assertThat(ui.initialFirstVisibleHour).isEqualTo(8.5f)
        assertThat(ui.timelineFor(today)).isEqualTo(
            DayTimelineState(
                date = today,
                allDayEvents = listOf(holiday.toTimelineEvent(zone)),
                timedEvents = listOf(dentist, standup, designReview).map { it.toTimelineEvent(zone) },
                now = LocalTime.of(9, 10),
            ),
        )
        // not today: no now-line; and an event ending exactly at midnight doesn't reach into it
        assertThat(ui.timelineFor(tomorrow)).isEqualTo(DayTimelineState(tomorrow))
        assertThat(ui.days.keys).isEqualTo(setOf(today, tomorrow))
    }

    @Test
    fun state_settledOnADayWithNoMeetings_countsZero() {
        val state = AppState(
            anchorDate = today,
            settledDate = tomorrow,
            eventsByDay = mapOf(tomorrow to DayEvents(tomorrow, listOf(lateShow), loadedAt)),
        )

        val ui = state.toDayUiState(now, zone)

        assertThat(ui.meetingCount).isEqualTo(0)
        // the initial scroll waits for the anchor day, not whichever day is settled
        assertThat(ui.initialFirstVisibleHour).isNull()
    }

    @Test
    fun state_followsTheStore() = runStoreTest({ createAppStore(this, AppState(anchorDate = today), emptySet()) }) { store ->
        val viewModel = DayViewModel(store, clock, FakeSettingsRepository())
        viewModel.state.test {
            assertThat(awaitItem()).isEqualTo(DayUiState(anchorDate = today))

            viewModel.onPageSettled(tomorrow)

            assertThat(awaitItem()).isEqualTo(DayUiState(anchorDate = today, date = tomorrow, isToday = false))
        }
    }

    @Test
    fun state_labelsTheShareFabAsSyncing_onceTheSettingsFlowSaysTheSyncIsEffective() = runStoreTest(
        {
            createAppStore(
                this,
                AppState(anchorDate = today, calendars = listOf(family), dayPlans = mapOf(today to DayPlan(today, alarmsSetAt = Instant.EPOCH))),
                emptySet(),
            )
        },
    ) { store ->
        val settings = FakeSettingsRepository(Settings(busySync = BusySync(enabled = true, calendarId = family.id)))
        val viewModel = DayViewModel(store, clock, settings)

        viewModel.state.test {
            val synced = awaitItem()
            assertThat(synced.fabState).isEqualTo(FabState.Share(ShareMode.SYNC_AND_TEXT))
            assertThat(synced.shareMode).isEqualTo(ShareMode.SYNC_AND_TEXT)

            settings.setBusySyncEnabled(false)

            assertThat(awaitItem().fabState).isEqualTo(FabState.Share())
        }
    }

    @Test
    fun state_labelsTheShareFabAsSyncOnly_whenTheTextIsTurnedOff_andFallsBackToText_whenTheCalendarGoes() = runStoreTest(
        {
            createAppStore(
                this,
                AppState(anchorDate = today, calendars = listOf(family), dayPlans = mapOf(today to DayPlan(today, alarmsSetAt = Instant.EPOCH))),
                emptySet(),
            )
        },
    ) { store ->
        val settings = FakeSettingsRepository(Settings(busySync = BusySync(enabled = true, calendarId = family.id, sendText = false)))
        val viewModel = DayViewModel(store, clock, settings)

        viewModel.state.test {
            val syncOnly = awaitItem()
            assertThat(syncOnly.fabState).isEqualTo(FabState.Share(ShareMode.SYNC_ONLY))
            assertThat(syncOnly.shareMode).isEqualTo(ShareMode.SYNC_ONLY)

            // the calendar is gone: a "Sync busy times" button would write nothing, so it shares
            store.dispatch(SetCalendars(emptyList()))

            assertThat(awaitItem().fabState).isEqualTo(FabState.Share(ShareMode.TEXT))
        }
    }

    @Test
    fun state_followsTheStore_beforeTheSettingsHaveBeenRead() = runStoreTest({ createAppStore(this, AppState(anchorDate = today), emptySet()) }) { store ->
        // DataStore's first emission is a disk read; a store change in that window must
        // still reach the screen (with the sync read as off until the settings land)
        val viewModel = DayViewModel(store, clock, NeverEmittingSettingsRepository())
        viewModel.state.test {
            assertThat(awaitItem()).isEqualTo(DayUiState(anchorDate = today))

            viewModel.onPageSettled(tomorrow)

            assertThat(awaitItem()).isEqualTo(DayUiState(anchorDate = today, date = tomorrow, isToday = false))
        }
    }

    @Test
    fun onPageSettled_settlesOnThatDay_andLoadsIt() {
        val loads = MutableSharedFlow<Action>(replay = 10)
        val recordLoads = SideEffect<com.episode6.meetingminder.store.AppState> {
            actions.onEach { if (it is LoadDay) loads.emit(it) }.filter { false }
        }
        runStoreTest({ createAppStore(this, AppState(anchorDate = today), setOf(recordLoads)) }) { store ->
            val viewModel = DayViewModel(store, clock, FakeSettingsRepository())

            viewModel.onPageSettled(tomorrow)

            assertThat(loads.first()).isEqualTo(LoadDay(tomorrow))
            assertThat(store.state.settledDate).isEqualTo(tomorrow)
        }
    }

    @Test
    fun calendarEventFor_findsTheEventInAnyLoadedDay() = runStoreTest(
        {
            createAppStore(
                this,
                AppState(
                    anchorDate = today,
                    eventsByDay = mapOf(
                        today to DayEvents(today, listOf(standup), loadedAt),
                        tomorrow to DayEvents(tomorrow, listOf(lateShow), loadedAt),
                    ),
                ),
                emptySet(),
            )
        },
    ) { store ->
        val viewModel = DayViewModel(store, clock, FakeSettingsRepository())

        assertThat(viewModel.calendarEventFor(lateShow.key)).isEqualTo(lateShow)
        assertThat(viewModel.calendarEventFor(EventKey(99, 0))).isNull()
    }

    @Test
    fun messages_emitEachMessageOnce_andShowingOneClearsIt() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store, clock, FakeSettingsRepository())
        val message = UiMessage(id = 1, text = 1)
        viewModel.messages.test {
            store.dispatch(ShowMessage(message))
            assertThat(awaitItem()).isEqualTo(message)

            viewModel.onMessageShown(message)

            expectNoEvents()
            assertThat(store.state.transientMessage).isNull()
        }
    }

    @Test
    fun pendingShare_emitsEachShareOnce_andLaunchingItClearsIt() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store, clock, FakeSettingsRepository())
        val share = PendingShare.next(today, "text")
        viewModel.pendingShare.test {
            store.dispatch(SetPendingShare(share))
            assertThat(awaitItem()).isEqualTo(share)

            viewModel.onShareLaunched(share)

            expectNoEvents()
            assertThat(store.state.pendingShare).isNull()

            val next = PendingShare.next(today, "again")
            store.dispatch(SetPendingShare(next))
            assertThat(awaitItem()).isEqualTo(next)
        }
    }

    @Test
    fun onOpenInCalendarFailed_showsTheNoCalendarAppMessage() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store, clock, FakeSettingsRepository())
        viewModel.messages.test {
            viewModel.onOpenInCalendarFailed()

            assertThat(awaitItem()).prop(UiMessage::text).isEqualTo(R.string.open_in_calendar_failed)
        }
    }

    @Test
    fun onCheckForUpdatesFailed_showsTheNoBrowserMessage() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store, clock, FakeSettingsRepository())
        viewModel.messages.test {
            viewModel.onCheckForUpdatesFailed()

            assertThat(awaitItem()).prop(UiMessage::text).isEqualTo(R.string.check_for_updates_no_browser)
        }
    }

    @Test
    fun onEventToggle_dispatchesToggleEventForThePagesDate() {
        val toggles = MutableSharedFlow<Action>(replay = 10)
        val recordToggles = SideEffect<AppState> {
            actions.onEach { if (it is ToggleEvent) toggles.emit(it) }.filter { false }
        }
        runStoreTest({ createAppStore(this, AppState(anchorDate = today), setOf(recordToggles)) }) { store ->
            val viewModel = DayViewModel(store, clock, FakeSettingsRepository())

            // the event loaded on today's page, but the tap came from tomorrow's (an event
            // spanning midnight can appear on both, each with its own selection)
            viewModel.onEventToggle(tomorrow, standup.toTimelineEvent(zone))

            assertThat(toggles.first()).isEqualTo(ToggleEvent(tomorrow, standup.key))
        }
    }

    @Test
    fun onFabClick_inSetAlarmsState_dispatchesSetAlarmsForTheSettledDay() {
        val dispatched = MutableSharedFlow<Action>(replay = 10)
        val record = SideEffect<AppState> {
            actions.onEach { if (it is SetAlarms) dispatched.emit(it) }.filter { false }
        }
        val selected = DayPlan(tomorrow, selected = mapOf(standup.key to standup.toSelectedEventForTest()))
        runStoreTest(
            { createAppStore(this, AppState(anchorDate = today, settledDate = tomorrow, dayPlans = mapOf(tomorrow to selected)), setOf(record)) },
        ) { store ->
            val viewModel = DayViewModel(store, clock, FakeSettingsRepository())

            viewModel.onFabClick()

            assertThat(dispatched.first()).isEqualTo(SetAlarms(tomorrow))
        }
    }

    @Test
    fun onFabClick_inShareState_dispatchesShareDayForTheSettledDay() {
        val dispatched = MutableSharedFlow<Action>(replay = 10)
        val record = SideEffect<AppState> {
            actions.onEach { if (it is ShareDay) dispatched.emit(it) }.filter { false }
        }
        val armed = DayPlan(today, selected = mapOf(standup.key to standup.toSelectedEventForTest()), alarmsSetAt = Instant.EPOCH)
        runStoreTest(
            { createAppStore(this, AppState(anchorDate = today, dayPlans = mapOf(today to armed)), setOf(record)) },
        ) { store ->
            val viewModel = DayViewModel(store, clock, FakeSettingsRepository())

            viewModel.onFabClick()

            assertThat(dispatched.first()).isEqualTo(ShareDay(today))
        }
    }

    @Test
    fun aSecondShareTap_whileTheSheetIsStillOpening_startsNoSecondShare() {
        val dispatched = MutableSharedFlow<Action>(replay = 10)
        val record = SideEffect<AppState> {
            actions.onEach { if (it is ShareDay) dispatched.emit(it) }.filter { false }
        }
        val armed = DayPlan(today, selected = mapOf(standup.key to standup.toSelectedEventForTest()), alarmsSetAt = Instant.EPOCH)
        runStoreTest(
            { createAppStore(this, AppState(anchorDate = today, dayPlans = mapOf(today to armed)), setOf(record)) },
        ) { store ->
            val viewModel = DayViewModel(store, clock, FakeSettingsRepository())

            viewModel.onFabClick()
            // a fast double tap, and "Share again" while the chooser is still coming up
            viewModel.onFabClick()
            viewModel.onShareAgainClick()

            assertThat(store.state.shareInFlight).isEqualTo(true)
            assertThat(dispatched.first()).isEqualTo(ShareDay(today))
            assertThat(dispatched.replayCache).containsExactly(ShareDay(today))

            // the sheet has closed: the next tap is a new share
            viewModel.onShareSheetClosed()
            viewModel.onFabClick()

            assertThat(store.state.shareInFlight).isEqualTo(true)
            assertThat(dispatched.replayCache).containsExactly(ShareDay(today), ShareDay(today))
        }
    }

    @Test
    fun onShareAgainClick_dispatchesShareDayForTheSettledDay() {
        val dispatched = MutableSharedFlow<Action>(replay = 10)
        val record = SideEffect<AppState> {
            actions.onEach { if (it is ShareDay) dispatched.emit(it) }.filter { false }
        }
        runStoreTest({ createAppStore(this, AppState(anchorDate = today, settledDate = tomorrow), setOf(record)) }) { store ->
            val viewModel = DayViewModel(store, clock, FakeSettingsRepository())

            viewModel.onShareAgainClick()

            assertThat(dispatched.first()).isEqualTo(ShareDay(tomorrow))
        }
    }

    @Test
    fun onMarkNotSharedClick_dispatchesMarkNotSharedForTheSettledDay() {
        val dispatched = MutableSharedFlow<Action>(replay = 10)
        val record = SideEffect<AppState> {
            actions.onEach { if (it is MarkNotShared) dispatched.emit(it) }.filter { false }
        }
        runStoreTest({ createAppStore(this, AppState(anchorDate = today), setOf(record)) }) { store ->
            val viewModel = DayViewModel(store, clock, FakeSettingsRepository())

            viewModel.onMarkNotSharedClick()

            assertThat(dispatched.first()).isEqualTo(MarkNotShared(today))
        }
    }

    @Test
    fun onShareLaunched_clearsThePendingShare() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today, pendingShare = PendingShare.next(today, "text")), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store, clock, FakeSettingsRepository())
        val share = store.state.pendingShare!!

        viewModel.onShareLaunched(share)

        assertThat(store.state.pendingShare).isNull()
    }

    @Test
    fun onFabClick_withNothingSelected_doesNothing() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store, clock, FakeSettingsRepository())
        viewModel.messages.test {
            viewModel.onFabClick()

            expectNoEvents()
        }
    }

    @Test
    fun toDayUiState_countsArmedSelectionsOnTheSettledDay() {
        val armedStandup = standup.toSelectedEventForTest().copy(alarmId = 1, alarmAt = standup.begin.minusSeconds(300))
        val state = AppState(
            anchorDate = today,
            dayPlans = mapOf(
                today to DayPlan(
                    today,
                    selected = mapOf(standup.key to armedStandup, dentist.key to dentist.toSelectedEventForTest()),
                    alarmsSetAt = Instant.EPOCH,
                ),
            ),
        )

        val ui = state.toDayUiState(now, zone)

        assertThat(ui.fabState).isEqualTo(FabState.Share())
        assertThat(ui.armedCount).isEqualTo(1)
    }

    @Test
    fun toDayUiState_convertsSharedAtToTheDeviceZone() {
        val shared = DayPlan(today, alarmsSetAt = Instant.EPOCH, sharedAt = at(today, 8, 12))
        val state = AppState(anchorDate = today, dayPlans = mapOf(today to shared))

        val ui = state.toDayUiState(now, zone)

        assertThat(ui.sharedAt).isEqualTo(today.atTime(8, 12))
    }

    @Test
    fun toDayUiState_keepsTheDateASharedDayWasSharedOn() {
        // tomorrow's schedule, shared tonight
        val shared = DayPlan(tomorrow, alarmsSetAt = Instant.EPOCH, sharedAt = at(today, 21, 0))
        val state = AppState(anchorDate = today, settledDate = tomorrow, dayPlans = mapOf(tomorrow to shared))

        assertThat(state.toDayUiState(now, zone).sharedAt).isEqualTo(today.atTime(21, 0))
    }

    @Test
    fun toDayUiState_sharedAtIsNullUntilTheDayHasBeenShared() {
        val state = AppState(anchorDate = today, dayPlans = mapOf(today to DayPlan(today, alarmsSetAt = Instant.EPOCH)))

        assertThat(state.toDayUiState(now, zone).sharedAt).isNull()
    }

    @Test
    fun toFabState_hiddenWithNoSelections_setAlarmsWithSome_shareOnceAlarmsAreSet() {
        val noPlan: DayPlan? = null
        assertThat(noPlan.toFabState()).isEqualTo(FabState.Hidden)
        assertThat(DayPlan(today).toFabState()).isEqualTo(FabState.Hidden)

        val selected = DayPlan(today, selected = mapOf(standup.key to standup.toSelectedEventForTest()))
        assertThat(selected.toFabState()).isEqualTo(FabState.SetAlarms(1))

        val armed = selected.copy(alarmsSetAt = Instant.EPOCH)
        assertThat(armed.toFabState()).isEqualTo(FabState.Share())
    }

    @Test
    fun toFabState_syncOnly_dropsTheButtonOnceTheDayIsSynced_butNotInTheModesThatSendText() {
        val armed = DayPlan(today, selected = mapOf(standup.key to standup.toSelectedEventForTest()), alarmsSetAt = Instant.EPOCH)
        assertThat(armed.toFabState(ShareMode.SYNC_ONLY)).isEqualTo(FabState.Share(ShareMode.SYNC_ONLY))

        val synced = armed.copy(sharedAt = Instant.EPOCH)
        assertThat(synced.toFabState(ShareMode.SYNC_ONLY)).isEqualTo(FabState.Synced)
        assertThat(synced.toFabState(ShareMode.SYNC_AND_TEXT)).isEqualTo(FabState.Share(ShareMode.SYNC_AND_TEXT))
        assertThat(synced.toFabState(ShareMode.TEXT)).isEqualTo(FabState.Share(ShareMode.TEXT))

        // changing the picks after the sync clears alarmsSetAt: "Set alarms" comes back
        assertThat(synced.copy(alarmsSetAt = null).toFabState(ShareMode.SYNC_ONLY)).isEqualTo(FabState.SetAlarms(1))
    }

    @Test
    fun toFabState_staysAsClearAlarms_whenEverySelectionIsRemovedButAlarmsAreStillArmed() {
        // the toggle that emptied the selection cleared alarmsSetAt; the scheduled_alarm row is still SCHEDULED
        val deselected = DayPlan(today, selected = emptyMap(), armedKeys = setOf(standup.key), alarmsSetAt = null)
        assertThat(deselected.toFabState()).isEqualTo(FabState.SetAlarms(0))

        // once the reconcile has cancelled it (or it fired) nothing is armed: back to hidden
        assertThat(deselected.copy(armedKeys = emptySet()).toFabState()).isEqualTo(FabState.Hidden)
    }

    @Test
    fun onFabClick_inClearAlarmsState_dispatchesSetAlarmsForTheSettledDay() {
        val dispatched = MutableSharedFlow<Action>(replay = 10)
        val record = SideEffect<AppState> {
            actions.onEach { if (it is SetAlarms) dispatched.emit(it) }.filter { false }
        }
        val deselected = DayPlan(today, armedKeys = setOf(standup.key))
        runStoreTest(
            { createAppStore(this, AppState(anchorDate = today, dayPlans = mapOf(today to deselected)), setOf(record)) },
        ) { store ->
            val viewModel = DayViewModel(store, clock, FakeSettingsRepository())
            assertThat(viewModel.state.value.fabState).isEqualTo(FabState.SetAlarms(0))

            viewModel.onFabClick()

            assertThat(dispatched.first()).isEqualTo(SetAlarms(today))
        }
    }

    @Test
    fun toDayUiState_marksSelectedEventsAndTheirAlarmTime() {
        val state = AppState(
            anchorDate = today,
            eventsByDay = mapOf(today to DayEvents(today, listOf(standup, dentist), loadedAt)),
            dayPlans = mapOf(
                today to DayPlan(
                    today,
                    selected = mapOf(
                        standup.key to SelectedEvent(
                            key = standup.key,
                            title = standup.title,
                            begin = standup.begin,
                            end = standup.end,
                            alarmAt = standup.begin.minusSeconds(300),
                            rsvpState = RsvpState.ACCEPTED_LOCALLY,
                        ),
                    ),
                ),
            ),
        )

        val ui = state.toDayUiState(now, zone)

        assertThat(ui.fabState).isEqualTo(FabState.SetAlarms(1))
        assertThat(ui.timelineFor(today).timedEvents).containsExactly(
            standup.toTimelineEvent(zone, selected = true, alarmAt = LocalTime.of(9, 25), rsvp = ChipRsvp.Sent),
            dentist.toTimelineEvent(zone),
        )
    }

    private fun CalendarEvent.toSelectedEventForTest() = SelectedEvent(
        key = key,
        title = title,
        begin = begin,
        end = end,
    )

    @Test
    fun toTimelineState_dropsTimedEventsThatDontOverlapTheDay() {
        val yesterday = today.minusDays(1)
        val endsAtMidnight = testCalendarEvent(6, at(yesterday, 23), at(today, 0))
        val zeroLengthAtMidnight = testCalendarEvent(7, at(today, 0), at(today, 0))

        val timeline = DayEvents(today, listOf(endsAtMidnight, zeroLengthAtMidnight, standup), loadedAt).toTimelineState(zone, now = null)

        assertThat(timeline.timedEvents).containsExactly(zeroLengthAtMidnight.toTimelineEvent(zone), standup.toTimelineEvent(zone))
        assertThat(timeline.allDayEvents).isEmpty()
    }

    private val sharedStandup = DayPlan(
        date = today,
        selected = mapOf(standup.key to standup.toSelectedEventForTest()),
        alarmsSetAt = Instant.EPOCH,
        sharedAt = Instant.EPOCH,
        sharedSnapshot = listOf(BusyRange(standup.begin, standup.end)),
    )

    @Test
    fun changeBanner_isNull_forADayThatHasntBeenShared() {
        val state = AppState(
            anchorDate = today,
            eventsByDay = mapOf(today to DayEvents(today, listOf(standup), loadedAt)),
            dayPlans = mapOf(today to sharedStandup.copy(sharedAt = null)),
            scheduleChanges = listOf(ScheduleChange.New(today, designReview.key, designReview.begin, designReview.end)),
        )

        assertThat(state.toDayUiState(now, zone).changeBanner).isNull()
    }

    @Test
    fun changeBanner_isNull_whileTheSharedDayStillMatchesTheShare() {
        val state = AppState(
            anchorDate = today,
            eventsByDay = mapOf(today to DayEvents(today, listOf(standup, designReview), loadedAt)),
            dayPlans = mapOf(today to sharedStandup),
            scheduleChanges = listOf(ScheduleChange.New(tomorrow, designReview.key, designReview.begin, designReview.end)),
        )

        assertThat(state.toDayUiState(now, zone).changeBanner).isNull()
    }

    @Test
    fun changeBanner_listsTheSettledDaysRecordedChanges() {
        val new = ScheduleChange.New(today, designReview.key, designReview.begin, designReview.end)
        val moved = ScheduleChange.Moved(today, EventKey(8, 0), at(today, 12), at(today, 13), at(today, 12, 30), at(today, 13, 30))
        val state = AppState(
            anchorDate = today,
            eventsByDay = mapOf(today to DayEvents(today, listOf(standup, designReview), loadedAt)),
            dayPlans = mapOf(today to sharedStandup),
            scheduleChanges = listOf(moved, new),
        )

        assertThat(state.toDayUiState(now, zone).changeBanner).isEqualTo(
            ScheduleChangeBannerState(
                listOf(
                    ScheduleChangeLine.Moved("12:00 – 1:00 PM", "12:30 – 1:30 PM"),
                    ScheduleChangeLine.New("2:00 – 3:00 PM"),
                ),
            ),
        )
    }

    @Test
    fun changeBanner_showsWithNoLines_whenOnlyTheSelectionChangedSinceTheShare() {
        val state = AppState(
            anchorDate = today,
            eventsByDay = mapOf(today to DayEvents(today, listOf(standup, designReview), loadedAt)),
            dayPlans = mapOf(
                today to sharedStandup.copy(selected = sharedStandup.selected + (designReview.key to designReview.toSelectedEventForTest())),
            ),
        )

        assertThat(state.toDayUiState(now, zone).changeBanner).isEqualTo(ScheduleChangeBannerState(emptyList()))
    }

    @Test
    fun changeBanner_waitsForTheDayToLoad_beforeComparingTheSelection() {
        val state = AppState(anchorDate = today, dayPlans = mapOf(today to sharedStandup.copy(selected = emptyMap())))

        assertThat(state.toDayUiState(now, zone).changeBanner).isNull()
    }

    @Test
    fun changeBanner_isNull_forASharedDayThatHasEnded() {
        val yesterday = today.minusDays(1)
        val state = AppState(
            anchorDate = today,
            settledDate = yesterday,
            dayPlans = mapOf(yesterday to sharedStandup.copy(selected = emptyMap())),
            scheduleChanges = listOf(ScheduleChange.New(yesterday, designReview.key, designReview.begin, designReview.end)),
        )

        assertThat(state.toDayUiState(now, zone).changeBanner).isNull()
    }
}

package com.episode6.meetingminder.ui.day

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.prop
import com.episode6.meetingminder.R
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
import com.episode6.meetingminder.store.SetAlarms
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
import kotlinx.coroutines.flow.MutableSharedFlow
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
        val viewModel = DayViewModel(store, clock)
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
            val viewModel = DayViewModel(store, clock)

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
        val viewModel = DayViewModel(store, clock)

        assertThat(viewModel.calendarEventFor(lateShow.key)).isEqualTo(lateShow)
        assertThat(viewModel.calendarEventFor(EventKey(99, 0))).isNull()
    }

    @Test
    fun messages_emitEachMessageOnce_andShowingOneClearsIt() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store, clock)
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
    fun onOpenInCalendarFailed_showsTheNoCalendarAppMessage() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store, clock)
        viewModel.messages.test {
            viewModel.onOpenInCalendarFailed()

            assertThat(awaitItem()).prop(UiMessage::text).isEqualTo(R.string.open_in_calendar_failed)
        }
    }

    @Test
    fun onCheckForUpdatesFailed_showsTheNoBrowserMessage() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store, clock)
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
            val viewModel = DayViewModel(store, clock)

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
            val viewModel = DayViewModel(store, clock)

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
            val viewModel = DayViewModel(store, clock)

            viewModel.onFabClick()

            assertThat(dispatched.first()).isEqualTo(ShareDay(today))
        }
    }

    @Test
    fun onShareAgainClick_dispatchesShareDayForTheSettledDay() {
        val dispatched = MutableSharedFlow<Action>(replay = 10)
        val record = SideEffect<AppState> {
            actions.onEach { if (it is ShareDay) dispatched.emit(it) }.filter { false }
        }
        runStoreTest({ createAppStore(this, AppState(anchorDate = today, settledDate = tomorrow), setOf(record)) }) { store ->
            val viewModel = DayViewModel(store, clock)

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
            val viewModel = DayViewModel(store, clock)

            viewModel.onMarkNotSharedClick()

            assertThat(dispatched.first()).isEqualTo(MarkNotShared(today))
        }
    }

    @Test
    fun onShareLaunched_clearsThePendingShare() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today, pendingShare = PendingShare.next(today, "text")), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store, clock)
        val share = store.state.pendingShare!!

        viewModel.onShareLaunched(share)

        assertThat(store.state.pendingShare).isNull()
    }

    @Test
    fun onFabClick_withNothingSelected_doesNothing() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store, clock)
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

        assertThat(ui.fabState).isEqualTo(FabState.Share)
        assertThat(ui.armedCount).isEqualTo(1)
    }

    @Test
    fun toDayUiState_convertsSharedAtToTheDeviceZone() {
        val shared = DayPlan(today, alarmsSetAt = Instant.EPOCH, sharedAt = at(today, 8, 12))
        val state = AppState(anchorDate = today, dayPlans = mapOf(today to shared))

        val ui = state.toDayUiState(now, zone)

        assertThat(ui.sharedAtTime).isEqualTo(LocalTime.of(8, 12))
    }

    @Test
    fun toDayUiState_sharedAtIsNullUntilTheDayHasBeenShared() {
        val state = AppState(anchorDate = today, dayPlans = mapOf(today to DayPlan(today, alarmsSetAt = Instant.EPOCH)))

        assertThat(state.toDayUiState(now, zone).sharedAtTime).isNull()
    }

    @Test
    fun toFabState_hiddenWithNoSelections_setAlarmsWithSome_shareOnceAlarmsAreSet() {
        val noPlan: DayPlan? = null
        assertThat(noPlan.toFabState()).isEqualTo(FabState.Hidden)
        assertThat(DayPlan(today).toFabState()).isEqualTo(FabState.Hidden)

        val selected = DayPlan(today, selected = mapOf(standup.key to standup.toSelectedEventForTest()))
        assertThat(selected.toFabState()).isEqualTo(FabState.SetAlarms(1))

        val armed = selected.copy(alarmsSetAt = Instant.EPOCH)
        assertThat(armed.toFabState()).isEqualTo(FabState.Share)
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
            val viewModel = DayViewModel(store, clock)
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
}

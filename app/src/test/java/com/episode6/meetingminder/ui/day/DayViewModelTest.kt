package com.episode6.meetingminder.ui.day

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.prop
import com.episode6.meetingminder.R
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.LoadDay
import com.episode6.meetingminder.store.ShowMessage
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
    fun toTimelineState_dropsTimedEventsThatDontOverlapTheDay() {
        val yesterday = today.minusDays(1)
        val endsAtMidnight = testCalendarEvent(6, at(yesterday, 23), at(today, 0))
        val zeroLengthAtMidnight = testCalendarEvent(7, at(today, 0), at(today, 0))

        val timeline = DayEvents(today, listOf(endsAtMidnight, zeroLengthAtMidnight, standup), loadedAt).toTimelineState(zone, now = null)

        assertThat(timeline.timedEvents).containsExactly(zeroLengthAtMidnight.toTimelineEvent(zone), standup.toTimelineEvent(zone))
        assertThat(timeline.allDayEvents).isEmpty()
    }
}

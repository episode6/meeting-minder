package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.data.db.BusyBlockEntity
import com.episode6.meetingminder.data.db.FakeBusyBlockDao
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.permissions.PermissionState
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.LoadDay
import com.episode6.meetingminder.store.ToggleEvent
import com.episode6.meetingminder.store.createAppStore
import com.episode6.redux.testsupport.runStoreTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The end-to-end half of TODO.md §4.7's "hidden from ourselves": a `busy` block the app
 * wrote can't be selected, because `LoadDayEventsSideEffects`' `excludeOwnBlocks` keeps it
 * out of `AppState.eventsByDay` in the first place — and `ToggleEventSideEffects` only ever
 * copies an event it finds there into `selected_event`. Without that, a block on a visible
 * calendar (Family usually is) could be tapped and fold straight back into the next share.
 *
 * Wired like `SelectionPersistenceStoreTest`: the real store, the real effects, fakes for
 * the provider and Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusyBlockHidingStoreTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val loadedAt = Instant.parse("2026-09-14T12:00:00Z")

    private fun at(hour: Int) = Instant.parse("2026-09-14T%02d:00:00Z".format(hour))

    private val standup = testCalendarEvent(1, at(9), at(10), title = "Standup")

    /** Still carrying its `CUSTOM_APP_PACKAGE` marker. */
    private val markedBlock = testCalendarEvent(9, at(9), at(10), title = "busy", meeting = false, ownedByApp = true)

    /** Marker lost on the way through the account's sync adapter; only `busy_block` knows it is ours. */
    private val tabledBlock = testCalendarEvent(10, at(13), at(14), title = "busy", meeting = false)

    @Test
    fun aBusyBlockTheAppWrote_neverReachesTheDay_soItCanNeverBeSelected() {
        val dayPlanDao = FakeDayPlanDao()
        val busyBlocks = FakeBusyBlockDao(
            listOf(
                BusyBlockEntity(
                    eventId = tabledBlock.eventId, date = today, calendarId = tabledBlock.calendarId,
                    beginMillis = tabledBlock.begin.toEpochMilli(), endMillis = tabledBlock.end.toEpochMilli(),
                ),
            ),
        )
        val repository = FakeCalendarRepository(events = mutableMapOf(today to listOf(standup, markedBlock, tabledBlock)))
        val sideEffects = setOf(
            object : LoadDayEventsSideEffects {}.loadDayEvents(
                repository,
                Clock.fixed(loadedAt, ZoneOffset.UTC),
                FakeSettingsRepository(),
                busyBlocks,
            ),
            object : ObserveDayPlansSideEffects {}.observeDayPlans(dayPlanDao, FakeScheduledAlarmDao()),
            object : ToggleEventSideEffects {}.toggleEvent(dayPlanDao),
        )
        val initial = AppState(anchorDate = today, permissions = PermissionState(calendarGranted = true))

        // EmptyCoroutineContext: ObserveDayPlans' `combine` yields internally, which
        // kotlinx-coroutines-test rejects across two schedulers (see SelectionPersistenceStoreTest)
        runStoreTest({ createAppStore(this, initial, sideEffects) }, context = EmptyCoroutineContext) { store ->
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) { store.collect {} }

            store.dispatch(LoadDay(today))
            advanceUntilIdle()

            assertThat(store.state.eventsByDay.getValue(today).events.map { it.eventId }).containsExactly(standup.eventId)

            // the taps a stray chip would produce, if either block had drawn one
            store.dispatch(ToggleEvent(today, markedBlock.key))
            store.dispatch(ToggleEvent(today, tabledBlock.key))
            advanceUntilIdle()

            assertThat(dayPlanDao.selectionsFlow.value).isEmpty()
            assertThat(store.state.dayPlans).isEmpty()

            // the meeting beside them still selects, so this isn't passing for want of a day
            store.dispatch(ToggleEvent(today, standup.key))
            advanceUntilIdle()

            assertThat(store.state.dayPlans.getValue(today).selected.keys.toList()).containsExactly(standup.key)

            collector.cancel()
        }
    }
}

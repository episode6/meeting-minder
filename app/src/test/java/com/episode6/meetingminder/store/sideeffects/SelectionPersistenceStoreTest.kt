package com.episode6.meetingminder.store.sideeffects

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isNull
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.store.ToggleEvent
import com.episode6.meetingminder.store.createAppStore
import com.episode6.redux.testsupport.runStoreTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.coroutines.EmptyCoroutineContext

/**
 * End-to-end pin for TODO.md PR-7's "selection survives ... day paging": dispatching
 * [ToggleEvent] through the real store wiring round-trips via [FakeDayPlanDao] (standing
 * in for Room) and [ObserveDayPlansSideEffects] reflects the write back into
 * `AppState.dayPlans`, rather than only asserting the isolated `output(...)` behaviour of
 * either side effect on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionPersistenceStoreTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val otherDate = today.plusDays(1)
    private val standup = testCalendarEvent(1, Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000), title = "Standup")

    @Test
    fun toggleEvent_selectsThenUnselects_viaTheRealStoreWiring() {
        val dao = FakeDayPlanDao()
        val sideEffects = setOf(
            object : ObserveDayPlansSideEffects {}.observeDayPlans(dao, FakeScheduledAlarmDao()),
            object : ToggleEventSideEffects {}.toggleEvent(dao),
        )
        val initial = com.episode6.meetingminder.store.AppState(
            anchorDate = today,
            eventsByDay = mapOf(today to DayEvents(today, listOf(standup), Instant.EPOCH)),
        )

        // The default `context` (a fresh UnconfinedTestDispatcher) creates a second
        // TestCoroutineScheduler distinct from runTest's own; ObserveDayPlansSideEffects'
        // `combine` internally yields, and kotlinx-coroutines-test rejects that split
        // (`Detected use of different schedulers`). Passing EmptyCoroutineContext keeps the
        // store on the single ambient scheduler runStoreTest's own runTest already provides.
        runStoreTest({ createAppStore(this, initial, sideEffects) }, context = EmptyCoroutineContext) { store ->
            store.test {
                assertThat(awaitItem().dayPlans).isEmpty()

                store.dispatch(ToggleEvent(today, standup.key))

                val selected = awaitItem().dayPlans.getValue(today)
                assertThat(selected.selected.keys.toList()).containsExactly(standup.key)

                store.dispatch(ToggleEvent(today, standup.key))

                // a date with no plan row and no selections doesn't appear in the map at
                // all (DayPlan.kt: "equivalent to DayPlan(date, selected = emptyMap())")
                assertThat(awaitItem().dayPlans).isEmpty()

                // a second date's selection is untouched by toggles on the first
                assertThat(store.state.dayPlans[otherDate]).isNull()
            }
        }
    }
}

package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.toSelectedEventEntity
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.store.ToggleEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ToggleEventSideEffectsTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val standup = testCalendarEvent(1, Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000), title = "Standup")
    private val stateWithStandup = TestAppState.copy(eventsByDay = mapOf(today to DayEvents(today, listOf(standup), Instant.EPOCH)))

    @Test
    fun toggleOfAnUnselectedEvent_insertsItsRow() = runTest {
        val dao = FakeDayPlanDao()
        val sideEffect = object : ToggleEventSideEffects {}.toggleEvent(dao)

        val output = sideEffect.output(ToggleEvent(today, standup.key), state = stateWithStandup).toList()

        assertThat(output).isEmpty()
        assertThat(dao.selectedEventsOn(today)).isEqualTo(listOf(standup.toSelectedEventEntity(today)))
    }

    @Test
    fun toggleOfAnAlreadySelectedEvent_deletesItsRow() = runTest {
        val dao = FakeDayPlanDao(selections = listOf(standup.toSelectedEventEntity(today)))
        val sideEffect = object : ToggleEventSideEffects {}.toggleEvent(dao)

        sideEffect.output(ToggleEvent(today, standup.key), state = stateWithStandup).toList()

        assertThat(dao.selectedEventsOn(today)).isEmpty()
    }

    @Test
    fun toggleOfAKeyNoLongerLoaded_writesNothing() = runTest {
        val dao = FakeDayPlanDao()
        val sideEffect = object : ToggleEventSideEffects {}.toggleEvent(dao)

        sideEffect.output(ToggleEvent(today, EventKey(99, 0)), state = stateWithStandup).toList()

        assertThat(dao.selectedEventsOn(today)).isEmpty()
    }

    @Test
    fun toggleOnADifferentDateThanTheEventIsLoadedOn_writesNothing() = runTest {
        val dao = FakeDayPlanDao()
        val sideEffect = object : ToggleEventSideEffects {}.toggleEvent(dao)

        sideEffect.output(ToggleEvent(today.plusDays(1), standup.key), state = stateWithStandup).toList()

        assertThat(dao.selectedEventsOn(today.plusDays(1))).isEmpty()
    }

    @Test
    fun backToBackTogglesOfTheSameKey_endUnselected() = runTest {
        val dao = FakeDayPlanDao()
        val sideEffect = object : ToggleEventSideEffects {}.toggleEvent(dao)

        // Both toggles race through the same flatMapMerge; DayPlanDao.toggleSelectedEvent's
        // own @Transaction (not a read here + a write there) is what keeps a quick double tap
        // from reading "not selected" twice and inserting twice.
        sideEffect.output(ToggleEvent(today, standup.key), ToggleEvent(today, standup.key), state = stateWithStandup).toList()

        assertThat(dao.selectedEventsOn(today)).isEmpty()
    }
}

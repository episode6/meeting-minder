package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.toSelectedEventEntity
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.RsvpState
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.store.RsvpAccept
import com.episode6.meetingminder.store.RsvpAccepted
import com.episode6.meetingminder.store.RsvpResult
import com.episode6.meetingminder.store.SetDayEvents
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RsvpAcceptSideEffectsTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val standup = testCalendarEvent(1, Instant.parse("2026-09-14T09:00:00Z"), Instant.parse("2026-09-14T09:30:00Z"))
    private val designReview = testCalendarEvent(2, Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-14T11:00:00Z"))
    private val stateWithEvents = TestAppState.copy(
        eventsByDay = mapOf(today to DayEvents(today, listOf(standup, designReview), Instant.EPOCH)),
    )

    private val repository = FakeCalendarRepository()

    private fun rsvpAccept() = object : RsvpAcceptSideEffects {}.rsvpAccept(repository)
    private fun rsvpState(dao: FakeDayPlanDao) = object : RsvpAcceptSideEffects {}.rsvpState(dao)

    @Test
    fun rsvpAccept_writesTheEventFromTheLoadedWindow_andReportsTheIdItWentTo() = runTest {
        repository.rsvpEventIdFor = { 555 }

        val output = rsvpAccept().output(RsvpAccept(today, standup.key), state = stateWithEvents).toList()

        assertThat(repository.accepted).containsExactly(standup)
        assertThat(output).containsExactly(RsvpAccepted(today, standup.key, RsvpResult.Accepted(555)))
    }

    @Test
    fun rsvpAccept_whenTheProviderThrows_reportsFailure_forThatEventOnly() = runTest {
        repository.acceptError = SecurityException("WRITE_CALENDAR revoked")

        val output = rsvpAccept().output(RsvpAccept(today, standup.key), RsvpAccept(today, designReview.key), state = stateWithEvents).toList()

        assertThat(output).containsExactly(
            RsvpAccepted(today, standup.key, RsvpResult.Failed),
            RsvpAccepted(today, designReview.key, RsvpResult.Failed),
        )
    }

    @Test
    fun rsvpAccept_whenTheEventHasLeftTheLoadedWindow_failsWithoutWriting() = runTest {
        val output = rsvpAccept().output(RsvpAccept(today, standup.key), state = TestAppState).toList()

        assertThat(repository.accepted).isEmpty()
        assertThat(output).containsExactly(RsvpAccepted(today, standup.key, RsvpResult.Failed))
    }

    @Test
    fun rsvpAccepted_recordsAcceptedLocally_withTheWrittenEventId() = runTest {
        val dao = FakeDayPlanDao(selections = listOf(standup.toSelectedEventEntity(today).copy(alarmId = 1, rsvpState = RsvpState.PENDING)))

        val output = rsvpState(dao).output(RsvpAccepted(today, standup.key, RsvpResult.Accepted(555))).toList()

        assertThat(output).isEmpty()
        assertThat(dao.selectedEventsOn(today).single()).isEqualTo(
            standup.toSelectedEventEntity(today).copy(alarmId = 1, rsvpState = RsvpState.ACCEPTED_LOCALLY, rsvpEventId = 555),
        )
    }

    @Test
    fun rsvpAccepted_recordsFailure_withNoEventId() = runTest {
        val dao = FakeDayPlanDao(selections = listOf(standup.toSelectedEventEntity(today).copy(rsvpState = RsvpState.PENDING)))

        rsvpState(dao).output(RsvpAccepted(today, standup.key, RsvpResult.Failed)).toList()

        val row = dao.selectedEventsOn(today).single()
        assertThat(row.rsvpState).isEqualTo(RsvpState.FAILED)
        assertThat(row.rsvpEventId).isNull()
    }

    @Test
    fun aReloadThatShowsTheWrittenEventClean_promotesAcceptedLocallyToSynced() = runTest {
        // the standup was answered through a new exception (555); the review's write is still dirty
        val dao = FakeDayPlanDao(
            selections = listOf(
                standup.toSelectedEventEntity(today).copy(rsvpState = RsvpState.ACCEPTED_LOCALLY, rsvpEventId = 555),
                designReview.toSelectedEventEntity(today).copy(rsvpState = RsvpState.ACCEPTED_LOCALLY, rsvpEventId = 2),
            ),
        )
        val reloaded = DayEvents(
            today,
            listOf(standup.copy(eventId = 555, dirty = false), designReview.copy(dirty = true)),
            Instant.EPOCH,
        )

        rsvpState(dao).output(SetDayEvents(reloaded)).toList()

        val rows = dao.selectedEventsOn(today).associateBy { it.eventId }
        assertThat(rows.getValue(standup.eventId).rsvpState).isEqualTo(RsvpState.SYNCED)
        assertThat(rows.getValue(standup.eventId).rsvpEventId).isEqualTo(555)
        assertThat(rows.getValue(designReview.eventId).rsvpState).isEqualTo(RsvpState.ACCEPTED_LOCALLY)
    }

    @Test
    fun aReload_leavesEveryOtherRsvpStateAlone() = runTest {
        val dao = FakeDayPlanDao(
            selections = listOf(
                standup.toSelectedEventEntity(today).copy(rsvpState = RsvpState.PENDING),
                designReview.toSelectedEventEntity(today).copy(rsvpState = RsvpState.FAILED),
            ),
        )

        rsvpState(dao).output(SetDayEvents(DayEvents(today, listOf(standup, designReview), Instant.EPOCH))).toList()

        assertThat(dao.selectedEventsOn(today).map { it.rsvpState }).containsExactly(RsvpState.PENDING, RsvpState.FAILED)
    }
}

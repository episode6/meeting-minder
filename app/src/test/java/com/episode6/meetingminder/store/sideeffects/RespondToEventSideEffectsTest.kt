package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.EventResponse
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.RespondToEvent
import com.episode6.meetingminder.store.RsvpAccepted
import com.episode6.meetingminder.store.RsvpResult
import com.episode6.meetingminder.store.ShowMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RespondToEventSideEffectsTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val standup = testCalendarEvent(1, Instant.parse("2026-09-14T09:00:00Z"), Instant.parse("2026-09-14T09:30:00Z"))
    private val soloBlock = testCalendarEvent(2, Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-14T11:00:00Z"), meeting = false)
    private val stateWithEvents = TestAppState.copy(
        eventsByDay = mapOf(today to DayEvents(today, listOf(standup, soloBlock), Instant.EPOCH)),
    )

    private val repository = FakeCalendarRepository()

    private fun respondToEvent() = object : RespondToEventSideEffects {}.respondToEvent(repository)

    @Test
    fun aNo_writesTheAnswer_confirms_andReloadsTheDay() = runTest {
        val output = respondToEvent().output(RespondToEvent(today, standup.key, EventResponse.NO), state = stateWithEvents).toList()

        assertThat(repository.responses).containsExactly(standup to EventResponse.NO)
        assertThat(output.map { it::class }).containsExactly(ShowMessage::class, CalendarContentChanged::class)
        assertThat((output.first() as ShowMessage).message.text).isEqualTo(R.string.respond_sent_no)
    }

    @Test
    fun aYes_alsoReportsRsvpAccepted_soAnArmedSelectionGetsItsTick() = runTest {
        repository.rsvpEventIdFor = { 555 }

        val output = respondToEvent().output(RespondToEvent(today, standup.key, EventResponse.YES), state = stateWithEvents).toList()

        assertThat(repository.responses).containsExactly(standup to EventResponse.YES)
        assertThat(output.first()).isEqualTo(RsvpAccepted(today, standup.key, RsvpResult.Accepted(555)))
        assertThat(output.drop(1).map { it::class }).containsExactly(ShowMessage::class, CalendarContentChanged::class)
        assertThat((output[1] as ShowMessage).message.text).isEqualTo(R.string.respond_sent_yes)
    }

    @Test
    fun aMaybe_confirmsAsMaybe_withoutAnRsvpReport() = runTest {
        val output = respondToEvent().output(RespondToEvent(today, standup.key, EventResponse.MAYBE), state = stateWithEvents).toList()

        assertThat(repository.responses).containsExactly(standup to EventResponse.MAYBE)
        assertThat(output.map { it::class }).containsExactly(ShowMessage::class, CalendarContentChanged::class)
        assertThat((output.first() as ShowMessage).message.text).isEqualTo(R.string.respond_sent_maybe)
    }

    @Test
    fun anEventTheMenuShouldNotHaveOffered_isRefusedWithoutWriting() = runTest {
        // a solo block has no self-attendee row: the exception insert would crash the provider
        val output = respondToEvent().output(RespondToEvent(today, soloBlock.key, EventResponse.YES), state = stateWithEvents).toList()

        assertThat(repository.responses).isEmpty()
        assertThat(output.map { it::class }).containsExactly(ShowMessage::class)
        assertThat((output.single() as ShowMessage).message.text).isEqualTo(R.string.respond_failed)
    }

    @Test
    fun anEventThatHasLeftTheLoadedWindow_isRefusedWithoutWriting() = runTest {
        val output = respondToEvent().output(RespondToEvent(today, standup.key, EventResponse.NO), state = TestAppState).toList()

        assertThat(repository.responses).isEmpty()
        assertThat((output.single() as ShowMessage).message.text).isEqualTo(R.string.respond_failed)
    }

    @Test
    fun aProviderFailure_isOneSnackbar_andNoReload() = runTest {
        repository.acceptError = SecurityException("WRITE_CALENDAR revoked")

        val output = respondToEvent().output(RespondToEvent(today, standup.key, EventResponse.NO), state = stateWithEvents).toList()

        assertThat(output.map { it::class }).containsExactly(ShowMessage::class)
        assertThat((output.single() as ShowMessage).message.text).isEqualTo(R.string.respond_failed)
    }
}

package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.episode6.meetingminder.data.calendar.CalendarFilter
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.LoadDay
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.SetDayEvents
import com.episode6.meetingminder.store.SetSettledDate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class LoadDayEventsSideEffectsTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val loadedAt = Instant.parse("2026-09-14T12:00:00Z")
    private val standup = testCalendarEvent(1, Instant.parse("2026-09-14T13:00:00Z"), Instant.parse("2026-09-14T13:30:00Z"))
    private val dentist = testCalendarEvent(2, Instant.parse("2026-09-15T16:00:00Z"), Instant.parse("2026-09-15T17:00:00Z"))
    private val repository = FakeCalendarRepository(events = mutableMapOf(today to listOf(standup), today.plusDays(1) to listOf(dentist)))
    private val sideEffect = object : LoadDayEventsSideEffects {}.loadDayEvents(repository, Clock.fixed(loadedAt, ZoneOffset.UTC))

    @Test
    fun loadDay_loadsThatDayFirst_thenTheDayEitherSide() = runTest {
        val output = sideEffect.output(LoadDay(today), state = CalendarGrantedAppState).toList()

        assertThat(output).containsExactly(
            SetDayEvents(DayEvents(today, listOf(standup), loadedAt)),
            SetDayEvents(DayEvents(today.plusDays(1), listOf(dentist), loadedAt)),
            SetDayEvents(DayEvents(today.minusDays(1), emptyList(), loadedAt)),
        )
        assertThat(repository.eventQueries).containsExactly(
            today to CalendarFilter.Visible,
            today.plusDays(1) to CalendarFilter.Visible,
            today.minusDays(1) to CalendarFilter.Visible,
        )
    }

    @Test
    fun calendarContentChanged_reloadsTheWindowAroundTheSettledDate() = runTest {
        val state = CalendarGrantedAppState.copy(settledDate = today.plusDays(1))

        val output = sideEffect.output(CalendarContentChanged, state = state).toList()

        assertThat(output).containsExactly(
            SetDayEvents(DayEvents(today.plusDays(1), listOf(dentist), loadedAt)),
            SetDayEvents(DayEvents(today.plusDays(2), emptyList(), loadedAt)),
            SetDayEvents(DayEvents(today, listOf(standup), loadedAt)),
        )
    }

    @Test
    fun withoutCalendarAccess_nothingIsRead() = runTest {
        val output = sideEffect.output(LoadDay(today), CalendarContentChanged, state = TestAppState).toList()

        assertThat(output).isEmpty()
        assertThat(repository.eventQueries).isEmpty()
    }

    @Test
    fun securityException_asksForAPermissionRecheck() = runTest {
        repository.error = SecurityException("calendar access revoked")

        val output = sideEffect.output(LoadDay(today), state = CalendarGrantedAppState).toList()

        assertThat(output).containsExactly(PermissionsMaybeChanged)
    }

    @Test
    fun otherActions_areIgnored() = runTest {
        val output = sideEffect.output(SetSettledDate(today), state = CalendarGrantedAppState).toList()

        assertThat(output).isEmpty()
        assertThat(repository.eventQueries).isEmpty()
    }
}

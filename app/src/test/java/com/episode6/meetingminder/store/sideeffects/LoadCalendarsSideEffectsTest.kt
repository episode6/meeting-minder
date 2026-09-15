package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.LoadDay
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.SetCalendars
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

class LoadCalendarsSideEffectsTest {

    private val work = CalendarInfo(
        id = 1,
        accountName = "me@work.com",
        accountType = "com.google",
        displayName = "Work",
        color = 0xFFE65C00.toInt(),
        visible = true,
        syncEvents = true,
        ownerAccount = "me@work.com",
        isPrimary = true,
        accessLevel = 700,
        canOrganizerRespond = false,
    )
    private val repository = FakeCalendarRepository(calendars = listOf(work))
    private val sideEffect = object : LoadCalendarsSideEffects {}.loadCalendars(repository)
    private val today = LocalDate.of(2026, 9, 14)

    @Test
    fun calendarContentChanged_alwaysRereadsTheCalendars() = runTest {
        val state = CalendarGrantedAppState.copy(calendars = listOf(work.copy(displayName = "Old name")))

        val output = sideEffect.output(CalendarContentChanged, state = state).toList()

        assertThat(output).containsExactly(SetCalendars(listOf(work)))
    }

    @Test
    fun loadDay_readsTheCalendarsOnlyWhileNoneAreLoaded() = runTest {
        assertThat(sideEffect.output(LoadDay(today), state = CalendarGrantedAppState).toList())
            .containsExactly(SetCalendars(listOf(work)))
        assertThat(sideEffect.output(LoadDay(today), state = CalendarGrantedAppState.copy(calendars = listOf(work))).toList())
            .isEmpty()
    }

    @Test
    fun withoutCalendarAccess_nothingIsRead() = runTest {
        val output = sideEffect.output(LoadDay(today), CalendarContentChanged, state = TestAppState).toList()

        assertThat(output).isEmpty()
    }

    @Test
    fun securityException_asksForAPermissionRecheck() = runTest {
        repository.error = SecurityException("calendar access revoked")

        val output = sideEffect.output(CalendarContentChanged, state = CalendarGrantedAppState).toList()

        assertThat(output).containsExactly(PermissionsMaybeChanged)
    }
}

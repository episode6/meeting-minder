package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.data.calendar.CalendarFilter
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.data.db.BusyBlockEntity
import com.episode6.meetingminder.data.db.FakeBusyBlockDao
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.Settings
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.SelfStatus
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
    private val hiddenCalendar = CalendarInfo(
        id = 9L,
        accountName = "me@work.com",
        accountType = "com.google",
        displayName = "Hidden",
        color = 0,
        visible = false,
        syncEvents = true,
        ownerAccount = "me@work.com",
        isPrimary = false,
        accessLevel = 700,
        canOrganizerRespond = false,
    )
    private val repository = FakeCalendarRepository(events = mutableMapOf(today to listOf(standup), today.plusDays(1) to listOf(dentist)))
    private val settings = FakeSettingsRepository()
    private val busyBlocks = FakeBusyBlockDao()
    private val sideEffect = object : LoadDayEventsSideEffects {}.loadDayEvents(repository, Clock.fixed(loadedAt, ZoneOffset.UTC), settings, busyBlocks)

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
    fun calendarOverrides_narrowTheQueryToTheEffectiveCalendars() = runTest {
        settings.settings.value = Settings(calendarOverrides = mapOf(9L to true))
        repository.calendars = listOf(hiddenCalendar)
        val state = CalendarGrantedAppState.copy(calendars = listOf(hiddenCalendar))

        sideEffect.output(LoadDay(today), state = state).toList()

        assertThat(repository.eventQueries).containsExactly(
            today to CalendarFilter.Only(setOf(9L)),
            today.plusDays(1) to CalendarFilter.Only(setOf(9L)),
            today.minusDays(1) to CalendarFilter.Only(setOf(9L)),
        )
    }

    @Test
    fun calendarOverrides_withStateCalendarsStillEmpty_readsTheCalendarListFromTheProvider() = runTest {
        // a cold process: LoadCalendarsSideEffects' SetCalendars hasn't landed yet, so
        // state.calendars is empty even though a calendar override is stored
        settings.settings.value = Settings(calendarOverrides = mapOf(9L to true))
        repository.calendars = listOf(hiddenCalendar)
        repository.events[today] = listOf(standup.copy(calendarId = hiddenCalendar.id))

        val output = sideEffect.output(LoadDay(today), state = CalendarGrantedAppState).toList()

        assertThat(repository.eventQueries).containsExactly(
            today to CalendarFilter.Only(setOf(9L)),
            today.plusDays(1) to CalendarFilter.Only(setOf(9L)),
            today.minusDays(1) to CalendarFilter.Only(setOf(9L)),
        )
        // the whole point: with the provider read as the fallback, the window isn't empty
        assertThat(output.first()).isEqualTo(SetDayEvents(DayEvents(today, listOf(standup.copy(calendarId = hiddenCalendar.id)), loadedAt)))
    }

    @Test
    fun showDeclinedOff_dropsDeclinedEvents() = runTest {
        val declined = standup.copy(selfStatus = SelfStatus.DECLINED)
        repository.events[today] = listOf(standup, declined)
        settings.settings.value = Settings(showDeclined = false)

        val output = sideEffect.output(LoadDay(today), state = CalendarGrantedAppState).toList()

        assertThat(output.first()).isEqualTo(SetDayEvents(DayEvents(today, listOf(standup), loadedAt)))
    }

    @Test
    fun otherActions_areIgnored() = runTest {
        val output = sideEffect.output(SetSettledDate(today), state = CalendarGrantedAppState).toList()

        assertThat(output).isEmpty()
        assertThat(repository.eventQueries).isEmpty()
    }
}

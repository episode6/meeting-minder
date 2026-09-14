package com.episode6.meetingminder.store

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.permissions.PermissionState
import com.episode6.redux.Action
import com.episode6.redux.subscriberaware.SubscriberStatusChanged
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AppStoreReducerTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val state = AppState(anchorDate = today)
    private val message = UiMessage(id = 7, text = 42)

    @Test
    fun initialState_settlesOnTheAnchorDate() {
        assertThat(state.settledDate).isEqualTo(today)
        assertThat(state.transientMessage).isNull()
    }

    @Test
    fun setSettledDate_movesOnlyTheSettledDate() {
        val result = state.reduce(SetSettledDate(today.plusDays(2)))

        assertThat(result).isEqualTo(state.copy(settledDate = today.plusDays(2)))
    }

    @Test
    fun setPermissions_replacesTheWholePermissionState() {
        val granted = PermissionState(calendarGranted = true)

        val result = state.reduce(SetPermissions(granted))

        assertThat(result).isEqualTo(state.copy(permissions = granted))
    }

    @Test
    fun setCalendars_replacesTheCalendarList() {
        val calendar = CalendarInfo(
            id = 1, accountName = "a", accountType = "LOCAL", displayName = "Cal", color = 0, visible = true,
            syncEvents = true, ownerAccount = "a", isPrimary = true, accessLevel = 700, canOrganizerRespond = false,
        )

        val result = state.reduce(SetCalendars(listOf(calendar)))

        assertThat(result).isEqualTo(state.copy(calendars = listOf(calendar)))
    }

    @Test
    fun setDayEvents_storesTheDay() {
        val result = state.reduce(SetDayEvents(dayEvents(today))).reduce(SetDayEvents(dayEvents(today.plusDays(1))))

        assertThat(result.eventsByDay).isEqualTo(
            mapOf(today to dayEvents(today), today.plusDays(1) to dayEvents(today.plusDays(1))),
        )
    }

    @Test
    fun setDayEvents_replacesAnEarlierLoadOfTheSameDay() {
        val reloaded = dayEvents(today).copy(loadedAt = Instant.EPOCH.plusSeconds(60))

        val result = state.reduce(SetDayEvents(dayEvents(today))).reduce(SetDayEvents(reloaded))

        assertThat(result.eventsByDay).isEqualTo(mapOf(today to reloaded))
    }

    @Test
    fun setDayEvents_dropsDaysOutsideTheSettledWindow() {
        val loaded = state
            .reduce(SetDayEvents(dayEvents(today.minusDays(1))))
            .reduce(SetDayEvents(dayEvents(today)))
            .reduce(SetSettledDate(today.plusDays(1)))

        // a late result for a day the pager has left behind is ignored, and the stale
        // yesterday falls out of the window as the new day lands
        val result = loaded
            .reduce(SetDayEvents(dayEvents(today.minusDays(2))))
            .reduce(SetDayEvents(dayEvents(today.plusDays(2))))

        assertThat(result.eventsByDay).isEqualTo(mapOf(today to dayEvents(today), today.plusDays(2) to dayEvents(today.plusDays(2))))
    }

    private fun dayEvents(date: LocalDate) = DayEvents(date, emptyList(), Instant.EPOCH)

    @Test
    fun showMessage_replacesAnyPendingMessage() {
        val newer = UiMessage(id = 8, text = 43)

        val result = state.reduce(ShowMessage(message)).reduce(ShowMessage(newer))

        assertThat(result.transientMessage).isEqualTo(newer)
    }

    @Test
    fun clearMessage_clearsTheMatchingMessage() {
        val result = state.copy(transientMessage = message).reduce(ClearMessage(message.id))

        assertThat(result.transientMessage).isNull()
    }

    @Test
    fun clearMessage_forAnOlderId_keepsTheNewerMessage() {
        val showing = state.copy(transientMessage = message)

        val result = showing.reduce(ClearMessage(id = 6))

        assertThat(result).isSameInstanceAs(showing)
    }

    @Test
    fun nonUpdateStateActions_leaveStateUntouched() {
        assertThat(state.reduce(object : Action {})).isSameInstanceAs(state)
        assertThat(state.reduce(SubscriberStatusChanged(true))).isSameInstanceAs(state)
    }

    @Test
    fun uiMessageNext_handsOutIncreasingIds() {
        val first = UiMessage.next(1)
        val second = UiMessage.next(1, "arg")

        assertThat(second.id).isEqualTo(first.id + 1)
        assertThat(second.formatArgs).isEqualTo(listOf<Any>("arg"))
    }
}

package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.calendar.CalendarSyncRequester
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.RefreshCalendars
import com.episode6.meetingminder.store.ShowMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RefreshCalendarsSideEffectsTest {

    private var syncRequests = 0
    private val syncRequester = CalendarSyncRequester { syncRequests++ }

    private fun refreshCalendars() = object : RefreshCalendarsSideEffects {}.refreshCalendars(syncRequester)

    @Test
    fun refresh_requestsASync_saysSo_andReloadsTheWindow() = runTest {
        val output = refreshCalendars().output(RefreshCalendars).toList()

        assertThat(syncRequests).isEqualTo(1)
        assertThat(output.map { it::class }).containsExactly(ShowMessage::class, CalendarContentChanged::class)
        assertThat((output.first() as ShowMessage).message.text).isEqualTo(R.string.refresh_started)
    }

    @Test
    fun everyTap_isItsOwnRequest() = runTest {
        refreshCalendars().output(RefreshCalendars, RefreshCalendars).toList()

        assertThat(syncRequests).isEqualTo(2)
    }
}

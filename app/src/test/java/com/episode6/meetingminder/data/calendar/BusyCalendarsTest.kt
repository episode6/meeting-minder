package com.episode6.meetingminder.data.calendar

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.episode6.meetingminder.data.settings.BusySync
import com.episode6.meetingminder.model.CalendarInfo
import org.junit.Test

class BusyCalendarsTest {

    private fun calendar(
        id: Long,
        displayName: String,
        syncEvents: Boolean = true,
        accessLevel: Int = 700,
    ) = CalendarInfo(
        id = id,
        accountName = "me@example.com",
        accountType = "com.google",
        displayName = displayName,
        color = 0,
        visible = true,
        syncEvents = syncEvents,
        ownerAccount = "me@example.com",
        isPrimary = false,
        accessLevel = accessLevel,
        canOrganizerRespond = false,
    )

    @Test
    fun writable_keepsSyncingCalendarsAtContributorOrBetter() {
        val syncOff = calendar(1, "Work", syncEvents = false)
        val respondOnly = calendar(2, "Respond", accessLevel = 300)
        val contributor = calendar(3, "Contributor", accessLevel = 500)
        val owner = calendar(4, "Owner", accessLevel = 700)

        assertThat(listOf(syncOff, respondOnly, contributor, owner).writable()).containsExactly(contributor, owner)
    }

    @Test
    fun defaultBusyCalendar_findsFamilyCaseAndWhitespaceInsensitively() {
        val family = calendar(1, "  FAMILY  ")

        assertThat(defaultBusyCalendar(listOf(family))).isEqualTo(family)
    }

    @Test
    fun defaultBusyCalendar_skipsAReadOnlyFamilyCalendar() {
        val readOnlyFamily = calendar(1, "Family", accessLevel = 300)

        assertThat(defaultBusyCalendar(listOf(readOnlyFamily))).isNull()
    }

    @Test
    fun defaultBusyCalendar_withTwoFamilyCalendars_theFirstInProviderOrderWins() {
        val first = calendar(1, "Family")
        val second = calendar(2, "Family")

        assertThat(defaultBusyCalendar(listOf(first, second))).isEqualTo(first)
    }

    @Test
    fun defaultBusyCalendar_withNoFamilyCalendar_isNull() {
        val work = calendar(1, "Work")

        assertThat(defaultBusyCalendar(listOf(work))).isNull()
    }

    @Test
    fun effectiveBusyCalendar_offOrUnsetOrGone_isNull() {
        val family = calendar(1, "Family")

        assertThat(effectiveBusyCalendar(BusySync(enabled = false, calendarId = 1L), listOf(family))).isNull()
        assertThat(effectiveBusyCalendar(BusySync(enabled = true, calendarId = null), listOf(family))).isNull()
        assertThat(effectiveBusyCalendar(BusySync(enabled = true, calendarId = 99L), listOf(family))).isNull()
    }

    @Test
    fun effectiveBusyCalendar_resolvesTheStoredIdWhenEnabledAndWritable() {
        val family = calendar(1, "Family")

        assertThat(effectiveBusyCalendar(BusySync(enabled = true, calendarId = 1L), listOf(family))).isEqualTo(family)
    }

    @Test
    fun effectiveBusyCalendar_isNullWhenTheStoredIdIsNoLongerWritable() {
        val readOnlyNow = calendar(1, "Family", accessLevel = 300)

        assertThat(effectiveBusyCalendar(BusySync(enabled = true, calendarId = 1L), listOf(readOnlyNow))).isNull()
    }
}

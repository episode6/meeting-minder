package com.episode6.meetingminder.data.calendar

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.SelfStatus
import com.episode6.meetingminder.model.testCalendarEvent
import org.junit.Test
import java.time.Instant

class EffectiveCalendarsTest {

    private fun calendar(id: Long, visible: Boolean) = CalendarInfo(
        id = id,
        accountName = "me@example.com",
        accountType = "com.google",
        displayName = "Calendar $id",
        color = 0,
        visible = visible,
        syncEvents = true,
        ownerAccount = "me@example.com",
        isPrimary = false,
        accessLevel = 700,
        canOrganizerRespond = true,
    )

    @Test
    fun noOverrides_isPlainCalendarFilterVisible_soCalendarsNeverHaveToBeLoadedFirst() {
        val filter = effectiveCalendarFilter(calendars = emptyList(), overrides = emptyMap())

        assertThat(filter).isEqualTo(CalendarFilter.Visible)
    }

    @Test
    fun overrides_forceIncludeAHiddenCalendar_andForceExcludeAVisibleOne() {
        val calendars = listOf(calendar(1, visible = false), calendar(2, visible = true), calendar(3, visible = true))

        val filter = effectiveCalendarFilter(calendars, overrides = mapOf(1L to true, 2L to false))

        assertThat(filter).isEqualTo(CalendarFilter.Only(setOf(1L, 3L)))
    }

    @Test
    fun excludeDeclined_off_dropsOnlyDeclinedEvents() {
        val accepted = testCalendarEvent(1, Instant.EPOCH, Instant.EPOCH.plusSeconds(1800))
        val declined = testCalendarEvent(2, Instant.EPOCH, Instant.EPOCH.plusSeconds(1800)).copy(selfStatus = SelfStatus.DECLINED)

        assertThat(listOf(accepted, declined).excludeDeclined(showDeclined = true)).containsExactly(accepted, declined)
        assertThat(listOf(accepted, declined).excludeDeclined(showDeclined = false)).containsExactly(accepted)
    }
}

package com.episode6.meetingminder.ui.day

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.model.testCalendarEvent
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DayPagerTest {

    private val anchor = LocalDate.of(2026, 9, 14)
    private val zone = ZoneId.of("America/New_York")

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant()

    @Test
    fun anchorPage_isTheAnchorDate_andNeighboursAreAdjacentDays() {
        assertThat(pageToDate(DayViewDefaults.PagerAnchorPage, anchor)).isEqualTo(anchor)
        assertThat(pageToDate(DayViewDefaults.PagerAnchorPage + 1, anchor)).isEqualTo(anchor.plusDays(1))
        assertThat(pageToDate(DayViewDefaults.PagerAnchorPage - 1, anchor)).isEqualTo(anchor.minusDays(1))
    }

    @Test
    fun dateToPage_roundTripsAcrossMonthAndYearBoundaries() {
        listOf(anchor, anchor.plusDays(17), anchor.minusDays(400), LocalDate.of(2027, 1, 1)).forEach { date ->
            assertThat(pageToDate(dateToPage(date, anchor), anchor)).isEqualTo(date)
        }
    }

    @Test
    fun dateToPage_clampsToThePagerRange() {
        assertThat(dateToPage(anchor.plusYears(100), anchor)).isEqualTo(DayViewDefaults.PagerPageCount - 1)
        assertThat(dateToPage(anchor.minusYears(100), anchor)).isEqualTo(0)
    }

    @Test
    fun initialHour_isAnHourBeforeTheFirstMeeting() {
        val events = listOf(
            testCalendarEvent(1, at(anchor, 14), at(anchor, 15)),
            testCalendarEvent(2, at(anchor, 9, 30), at(anchor, 10)),
        )

        assertThat(initialFirstVisibleHour(anchor, events, zone)).isEqualTo(8.5f)
    }

    @Test
    fun initialHour_ignoresSoloBlocksAndMeetingsThatStartedOnAnotherDay() {
        val events = listOf(
            testCalendarEvent(1, at(anchor, 6), at(anchor, 7), meeting = false),
            testCalendarEvent(2, at(anchor.minusDays(1), 23), at(anchor, 1)),
        )

        assertThat(initialFirstVisibleHour(anchor, events, zone)).isEqualTo(DayViewDefaults.DefaultFirstVisibleHour.toFloat())
    }

    @Test
    fun initialHour_neverScrollsAboveMidnight() {
        val events = listOf(testCalendarEvent(1, at(anchor, 0, 30), at(anchor, 1)))

        assertThat(initialFirstVisibleHour(anchor, events, zone)).isEqualTo(0f)
    }
}

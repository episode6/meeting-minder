package com.episode6.meetingminder.ui.day

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** How the long-press sheet classifies an event's times ([eventWhen]). */
class EventWhenTest {

    private val day = LocalDate.of(2026, 9, 14)

    @Test
    fun aTimedEventInsideOneDay_isSameDay() {
        assertThat(eventWhen(day.atTime(10, 0), day.atTime(11, 0), allDay = false))
            .isEqualTo(EventWhen.SameDay(day, LocalTime.of(10, 0), LocalTime.of(11, 0)))
    }

    @Test
    fun aTimedEventEndingAtTheNextMidnight_isStillSameDay() {
        assertThat(eventWhen(day.atTime(23, 0), day.plusDays(1).atStartOfDay(), allDay = false))
            .isEqualTo(EventWhen.SameDay(day, LocalTime.of(23, 0), LocalTime.MIDNIGHT))
    }

    @Test
    fun aTimedEventCrossingMidnight_isSpanning() {
        assertThat(eventWhen(day.atTime(23, 0), day.plusDays(1).atTime(1, 0), allDay = false))
            .isEqualTo(EventWhen.Spanning(day.atTime(23, 0), day.plusDays(1).atTime(1, 0)))
    }

    @Test
    fun aTimedEventOverSeveralDays_isSpanning() {
        assertThat(eventWhen(day.atTime(10, 0), day.plusDays(2).atTime(11, 0), allDay = false))
            .isEqualTo(EventWhen.Spanning(day.atTime(10, 0), day.plusDays(2).atTime(11, 0)))
    }

    @Test
    fun aOneDayAllDayEvent_endsOnItsOwnDay() {
        assertThat(eventWhen(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), allDay = true))
            .isEqualTo(EventWhen.AllDay(day, day))
    }

    @Test
    fun aMultiDayAllDayEvent_endsTheDayBeforeItsExclusiveEnd() {
        assertThat(eventWhen(day.minusDays(1).atStartOfDay(), day.plusDays(4).atStartOfDay(), allDay = true))
            .isEqualTo(EventWhen.AllDay(day.minusDays(1), day.plusDays(3)))
    }

    @Test
    fun aZeroLengthAllDayEvent_neverEndsBeforeItBegins() {
        assertThat(eventWhen(day.atStartOfDay(), day.atStartOfDay(), allDay = true))
            .isEqualTo(EventWhen.AllDay(day, day))
    }
}

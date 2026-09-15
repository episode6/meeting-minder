package com.episode6.meetingminder.share

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.model.BusyRange
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Pure logic, no Android (TODO.md §3.6): merging, AM/PM elision, empty day, midnight-spanning. */
class ScheduleTextFormatterTest {

    private val zone = ZoneOffset.UTC
    private val date = LocalDate.of(2026, 9, 14)

    private fun at(hour: Int, minute: Int = 0, day: LocalDate = date) = day.atTime(hour, minute).toInstant(zone)

    private fun range(beginHour: Int, beginMinute: Int, endHour: Int, endMinute: Int) =
        BusyRange(at(beginHour, beginMinute), at(endHour, endMinute))

    @Test
    fun merge_combinesAdjacentAndOverlappingRanges() {
        val adjacent = listOf(range(9, 0, 9, 30), range(9, 30, 10, 0))
        val overlapping = listOf(range(10, 0, 11, 0), range(10, 30, 11, 30))
        val disjoint = listOf(range(9, 0, 9, 30), range(10, 0, 10, 30))

        assertThat(ScheduleTextFormatter.merge(adjacent)).containsExactly(range(9, 0, 10, 0))
        assertThat(ScheduleTextFormatter.merge(overlapping)).containsExactly(range(10, 0, 11, 30))
        assertThat(ScheduleTextFormatter.merge(disjoint)).containsExactly(range(9, 0, 9, 30), range(10, 0, 10, 30))
    }

    @Test
    fun merge_isOrderIndependentAndSortsTheResult() {
        val out = ScheduleTextFormatter.merge(listOf(range(12, 0, 13, 0), range(9, 0, 9, 30)))

        assertThat(out).containsExactly(range(9, 0, 9, 30), range(12, 0, 13, 0))
    }

    @Test
    fun format_listsMergedRangesWithAmPmElision() {
        val text = ScheduleTextFormatter.format(date, listOf(range(9, 0, 9, 30), range(9, 30, 10, 0), range(12, 0, 13, 0)), zone)

        assertThat(text).isEqualTo(
            "Mon Sep 14 — I'm in meetings:\n" +
                "• 9:00 – 10:00 AM\n" +
                "• 12:00 – 1:00 PM\n" +
                "Free the rest of the day.",
        )
    }

    @Test
    fun format_showsBothPeriodsWhenARangeCrossesNoonOrMidnight() {
        val crossesNoon = ScheduleTextFormatter.format(date, listOf(range(11, 30, 12, 30)), zone)
        assertThat(crossesNoon).contains("• 11:30 AM – 12:30 PM")

        val overnight = BusyRange(at(23, 0), at(1, 0, date.plusDays(1)))
        val crossesMidnight = ScheduleTextFormatter.format(date, listOf(overnight), zone)
        assertThat(crossesMidnight).contains("• 11:00 PM – 1:00 AM")
    }

    @Test
    fun format_emptyDay_saysNoMeetingsToday() {
        val text = ScheduleTextFormatter.format(date, emptyList(), zone)

        assertThat(text).isEqualTo("Mon Sep 14 — I'm in meetings:\nNo meetings today.")
    }

    @Test
    fun format_update_prefixesUpdateAndListsOnlyTheRanges() {
        val text = ScheduleTextFormatter.format(date, listOf(range(9, 0, 9, 30)), zone, isUpdate = true)

        assertThat(text).isEqualTo("Update:\n• 9:00 – 9:30 AM")
    }

    @Test
    fun format_convertsTimesIntoTheGivenZone() {
        val range = BusyRange(Instant.parse("2026-09-14T13:00:00Z"), Instant.parse("2026-09-14T14:00:00Z"))

        val eastern = ScheduleTextFormatter.format(date, listOf(range), ZoneOffset.ofHours(-4))

        assertThat(eastern).contains("• 9:00 – 10:00 AM")
    }
}

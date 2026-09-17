package com.episode6.meetingminder.share

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.data.db.BusyBlockEntity
import com.episode6.meetingminder.model.BusyRange
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One case per rule of [reconcileBusyBlocks] (TODO.md §4.7): exact-instant matching, keep / delete / insert. */
class BusyBlockReconcilerTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val family = 7L
    private val other = 8L

    private val ten = Instant.parse("2026-09-14T14:00:00Z")
    private val eleven = Instant.parse("2026-09-14T15:00:00Z")
    private val noon = Instant.parse("2026-09-14T16:00:00Z")
    private val one = Instant.parse("2026-09-14T17:00:00Z")

    private fun row(eventId: Long, begin: Instant, end: Instant, calendarId: Long = family) =
        BusyBlockEntity(eventId = eventId, date = today, calendarId = calendarId, beginMillis = begin.toEpochMilli(), endMillis = end.toEpochMilli())

    @Test
    fun aRowWhoseTimesEqualADesiredRange_isKept_andTheRangeIsNotInsertedAgain() {
        val kept = row(1, ten, eleven)

        val plan = reconcileBusyBlocks(existing = listOf(kept), desired = listOf(BusyRange(ten, eleven)), calendarId = family)

        assertThat(plan).isEqualTo(BusyBlockPlan(keep = listOf(kept), delete = emptyList(), insert = emptyList()))
    }

    @Test
    fun aRowWhoseTimesDiffer_evenByAMinute_isDeletedAndTheRangeInserted_neverUpdated() {
        val stale = row(1, ten, eleven)
        val movedByAMinute = BusyRange(ten.plusSeconds(60), eleven)

        val plan = reconcileBusyBlocks(existing = listOf(stale), desired = listOf(movedByAMinute), calendarId = family)

        assertThat(plan).isEqualTo(BusyBlockPlan(keep = emptyList(), delete = listOf(stale), insert = listOf(movedByAMinute)))
    }

    @Test
    fun aRowOnAnotherCalendar_isDeleted_evenWhenItsTimesMatch_andTheRangeIsInsertedOnTheChosenOne() {
        val elsewhere = row(1, ten, eleven, calendarId = other)

        val plan = reconcileBusyBlocks(existing = listOf(elsewhere), desired = listOf(BusyRange(ten, eleven)), calendarId = family)

        assertThat(plan).isEqualTo(BusyBlockPlan(keep = emptyList(), delete = listOf(elsewhere), insert = listOf(BusyRange(ten, eleven))))
    }

    @Test
    fun noDesiredRanges_deletesEveryExistingRow() {
        val rows = listOf(row(1, ten, eleven), row(2, noon, one, calendarId = other))

        val plan = reconcileBusyBlocks(existing = rows, desired = emptyList(), calendarId = family)

        assertThat(plan).isEqualTo(BusyBlockPlan(keep = emptyList(), delete = rows, insert = emptyList()))
    }

    @Test
    fun noExistingRows_insertsEveryDesiredRange_inOrder() {
        val desired = listOf(BusyRange(ten, eleven), BusyRange(noon, one))

        val plan = reconcileBusyBlocks(existing = emptyList(), desired = desired, calendarId = family)

        assertThat(plan).isEqualTo(BusyBlockPlan(keep = emptyList(), delete = emptyList(), insert = desired))
    }

    @Test
    fun aRepeatedDesiredRange_isInsertedOnce_andSatisfiedByOneKeptRow() {
        val twice = listOf(BusyRange(ten, eleven), BusyRange(ten, eleven))

        assertThat(reconcileBusyBlocks(existing = emptyList(), desired = twice, calendarId = family).insert)
            .containsExactly(BusyRange(ten, eleven))

        val kept = row(1, ten, eleven)
        val plan = reconcileBusyBlocks(existing = listOf(kept), desired = twice, calendarId = family)
        assertThat(plan.keep).containsExactly(kept)
        assertThat(plan.insert).isEmpty()
    }

    @Test
    fun twoExistingRowsForTheSameRange_keepTheFirstAndDeleteTheDuplicate() {
        val first = row(1, ten, eleven)
        val duplicate = row(2, ten, eleven)

        val plan = reconcileBusyBlocks(existing = listOf(first, duplicate), desired = listOf(BusyRange(ten, eleven)), calendarId = family)

        assertThat(plan).isEqualTo(BusyBlockPlan(keep = listOf(first), delete = listOf(duplicate), insert = emptyList()))
    }

    @Test
    fun aMixedDay_keepsDeletesAndInsertsTogether() {
        val unchanged = row(1, ten, eleven)
        val gone = row(2, noon, one)
        val added = BusyRange(one, one.plusSeconds(1800))

        val plan = reconcileBusyBlocks(existing = listOf(unchanged, gone), desired = listOf(BusyRange(ten, eleven), added), calendarId = family)

        assertThat(plan).isEqualTo(BusyBlockPlan(keep = listOf(unchanged), delete = listOf(gone), insert = listOf(added)))
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val day: LocalDate = LocalDate.of(2026, 9, 14)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant = date.atTime(hour, minute).atZone(zone).toInstant()

    @Test
    fun clipToDay_keepsARangeInsideTheDayAsItIs() {
        val inside = BusyRange(at(day, 9), at(day, 10))

        assertThat(listOf(inside).clipToDay(day, zone)).containsExactly(inside)
    }

    @Test
    fun clipToDay_cutsARangeAtTheDaysEdges() {
        val midnight = day.plusDays(1).atStartOfDay(zone).toInstant()
        val overnight = BusyRange(at(day, 23), at(day.plusDays(1), 1))

        assertThat(listOf(overnight).clipToDay(day, zone)).containsExactly(BusyRange(at(day, 23), midnight))
        assertThat(listOf(overnight).clipToDay(day.plusDays(1), zone)).containsExactly(BusyRange(midnight, at(day.plusDays(1), 1)))
    }

    @Test
    fun clipToDay_dropsARangeThatNeverReachesIntoTheDay() {
        // ends exactly at midnight: it belongs to the day it started, not the next one
        val endsAtMidnight = BusyRange(at(day, 23), day.plusDays(1).atStartOfDay(zone).toInstant())
        val anotherDay = BusyRange(at(day.plusDays(2), 9), at(day.plusDays(2), 10))

        assertThat(listOf(endsAtMidnight, anotherDay).clipToDay(day.plusDays(1), zone)).isEmpty()
    }
}

package com.episode6.meetingminder.share

import com.episode6.meetingminder.data.calendar.BUSY_BLOCK_TITLE
import com.episode6.meetingminder.data.db.BusyBlockEntity
import com.episode6.meetingminder.model.BusyRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What one sync of a day has to do to the chosen calendar (TODO.md §4.7): [keep] the rows
 * that already say the right thing, [delete] every other existing row, [insert] every
 * desired range no kept row covers. Only ever built from `busy_block` rows, so [delete]
 * can never name an event the app didn't write.
 */
data class BusyBlockPlan(
    val keep: List<BusyBlockEntity>,
    val delete: List<BusyBlockEntity>,
    val insert: List<BusyRange>,
)

/**
 * Reconciles a day's [existing] `busy_block` rows against the [desired] busy ranges of a
 * fresh share, for the calendar the sync writes to now ([calendarId]) under the title it
 * writes now ([title], `busyBlockTitle` of the first name in Settings):
 * - an existing row on [calendarId] titled [title] whose begin/end equal a desired range is kept, and that
 *   range is satisfied (a second identical row is deleted rather than kept twice — nothing
 *   in the syncer produces one, since a crash between the provider insert and the table
 *   write leaves the calendar with an extra event and the table with nothing, not the
 *   other way round; the branch is defensive so the table can never hold two rows for one
 *   range whatever put them there);
 * - every other existing row — different times, a different calendar (the user switched
 *   calendars since) or a different title (the user changed their first name since) — is
 *   deleted;
 * - every unsatisfied desired range is inserted, once, even if [desired] repeats it.
 *
 * Ranges compare by **exact instants**: a one-minute move is a delete plus an insert, never
 * an update (decision 6 of the spec: simpler than an update, and Google's UI shows it the
 * same way). Order is preserved from the inputs so the syncer's writes are predictable.
 */
fun reconcileBusyBlocks(
    existing: List<BusyBlockEntity>,
    desired: List<BusyRange>,
    calendarId: Long,
    title: String = BUSY_BLOCK_TITLE,
): BusyBlockPlan {
    val unsatisfied = desired.distinct().toMutableList()
    val keep = mutableListOf<BusyBlockEntity>()
    val delete = mutableListOf<BusyBlockEntity>()
    for (row in existing) {
        val covered = if (row.calendarId == calendarId && row.title == title) unsatisfied.firstOrNull { it == row.range } else null
        if (covered != null) {
            unsatisfied.remove(covered)
            keep += row
        } else {
            delete += row
        }
    }
    return BusyBlockPlan(keep = keep, delete = delete, insert = unsatisfied)
}

/**
 * [this] cut down to the part that falls on [date] in [zone]: what one day's sync writes
 * (TODO.md §4.7). A share's ranges are deliberately *not* clipped — an event across midnight
 * is shared whole from whichever page it was picked on (`ScheduleTextFormatter`) — but a
 * block is bookkept per day, and a midnight-spanning event selected on both of its pages
 * would otherwise be written twice, once under each date, with either day's reconcile blind
 * to the other's row. Clipped, each day owns exactly its own part of the block, and the
 * partner sees two adjacent blocks rather than one twice. A range that doesn't reach into
 * the day is dropped.
 */
fun List<BusyRange>.clipToDay(date: LocalDate, zone: ZoneId): List<BusyRange> {
    val dayBegin = date.atStartOfDay(zone).toInstant()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
    return mapNotNull { range ->
        val begin = maxOf(range.begin, dayBegin)
        val end = minOf(range.end, dayEnd)
        if (end > begin) BusyRange(begin, end) else null
    }
}

/** The row's times as the [BusyRange] a share computes, for exact-instant comparison. */
val BusyBlockEntity.range: BusyRange
    get() = BusyRange(Instant.ofEpochMilli(beginMillis), Instant.ofEpochMilli(endMillis))

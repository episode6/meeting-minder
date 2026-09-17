package com.episode6.meetingminder.share

import com.episode6.meetingminder.data.db.BusyBlockEntity
import com.episode6.meetingminder.model.BusyRange
import java.time.Instant

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
 * fresh share, for the calendar the sync writes to now ([calendarId]):
 * - an existing row on [calendarId] whose begin/end equal a desired range is kept, and that
 *   range is satisfied (a second identical row, left behind by a crash between the provider
 *   insert and the table write of a retry, is deleted: the range is already satisfied);
 * - every other existing row — different times, or a different calendar (the user switched
 *   calendars since) — is deleted;
 * - every unsatisfied desired range is inserted, once, even if [desired] repeats it.
 *
 * Ranges compare by **exact instants**: a one-minute move is a delete plus an insert, never
 * an update (decision 6 of the spec: simpler than an update, and Google's UI shows it the
 * same way). Order is preserved from the inputs so the syncer's writes are predictable.
 */
fun reconcileBusyBlocks(existing: List<BusyBlockEntity>, desired: List<BusyRange>, calendarId: Long): BusyBlockPlan {
    val unsatisfied = desired.distinct().toMutableList()
    val keep = mutableListOf<BusyBlockEntity>()
    val delete = mutableListOf<BusyBlockEntity>()
    for (row in existing) {
        val covered = if (row.calendarId == calendarId) unsatisfied.firstOrNull { it == row.range } else null
        if (covered != null) {
            unsatisfied.remove(covered)
            keep += row
        } else {
            delete += row
        }
    }
    return BusyBlockPlan(keep = keep, delete = delete, insert = unsatisfied)
}

/** The row's times as the [BusyRange] a share computes, for exact-instant comparison. */
val BusyBlockEntity.range: BusyRange
    get() = BusyRange(Instant.ofEpochMilli(beginMillis), Instant.ofEpochMilli(endMillis))

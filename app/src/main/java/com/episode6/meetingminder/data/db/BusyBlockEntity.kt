package com.episode6.meetingminder.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * `busy_block` (TODO.md §4.7): one row per busy block the app inserted into the user's
 * chosen calendar, keyed by the `Events._ID` the insert returned. This table is **the**
 * source of truth for "what the app wrote": `share/BusyCalendarSyncer` only ever asks the
 * provider to delete ids read from here, and `excludeOwnBlocks` hides these ids from every
 * read (alongside the `CUSTOM_APP_PACKAGE` marker, which may not survive a sync round
 * trip). A row is written the moment its insert returns and dropped the moment its delete
 * is asked for, so a crash mid-sync leaves the table truthful rather than the calendar.
 */
@Entity(tableName = "busy_block")
data class BusyBlockEntity(
    /** The `Events._ID` the insert returned; the one thing `deleteOwnEvent` is ever fed. */
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: Long,
    /** The shared day the block belongs to: what a re-share reconciles and "Mark as not shared" clears. */
    val date: LocalDate,
    /** The calendar it was written to; a block on another calendar is deleted, never re-homed. */
    @ColumnInfo(name = "calendar_id") val calendarId: Long,
    @ColumnInfo(name = "begin_millis") val beginMillis: Long,
    @ColumnInfo(name = "end_millis") val endMillis: Long,
)

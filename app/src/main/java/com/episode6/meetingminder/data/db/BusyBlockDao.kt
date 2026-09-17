package com.episode6.meetingminder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Room access to `busy_block` (TODO.md §4.7). `share/BusyCalendarSyncer` is the only
 * writer: [upsert] as each provider insert returns, [delete] as each provider delete is
 * asked for. Readers: the syncer's reconcile ([blocksOn]) and cleanup ([blocksFrom]), and
 * the hiding at every calendar read site ([observeEventIds]).
 */
@Dao
interface BusyBlockDao {
    /** The blocks written for [date], in time order. */
    @Query("SELECT * FROM busy_block WHERE date = :date ORDER BY begin_millis, event_id")
    suspend fun blocksOn(date: LocalDate): List<BusyBlockEntity>

    /**
     * The blocks of [date] and every later day, for the disable/switch cleanup, which
     * leaves past days as history. `date` is stored as ISO text, which orders like dates.
     */
    @Query("SELECT * FROM busy_block WHERE date >= :date ORDER BY date, begin_millis, event_id")
    suspend fun blocksFrom(date: LocalDate): List<BusyBlockEntity>

    /** Every recorded `Events._ID`, streamed on each change, for `excludeOwnBlocks`. */
    fun observeEventIds(): Flow<Set<Long>> = observeEventIdRows().map { it.toSet() }.distinctUntilChanged()

    /**
     * Every recorded `Events._ID`, read once, for the `excludeOwnBlocks` of a read that
     * happens on its own schedule rather than on a stream: the day load, the change
     * monitor's fresh read and the share's cold-process read all take the ids as they are
     * at the moment they read the provider.
     */
    suspend fun eventIds(): Set<Long> = eventIdRows().toSet()

    /** Backs [eventIds]; Room returns lists, the callers want a set. */
    @Query("SELECT event_id FROM busy_block")
    suspend fun eventIdRows(): List<Long>

    /** Backs [observeEventIds]; Room returns lists, the callers want a set. */
    @Query("SELECT event_id FROM busy_block")
    fun observeEventIdRows(): Flow<List<Long>>

    /** `REPLACE` keyed on `event_id`: a re-recorded id just refreshes its day and times. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BusyBlockEntity)

    /** Forgets one block, called right after its provider delete was asked for. Returns the rows removed (0 or 1). */
    @Query("DELETE FROM busy_block WHERE event_id = :eventId")
    suspend fun delete(eventId: Long): Int

    /** Forgets every block of [date]. Only for a day whose provider rows are already gone. */
    @Query("DELETE FROM busy_block WHERE date = :date")
    suspend fun deleteOn(date: LocalDate): Int

    /**
     * Forgets every block of a day before [date] — the table's history window, applied by
     * `share/BusyCalendarSyncer` at each sync so the table stays a few rows per shared day
     * of the last `BUSY_BLOCK_HISTORY_DAYS`, not of the app's lifetime. Table only: the
     * events stay on the calendar as history (the `CUSTOM_APP_PACKAGE` marker keeps hiding
     * them here). Returns the rows forgotten.
     */
    @Query("DELETE FROM busy_block WHERE date < :date")
    suspend fun deleteBefore(date: LocalDate): Int
}

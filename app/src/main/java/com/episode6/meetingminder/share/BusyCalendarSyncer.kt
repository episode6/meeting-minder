package com.episode6.meetingminder.share

import android.util.Log
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.calendar.effectiveBusyCalendar
import com.episode6.meetingminder.data.db.BusyBlockDao
import com.episode6.meetingminder.data.db.BusyBlockEntity
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.BusyRange
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate

private const val TAG = "MeetingMinderBusySync"

/** The outcome of one [BusyCalendarSyncer.sync]. */
sealed interface BusySyncResult {
    /** The feature is off, no calendar is chosen, or the chosen one is gone or read-only: nothing was touched. */
    data object Skipped : BusySyncResult

    /** Every planned delete and insert went through; [inserted] and [deleted] count the provider writes. */
    data class Synced(val calendarName: String, val inserted: Int, val deleted: Int) : BusySyncResult

    /**
     * A provider write threw [cause] part way. The rows that succeeded before it are kept
     * (each is recorded as its write returns), so the next share reconciles from a true
     * picture rather than re-inserting what is already there.
     */
    data class Failed(val calendarName: String, val cause: Exception) : BusySyncResult
}

/**
 * Writes a shared day's busy ranges to the user's chosen calendar and takes them back out
 * again (TODO.md §4.7). The `busy_block` table is the source of truth for what the app
 * wrote: every provider delete here is of an id read from that table, and nothing else
 * ever reaches [CalendarRepository.deleteOwnEvent]. PR-15c wires this into `ShareDay`
 * (`sync`), "Mark as not shared" (`clear`) and the setting's cleanup (`clearFrom`).
 *
 * Ordering inside [sync]: deletes first, then inserts, and each row is recorded the moment
 * its provider write returns, so a crash between two writes leaves the table describing
 * exactly what is on the calendar. A `false` from [CalendarRepository.deleteOwnEvent] (the
 * row was already gone: the user deleted it by hand) still drops the table row — the app
 * doesn't fight a deletion the user made. The calendar is resolved over a fresh
 * [CalendarRepository.calendars] read, never the store's cached list, so a calendar removed
 * or made read-only since the last load is noticed before anything is written.
 */
@Inject
@SingleIn(AppScope::class)
class BusyCalendarSyncer(
    private val repository: CalendarRepository,
    private val dao: BusyBlockDao,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    // a share's sync and a cleanup can overlap (share, then "Mark as not shared" at once);
    // one pass at a time so neither reads rows the other is half-way through changing
    private val mutex = Mutex()

    /**
     * Reconciles [date]'s blocks to [ranges] (already merged by `selectedBusyRanges`; an
     * empty list means "no meetings today" and removes every block of the day). Returns
     * [BusySyncResult.Skipped] when the sync isn't effective, [BusySyncResult.Synced] when
     * every write went through, [BusySyncResult.Failed] when a provider write threw — except
     * a `SecurityException` (`WRITE_CALENDAR` revoked), which propagates so the caller can
     * re-check permissions the way the RSVP write does.
     */
    suspend fun sync(date: LocalDate, ranges: List<BusyRange>): BusySyncResult = mutex.withLock {
        val busySync = settings.current().busySync
        val calendar = effectiveBusyCalendar(busySync, repository.calendars()) ?: return@withLock BusySyncResult.Skipped
        val plan = reconcileBusyBlocks(dao.blocksOn(date), ranges, calendar.id)
        var deleted = 0
        var inserted = 0
        try {
            for (row in plan.delete) {
                deleteBlock(row)
                deleted++
            }
            for (range in plan.insert) {
                val eventId = repository.insertBusyBlock(calendar.id, range, clock.zone)
                dao.upsert(
                    BusyBlockEntity(
                        eventId = eventId,
                        date = date,
                        calendarId = calendar.id,
                        beginMillis = range.begin.toEpochMilli(),
                        endMillis = range.end.toEpochMilli(),
                    ),
                )
                inserted++
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "busy sync of $date to ${calendar.displayName} failed after $deleted deletes and $inserted inserts", e)
            return@withLock BusySyncResult.Failed(calendar.displayName, e)
        }
        Log.d(TAG, "busy sync of $date to ${calendar.displayName}: kept ${plan.keep.size}, deleted $deleted, inserted $inserted")
        BusySyncResult.Synced(calendar.displayName, inserted = inserted, deleted = deleted)
    }

    /**
     * Deletes every block written for [date] ("Mark as not shared"). Returns how many
     * provider deletes were asked for. Exceptions propagate (a `SecurityException` for the
     * caller's permission re-check, anything else for it to log); the rows already handled
     * are gone from the table, so a retry picks up where this left off.
     */
    suspend fun clear(date: LocalDate): Int = mutex.withLock { deleteAll(dao.blocksOn(date)) }

    /** Deletes the blocks of [from] and every later day, on every calendar (the toggle turned off). Past days stay as history. */
    suspend fun clearFrom(from: LocalDate): Int = mutex.withLock { deleteAll(dao.blocksFrom(from)) }

    /**
     * Deletes the blocks of [from] and every later day that were written to [calendarId]
     * (the user switched to another calendar); they are re-created on the new calendar by
     * the next share of each day, not eagerly.
     */
    suspend fun clearFrom(from: LocalDate, calendarId: Long): Int =
        mutex.withLock { deleteAll(dao.blocksFrom(from).filter { it.calendarId == calendarId }) }

    private suspend fun deleteAll(rows: List<BusyBlockEntity>): Int {
        for (row in rows) deleteBlock(row)
        return rows.size
    }

    /** The provider delete, then the table row: a `false` (already gone) is still done. */
    private suspend fun deleteBlock(row: BusyBlockEntity) {
        if (!repository.deleteOwnEvent(row.eventId)) {
            Log.d(TAG, "busy block ${row.eventId} on ${row.date} was already gone from the calendar")
        }
        dao.delete(row.eventId)
    }
}

package com.episode6.meetingminder.share

import android.util.Log
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.calendar.busyBlockTitle
import com.episode6.meetingminder.data.calendar.effectiveBusyCalendar
import com.episode6.meetingminder.data.db.BusyBlockDao
import com.episode6.meetingminder.data.db.BusyBlockEntity
import com.episode6.meetingminder.data.db.DayPlanDao
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

/**
 * How far back `busy_block` remembers what it wrote: rows of days older than this are
 * forgotten at each [BusyCalendarSyncer.sync] (table only; the calendar keeps the events as
 * history and the `CUSTOM_APP_PACKAGE` marker keeps hiding them on this device), so the
 * table — and the id set every provider read filters by — stays a few rows per shared day
 * of the last month rather than of the app's lifetime. A day older than this that is shared
 * again gets fresh blocks beside the old ones; widen the window rather than reading the
 * provider for `busy` titles, which would delete blocks the user typed by hand.
 */
internal const val BUSY_BLOCK_HISTORY_DAYS = 30L

/** The outcome of one [BusyCalendarSyncer.sync]. */
sealed interface BusySyncResult {
    /**
     * Nothing was touched: the feature is off, no calendar is chosen, the chosen one is gone
     * or read-only, or the day is no longer shared (a "Mark as not shared" got in first).
     */
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
 * exactly what is on the calendar — with one window: a crash between
 * [CalendarRepository.insertBusyBlock] returning and the [BusyBlockDao.upsert] right after
 * it leaves an **orphan**, a `busy` event on the calendar that the table never learns
 * about. The app can never delete it (only table ids are ever deleted), the next share
 * inserts the range again beside it, and it stays hidden on this device only by its
 * `CUSTOM_APP_PACKAGE` marker. The window is one Room write wide, and the alternative
 * (recording the row before the insert, with a placeholder id) would break "the table only
 * ever names events that exist", so it is accepted and written down here. A `false` from
 * [CalendarRepository.deleteOwnEvent] (the provider no longer had the row) still drops the
 * table row — the app doesn't fight a deletion made behind its back. The calendar is
 * resolved over a fresh [CalendarRepository.calendars] read, never the store's cached list,
 * so a calendar removed or made read-only since the last load is noticed before anything
 * is written.
 */
@Inject
@SingleIn(AppScope::class)
class BusyCalendarSyncer(
    private val repository: CalendarRepository,
    private val dao: BusyBlockDao,
    private val dayPlanDao: DayPlanDao,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    // A share's sync and a cleanup can overlap (share, then "Mark as not shared" at once):
    // one pass at a time, so neither reads rows the other is half-way through changing. The
    // lock doesn't order them — see the shared_at check in sync() for what does.
    private val mutex = Mutex()

    /**
     * Reconciles [date]'s blocks to [ranges] (already merged by `selectedBusyRanges`; an
     * empty list means "no meetings today" and removes every block of the day), clipped to
     * the day ([clipToDay]) so a midnight-spanning selection is bookkept under each of its
     * days once. Returns [BusySyncResult.Skipped] when the sync isn't effective or the day
     * is no longer shared — `day_plan.shared_at` is read under the lock, so a "Mark as not
     * shared" that beat a share's fanned-out sync to the lock (it clears `shared_at` before
     * it takes the lock) can't be followed by that sync's inserts — [BusySyncResult.Synced]
     * when every write went through, [BusySyncResult.Failed] when a provider write threw —
     * except a `SecurityException` (`WRITE_CALENDAR` revoked), which propagates so the
     * caller can re-check permissions the way the RSVP write does. Blocks are titled
     * `busyBlockTitle` of the first name in Settings, and a kept row must carry that title,
     * so the first re-share after the name changed replaces the day's blocks. Each sync also
     * forgets the table's rows older than [BUSY_BLOCK_HISTORY_DAYS].
     */
    suspend fun sync(date: LocalDate, ranges: List<BusyRange>): BusySyncResult = mutex.withLock {
        val forgotten = dao.deleteBefore(LocalDate.now(clock).minusDays(BUSY_BLOCK_HISTORY_DAYS))
        if (forgotten > 0) Log.d(TAG, "forgot $forgotten busy block rows older than $BUSY_BLOCK_HISTORY_DAYS days")
        if (dayPlanDao.dayPlanOn(date)?.sharedAt == null) {
            Log.d(TAG, "busy sync of $date skipped: the day is no longer shared")
            return@withLock BusySyncResult.Skipped
        }
        val busySync = settings.current().busySync
        val calendar = effectiveBusyCalendar(busySync, repository.calendars()) ?: return@withLock BusySyncResult.Skipped
        val title = busyBlockTitle(busySync.firstName)
        val plan = reconcileBusyBlocks(dao.blocksOn(date), ranges.clipToDay(date, clock.zone), calendar.id, title)
        var deleted = 0
        var inserted = 0
        try {
            for (row in plan.delete) {
                deleteBlock(row)
                deleted++
            }
            for (range in plan.insert) {
                val eventId = repository.insertBusyBlock(calendar.id, range, busySync.firstName)
                dao.upsert(
                    BusyBlockEntity(
                        eventId = eventId,
                        date = date,
                        calendarId = calendar.id,
                        beginMillis = range.begin.toEpochMilli(),
                        endMillis = range.end.toEpochMilli(),
                        title = title,
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

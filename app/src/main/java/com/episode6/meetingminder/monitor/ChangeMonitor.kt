package com.episode6.meetingminder.monitor

import android.util.Log
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.db.ChangeSnapshotDao
import com.episode6.meetingminder.data.db.ChangeSnapshotEntity
import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.data.db.baseline
import com.episode6.meetingminder.data.db.decodeScheduleChanges
import com.episode6.meetingminder.data.db.encodeScheduleChanges
import com.episode6.meetingminder.data.db.promoteSyncedRsvps
import com.episode6.meetingminder.permissions.PermissionChecker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate

private const val TAG = "MeetingMinderChanges"

/**
 * Runs change detection for every shared day and keeps monitoring's lifecycle (TODO.md
 * §4.3). Called by [CalendarChangeWorker] in the background, by the foreground
 * `CalendarContentChanged` reload (`ChangeDetectionSideEffects`), and by a share or "Mark as
 * not shared" ([onShareChanged]). No `AppStore` here: a worker can't await a dispatch, and
 * `change_snapshot` (streamed into the store) is the source of truth anyway.
 *
 * A shared day is a `change_snapshot` row (written at share time, deleted by "Mark as not
 * shared"); monitoring covers it until its local midnight passes. [runCheck]:
 * 1. drops the rows (and cancels the notifications) of days that have ended;
 * 2. with calendar access, for each remaining day reads the whole day fresh, promotes waiting
 *    RSVPs to `SYNCED` (§4.6), diffs it against the baseline ([ChangeDetector]), and when
 *    the result differs from what the last check recorded, records it
 *    (`changes_json`) and updates the notification — alerting only when a change is new
 *    (and, `setOnlyAlertOnce`, only if it isn't already showing), cancelling it when nothing
 *    is changed any more;
 * 3. re-arms the background works for the days still shared, or disarms them.
 */
@Inject
@SingleIn(AppScope::class)
class ChangeMonitor(
    private val repository: CalendarRepository,
    private val snapshotDao: ChangeSnapshotDao,
    private val dayPlanDao: DayPlanDao,
    private val permissionChecker: PermissionChecker,
    private val notifier: ScheduleChangeNotifier,
    private val scheduler: ChangeWorkScheduler,
    private val clock: Clock,
) {
    // the worker and the foreground reload can overlap; one check at a time
    private val mutex = Mutex()

    // Both entry points read change_snapshot and arm from what they read under this lock, so
    // a check that read "nothing shared" can't disarm after a share has armed (and a share's
    // notification cancel can't land before a check that is still showing one finishes).
    suspend fun runCheck(reason: ChangeCheckReason) = mutex.withLock {
        val sharedDays = try {
            val today = LocalDate.now(clock)
            val (ended, current) = snapshotDao.all().partition { it.date < today }
            for (snapshot in ended) {
                notifier.cancel(snapshot.date)
                snapshotDao.delete(snapshot.date)
            }
            if (permissionChecker.currentState().calendarGranted) {
                for (snapshot in current) check(snapshot)
            }
            current.mapTo(sortedSetOf()) { it.date }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // leave the works as they are: the periodic safety net retries
            Log.w(TAG, "change check failed", e)
            return@withLock
        }
        scheduler.update(sharedDays, reason)
    }

    /**
     * [date] was just shared, re-shared or marked as not shared: its notification no
     * longer describes anything (a re-share replaced the baseline), and monitoring may have
     * to start or stop.
     */
    suspend fun onShareChanged(date: LocalDate) = mutex.withLock {
        notifier.cancel(date)
        val today = LocalDate.now(clock)
        scheduler.update(snapshotDao.all().mapNotNullTo(sortedSetOf()) { it.date.takeIf { day -> day >= today } }, ChangeCheckReason.IN_APP)
    }

    // The fresh read uses the default calendar filter, like the LoadDay read the share's
    // baseline came from. Any per-day calendar filter (PR-12) must be applied to both, or
    // every meeting on a calendar only one of them excludes reads as New or Cancelled.
    private suspend fun check(snapshot: ChangeSnapshotEntity) {
        val fresh = try {
            repository.eventsOn(snapshot.date)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not read ${snapshot.date}", e)
            return
        }
        dayPlanDao.promoteSyncedRsvps(snapshot.date, repository)
        val changes = ChangeDetector.detect(snapshot.date, snapshot.baseline(), fresh, clock.instant())
        val previous = decodeScheduleChanges(snapshot.date, snapshot.changesJson)
        if (changes == previous) return
        // the record and the notification go together, or a cancelled check could leave a
        // change recorded (and so never notified) or notified without being recorded
        withContext(NonCancellable) {
            // guarded by taken_at: a re-share since the read replaced the baseline these changes were found against
            if (snapshotDao.setChanges(snapshot.date, snapshot.takenAt, encodeScheduleChanges(changes)) == 0) return@withContext
            if (changes.isEmpty()) {
                notifier.cancel(snapshot.date)
            } else {
                notifier.show(snapshot.date, changes, alert = changes.any { it !in previous })
            }
        }
    }
}

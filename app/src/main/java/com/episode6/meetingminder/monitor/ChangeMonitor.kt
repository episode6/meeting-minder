package com.episode6.meetingminder.monitor

import android.util.Log
import com.episode6.meetingminder.data.calendar.CalendarFilter
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.calendar.ShareMode
import com.episode6.meetingminder.data.calendar.effectiveCalendarFilter
import com.episode6.meetingminder.data.calendar.excludeDeclined
import com.episode6.meetingminder.data.calendar.excludeOwnBlocks
import com.episode6.meetingminder.data.calendar.shareMode
import com.episode6.meetingminder.data.db.BusyBlockDao
import com.episode6.meetingminder.data.db.ChangeSnapshotDao
import com.episode6.meetingminder.data.db.ChangeSnapshotEntity
import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.data.db.baseline
import com.episode6.meetingminder.data.db.decodeScheduleChanges
import com.episode6.meetingminder.data.db.encodeScheduleChanges
import com.episode6.meetingminder.data.db.promoteSyncedRsvps
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.CalendarInfo
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
 *    is changed any more; a new change on today found by a background check also rings the
 *    loud full-screen alert ([ScheduleChangeAlerter]);
 * 3. re-arms the background works for the days still shared, or disarms them.
 */
@Inject
@SingleIn(AppScope::class)
class ChangeMonitor(
    private val repository: CalendarRepository,
    private val snapshotDao: ChangeSnapshotDao,
    private val dayPlanDao: DayPlanDao,
    private val busyBlockDao: BusyBlockDao,
    private val permissionChecker: PermissionChecker,
    private val notifier: ScheduleChangeNotifier,
    private val scheduler: ChangeWorkScheduler,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val alerter: ScheduleChangeAlerter,
    private val mainUi: MainUiVisibility,
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
                val prefs = settings.current()
                // computed once per check pass, and only when an override (or, for the
                // notification's wording, a text-less busy sync) needs it, so the common
                // case never re-reads the calendar list
                val maybeSyncOnly = prefs.busySync.enabled && !prefs.busySync.sendText
                val calendars = when {
                    prefs.calendarOverrides.isNotEmpty() -> repository.calendars()
                    maybeSyncOnly -> calendarsForWording()
                    else -> emptyList()
                }
                val filter = if (prefs.calendarOverrides.isEmpty()) {
                    CalendarFilter.Visible
                } else {
                    effectiveCalendarFilter(calendars, prefs.calendarOverrides)
                }
                val syncOnly = maybeSyncOnly && shareMode(prefs.busySync, calendars) == ShareMode.SYNC_ONLY
                // read once per pass, like the filter: our own busy blocks (TODO.md §4.7)
                // must never read as a change, and a share can insert one between passes
                val ownBlocks = busyBlockDao.eventIds()
                for (snapshot in current) {
                    check(
                        snapshot,
                        filter,
                        prefs.showDeclined,
                        ownBlocks,
                        loud = reason != ChangeCheckReason.IN_APP && !mainUi.visible && snapshot.date == today,
                        syncOnly = syncOnly,
                    )
                }
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

    // Only the notification's wording depends on this read (TODO.md §4.7), so a failure
    // falls back to "shared" wording rather than skipping every day's check.
    private suspend fun calendarsForWording(): List<CalendarInfo> = try {
        repository.calendars()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "could not read the calendars for the notification's wording", e)
        emptyList()
    }

    /**
     * [date] was just shared, re-shared or marked as not shared: its notification no
     * longer describes anything (a re-share replaced the baseline), and monitoring may have
     * to start or stop.
     */
    suspend fun onShareChanged(date: LocalDate) = mutex.withLock {
        notifier.cancel(date)
        alerter.cancel(date)
        val today = LocalDate.now(clock)
        scheduler.update(snapshotDao.all().mapNotNullTo(sortedSetOf()) { it.date.takeIf { day -> day >= today } }, ChangeCheckReason.IN_APP)
    }

    // The fresh read uses the same calendar filter, "show declined" toggle and own-block
    // exclusion as the LoadDay read the share's baseline came from (TODO.md §5 PR-12,
    // §4.7), so a calendar or event only one of them excludes never reads as New or
    // Cancelled — in particular the `busy` blocks the share itself wrote a moment ago.
    //
    // [loud]: a new change also rings the full-screen alert ([ScheduleChangeAlerter]) — for
    // today only (a day shared ahead mustn't ring in the night for an invite that can wait
    // for the morning), and never while the app is on screen — not from its own foreground
    // check, and not from a background check that beat it to the lock ([MainUiVisibility]) —
    // where the banner is already in front of the user and the change is often their own
    // (an RSVP "No" from the chip menu reads as Declined).
    //
    // [syncOnly] words the notification for a day synced to the busy calendar rather than
    // shared as text (TODO.md §4.7).
    private suspend fun check(
        snapshot: ChangeSnapshotEntity,
        filter: CalendarFilter,
        showDeclined: Boolean,
        ownBlocks: Set<Long>,
        loud: Boolean,
        syncOnly: Boolean,
    ) {
        val fresh = try {
            repository.eventsOn(snapshot.date, filter).excludeDeclined(showDeclined).excludeOwnBlocks(ownBlocks)
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
                alerter.cancel(snapshot.date)
            } else {
                val isNew = changes.any { it !in previous }
                // the alert makes the noise when it rings; the notification stays behind it
                val ringing = isNew && loud && alerter.alert(snapshot.date)
                notifier.show(snapshot.date, changes, alert = isNew, silent = ringing, syncOnly = syncOnly)
            }
        }
    }
}

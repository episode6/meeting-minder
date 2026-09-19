package com.episode6.meetingminder.store.sideeffects

import android.util.Log
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.db.ChangeSnapshotDao
import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.monitor.ChangeMonitor
import com.episode6.meetingminder.share.BusyCalendarSyncer
import com.episode6.meetingminder.share.BusySyncResult
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.BusySyncSettingChanged
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.SyncBusyCalendar
import com.episode6.meetingminder.store.UiMessage
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.LocalDate

private const val TAG = "MeetingMinderBusySync"

/**
 * The busy-calendar sync's store wiring (TODO.md §4.7): everything that writes `busy`
 * blocks to the user's chosen calendar, or takes them back out, goes through
 * [BusyCalendarSyncer] from here.
 *
 * - [SyncBusyCalendar] is fanned out by `ShareDaySideEffects` right after
 *   [com.episode6.meetingminder.store.SetPendingShare] and only while the feature is on,
 *   so the chooser opens without waiting on provider IO (the `RsvpAccept` shape). A
 *   [BusySyncResult.Failed] is a snackbar and nothing else — the share itself went out, so
 *   it never blocks or reverses it; [BusySyncResult.Synced] and [BusySyncResult.Skipped]
 *   say nothing (the share is the visible outcome; a second snackbar would be noise).
 *   A sync-only share ([SyncBusyCalendar.syncOnlySharedAt] set) opened no chooser, so there
 *   the sync *is* the share: [BusySyncResult.Synced] is the "Busy times synced to Family"
 *   snackbar, and any outcome that didn't get the day onto the calendar — `Failed`,
 *   `Skipped`, or a throw — undoes the `shared_at` that share recorded (and its baseline),
 *   so "Sync busy times" comes back instead of a day that claims to be synced. Only that
 *   one share is undone (`DayPlanDao.clearSharedIfAt`); the blocks a failed sync did write
 *   stay recorded in `busy_block` for the next sync to reconcile. A `SecurityException` (`WRITE_CALENDAR` revoked under us) re-checks permissions
 *   the way the RSVP write does; anything else thrown before a write (the settings, the
 *   fresh calendar list, a Room read) is the calendar-less "Couldn't sync busy times"
 *   snackbar — the user asked for a sync that didn't happen — and never ends the effect.
 * - [BusySyncSettingChanged] is the Settings cleanup: the toggle going off deletes today's
 *   and every later day's blocks on every calendar, and a change of target calendar deletes
 *   the old calendar's. Nothing is written to a newly chosen calendar here; the next share
 *   of each day does that. "Today" is `LocalDate.now(clock)` — [AppState.anchorDate] is only
 *   kept current while the UI is visible, and this can run from anywhere.
 *
 * "Mark as not shared" clears its day from inside `ShareDaySideEffects.markNotShared`
 * rather than a third effect here, so the day's bookkeeping and its blocks are cleared by
 * one effect in one order. Every pass — a sync and both cleanups — runs under
 * [BusyCalendarSyncer]'s own lock, so two of them never read rows the other is half-way
 * through changing. The lock doesn't order them, so "Mark as not shared" tapped in the
 * instant between a share's [SyncBusyCalendar] being dispatched and running can reach the
 * lock first; the syncer then finds the day's `shared_at` already cleared and skips, so the
 * cleared day never gets that share's inserts.
 */
@ContributesTo(AppScope::class)
interface BusyCalendarSyncSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun syncBusyCalendar(
        syncer: BusyCalendarSyncer,
        dayPlanDao: DayPlanDao,
        changeSnapshotDao: ChangeSnapshotDao,
        changeMonitor: ChangeMonitor,
    ): SideEffect<AppState> = sideEffect {
        // a sync-only share whose sync didn't reach the calendar: see the class doc
        suspend fun undoSyncOnlyShare(action: SyncBusyCalendar) {
            val sharedAt = action.syncOnlySharedAt ?: return
            try {
                if (dayPlanDao.clearSharedIfAt(action.date, sharedAt) == 0) return
                changeSnapshotDao.delete(action.date)
                changeMonitor.onShareChanged(action.date)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not undo the sync-only share of ${action.date}", e)
            }
        }

        actions.filterIsInstance<SyncBusyCalendar>().flatMapMerge { action ->
            flow<Action> {
                val syncOnly = action.syncOnlySharedAt != null
                try {
                    when (val result = syncer.sync(action.date, action.ranges)) {
                        is BusySyncResult.Failed -> {
                            undoSyncOnlyShare(action)
                            emit(ShowMessage(UiMessage.next(R.string.busy_sync_failed, result.calendarName)))
                        }
                        is BusySyncResult.Synced ->
                            if (syncOnly) emit(ShowMessage(UiMessage.next(R.string.busy_sync_done, result.calendarName)))
                        // the calendar stopped resolving, or "Remove busy blocks" got to the
                        // lock first; beside a text share that's fine, but a sync-only share
                        // that wrote nothing mustn't be silent
                        BusySyncResult.Skipped -> if (syncOnly) {
                            undoSyncOnlyShare(action)
                            emit(ShowMessage(UiMessage.next(R.string.busy_sync_failed_unknown_calendar)))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    Log.w(TAG, "busy sync of ${action.date} lost calendar access", e)
                    undoSyncOnlyShare(action)
                    emit(PermissionsMaybeChanged)
                } catch (e: Exception) {
                    // BusyCalendarSyncer turns a failed provider *write* into Failed itself;
                    // what lands here threw before it (the settings read, the fresh calendar
                    // list, a Room read), so nothing was written — the sync the user asked
                    // for still didn't happen, hence the snackbar (no calendar name: it may
                    // be the settings read that failed). It must not escape the flow, or
                    // this effect's chain ends and no later share syncs for the rest of the
                    // process.
                    Log.w(TAG, "busy sync of ${action.date} failed before anything was written", e)
                    undoSyncOnlyShare(action)
                    emit(ShowMessage(UiMessage.next(R.string.busy_sync_failed_unknown_calendar)))
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun busySyncSettingChanged(syncer: BusyCalendarSyncer, clock: Clock): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<BusySyncSettingChanged>().flatMapMerge { action ->
            flow<Action> {
                val today = LocalDate.now(clock)
                try {
                    val previous = action.previousCalendarId
                    when {
                        // the feature is off: the blocks describe a plan nobody maintains any more
                        !action.enabledNow -> syncer.clearFrom(today)
                        // still on, but pointed somewhere else: only the calendar it left.
                        // The action carries both ids, so a toggle-on with the same calendar
                        // still chosen (previous == calendarId) is not read as a switch.
                        previous != null && previous != action.calendarId -> syncer.clearFrom(today, previous)
                        // turned on, or a first calendar chosen: nothing was ever written
                        else -> Unit
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    Log.w(TAG, "busy block cleanup from $today lost calendar access", e)
                    emit(PermissionsMaybeChanged)
                } catch (e: Exception) {
                    // the rows that were deleted are already out of the table; the next
                    // cleanup (or the next share of each day) picks up where this stopped
                    Log.w(TAG, "busy block cleanup from $today failed", e)
                }
            }
        }
    }
}

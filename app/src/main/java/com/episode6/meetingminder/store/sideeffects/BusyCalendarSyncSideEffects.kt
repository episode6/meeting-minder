package com.episode6.meetingminder.store.sideeffects

import android.util.Log
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.settings.SettingsRepository
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
 *   say nothing at all (the share is the visible outcome; a second snackbar would be
 *   noise). A `SecurityException` (`WRITE_CALENDAR` revoked under us) re-checks permissions
 *   the way the RSVP write does.
 * - [BusySyncSettingChanged] is the Settings cleanup: the toggle going off deletes today's
 *   and every later day's blocks on every calendar, and a change of target calendar deletes
 *   the old calendar's. Nothing is written to a newly chosen calendar here; the next share
 *   of each day does that. "Today" is `LocalDate.now(clock)` — [AppState.anchorDate] is only
 *   kept current while the UI is visible, and this can run from anywhere.
 *
 * "Mark as not shared" clears its day from inside `ShareDaySideEffects.markNotShared`
 * rather than a third effect here, so the day's bookkeeping and its blocks can't be cleared
 * by two effects interleaving.
 */
@ContributesTo(AppScope::class)
interface BusyCalendarSyncSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun syncBusyCalendar(syncer: BusyCalendarSyncer): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<SyncBusyCalendar>().flatMapMerge { action ->
            flow<Action> {
                try {
                    val result = syncer.sync(action.date, action.ranges)
                    if (result is BusySyncResult.Failed) {
                        emit(ShowMessage(UiMessage.next(R.string.busy_sync_failed, result.calendarName)))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    Log.w(TAG, "busy sync of ${action.date} lost calendar access", e)
                    emit(PermissionsMaybeChanged)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun busySyncSettingChanged(syncer: BusyCalendarSyncer, settings: SettingsRepository, clock: Clock): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<BusySyncSettingChanged>().flatMapMerge { action ->
            flow<Action> {
                val today = LocalDate.now(clock)
                try {
                    val previous = action.previousCalendarId
                    when {
                        // the feature is off: the blocks describe a plan nobody maintains any more
                        !action.enabledNow -> syncer.clearFrom(today)
                        // still on, but pointed somewhere else: only the calendar it left.
                        // Re-picking the calendar it already had changes nothing, so the
                        // radio row that was already selected can't wipe the day's blocks.
                        previous != null && previous != settings.current().busySync.calendarId -> syncer.clearFrom(today, previous)
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

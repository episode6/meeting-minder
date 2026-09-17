package com.episode6.meetingminder.store.sideeffects

import android.util.Log
import com.episode6.meetingminder.data.calendar.CalendarFilter
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.calendar.effectiveCalendarFilter
import com.episode6.meetingminder.data.calendar.excludeDeclined
import com.episode6.meetingminder.data.calendar.excludeOwnBlocks
import com.episode6.meetingminder.data.db.BusyBlockDao
import com.episode6.meetingminder.data.db.ChangeSnapshotDao
import com.episode6.meetingminder.data.db.ChangeSnapshotEntity
import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.data.db.decodeBusyRanges
import com.episode6.meetingminder.data.db.decodeScheduleChanges
import com.episode6.meetingminder.data.db.encodeBusyRanges
import com.episode6.meetingminder.data.db.encodeChangeSnapshotEvents
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.monitor.ChangeMonitor
import com.episode6.meetingminder.share.BusyCalendarSyncer
import com.episode6.meetingminder.share.ScheduleTextFormatter
import com.episode6.meetingminder.share.selectedBusyRanges
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.MarkNotShared
import com.episode6.meetingminder.store.PendingShare
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.SetPendingShare
import com.episode6.meetingminder.store.ShareFinished
import com.episode6.meetingminder.store.ShareDay
import com.episode6.meetingminder.store.SyncBusyCalendar
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
import java.time.Instant
import java.time.LocalDate

private const val TAG = "MeetingMinderShare"

/**
 * "Share schedule" / "Share again" / "Re-share" / "Share update" / "Mark as not shared"
 * (TODO.md §4.2/§4.3). [ShareDay] formats the day's *selected* events into busy-range text
 * with [ScheduleTextFormatter] ([selectedBusyRanges]: each selection's freshly loaded
 * begin/end when the provider still has the event, its stored `selected_event` times
 * otherwise), records `day_plan.shared_at`/`shared_snapshot` (the merged ranges) and the
 * `change_snapshot` baseline (every event on the day — selected or not, meeting or not —
 * that `monitor/ChangeDetector` compares a fresh read against), restarts monitoring
 * ([ChangeMonitor.onShareChanged], which also cancels the day's notification), then hands
 * the text to `Navigation.kt` via [SetPendingShare]: `ShareCompat` needs a real Activity
 * context and must never launch from a receiver (§4.2), so the chooser call happens in the
 * UI layer, not here. [MarkNotShared] clears that bookkeeping back out, stops monitoring
 * and deletes the day's busy blocks.
 *
 * With busy-calendar sync on (§4.7), the share also fans out [SyncBusyCalendar] with the
 * ranges it just formatted — **after** [SetPendingShare], so the chooser never waits on
 * provider IO, and after the baseline was written, so the blocks the sync then inserts
 * can't be read back as changes (`BusyCalendarSyncSideEffects` does the writing; the
 * baseline comes from an already `excludeOwnBlocks`-filtered read either way). That order
 * is load-bearing; keep it.
 *
 * The selection and plan are read from Room and the day's events from the store only when
 * they are loaded (otherwise straight from the provider, with the same calendar filter and
 * "show declined" toggle `LoadDayEventsSideEffects`/`monitor.ChangeMonitor` apply — TODO.md
 * §5 PR-12 — so a cold-process "Share update" writes the same baseline a warm one would),
 * because "Share update" can arrive from a notification into a cold process whose store
 * hasn't loaded anything yet. A day that was already shared and has changed since (recorded
 * changes, or busy ranges that no longer match the last share) gets the `Update:` text. If
 * the day can't be read at all, the share still goes out but no baseline is kept, rather
 * than one that would report every meeting as new.
 *
 * We can't know whether the user actually sent anything from the chooser (§4.2), so
 * "shared" is recorded as soon as the tap is handled, same moment the text is handed off
 * to be shown — there is no later confirmation to wait for.
 */
@ContributesTo(AppScope::class)
interface ShareDaySideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun shareDay(
        dayPlanDao: DayPlanDao,
        changeSnapshotDao: ChangeSnapshotDao,
        repository: CalendarRepository,
        changeMonitor: ChangeMonitor,
        busyBlockDao: BusyBlockDao,
        settings: SettingsRepository,
        clock: Clock,
    ): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<ShareDay>().flatMapMerge { action ->
            flow {
                val date = action.date
                try {
                    val prefs = settings.current()
                    val events = currentState().eventsByDay[date]?.events ?: run {
                        // computed only when an override exists, same as ChangeMonitor.runCheck
                        val filter = if (prefs.calendarOverrides.isEmpty()) {
                            CalendarFilter.Visible
                        } else {
                            effectiveCalendarFilter(repository.calendars(), prefs.calendarOverrides)
                        }
                        repository.readDay(date, filter, prefs.showDeclined, busyBlockDao.eventIds())
                    }
                    val selections = dayPlanDao.selectedEventsOn(date)
                    val busyRanges = selectedBusyRanges(
                        selections.associate { it.key to BusyRange(Instant.ofEpochMilli(it.beginMillis), Instant.ofEpochMilli(it.endMillis)) },
                        events.orEmpty(),
                    )
                    val plan = dayPlanDao.dayPlanOn(date)
                    val isUpdate = plan?.sharedAt != null && (
                        changeSnapshotDao.forDate(date)?.let { decodeScheduleChanges(date, it.changesJson) }.orEmpty().isNotEmpty() ||
                            plan.sharedSnapshot?.let(::decodeBusyRanges) != busyRanges
                        )
                    val text = ScheduleTextFormatter.format(date, busyRanges, clock.zone, isUpdate = isUpdate)
                    val now = clock.instant().toEpochMilli()

                    dayPlanDao.markShared(date, now, encodeBusyRanges(busyRanges))
                    if (events != null) {
                        changeSnapshotDao.upsert(
                            ChangeSnapshotEntity(
                                date = date,
                                takenAt = now,
                                eventsJson = encodeChangeSnapshotEvents(events, selections.mapTo(mutableSetOf()) { it.key }),
                            ),
                        )
                    } else {
                        changeSnapshotDao.delete(date)
                    }
                    changeMonitor.onShareChanged(date)
                    emit(SetPendingShare(PendingShare.next(date, text)))
                    // the chooser is on its way; the provider writes happen alongside it
                    // (TODO.md §4.7), never before it, and never when the feature is off
                    if (prefs.busySync.enabled) emit(SyncBusyCalendar(date, busyRanges))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // nothing was handed to the UI, so nothing will close a sheet: end the
                    // share in flight here or the FAB stays dead until the process dies
                    Log.w(TAG, "could not share $date", e)
                    emit(ShareFinished)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun markNotShared(
        dayPlanDao: DayPlanDao,
        changeSnapshotDao: ChangeSnapshotDao,
        changeMonitor: ChangeMonitor,
        busyCalendarSyncer: BusyCalendarSyncer,
    ): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<MarkNotShared>().flatMapMerge { action ->
            flow<Action> {
                dayPlanDao.clearShared(action.date)
                changeSnapshotDao.delete(action.date)
                changeMonitor.onShareChanged(action.date)
                // the day's busy blocks go with its bookkeeping, from inside this effect
                // rather than a second one listening for the same action, so they are
                // cleared in one order (and never half-way through a sync: every pass takes
                // the syncer's lock — see BusyCalendarSyncSideEffects for what that doesn't
                // promise)
                try {
                    busyCalendarSyncer.clear(action.date)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    Log.w(TAG, "could not clear ${action.date}'s busy blocks", e)
                    emit(PermissionsMaybeChanged)
                } catch (e: Exception) {
                    Log.w(TAG, "could not clear ${action.date}'s busy blocks", e)
                }
            }
        }
    }
}

/**
 * The whole day from the provider with the current calendar filter and "show declined"
 * toggle applied (the same read `LoadDayEventsSideEffects`/`monitor.ChangeMonitor` do), or
 * null when it can't be read (calendar access revoked).
 */
private suspend fun CalendarRepository.readDay(
    date: LocalDate,
    filter: CalendarFilter,
    showDeclined: Boolean,
    ownBlocks: Set<Long>,
): List<CalendarEvent>? = try {
    eventsOn(date, filter).excludeDeclined(showDeclined).excludeOwnBlocks(ownBlocks)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "could not read $date to share it", e)
    null
}

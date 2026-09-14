package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.data.db.ChangeSnapshotDao
import com.episode6.meetingminder.data.db.ChangeSnapshotEntity
import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.data.db.encodeBusyRanges
import com.episode6.meetingminder.data.db.encodeChangeSnapshotEvents
import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.share.ScheduleTextFormatter
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.MarkNotShared
import com.episode6.meetingminder.store.PendingShare
import com.episode6.meetingminder.store.SetPendingShare
import com.episode6.meetingminder.store.ShareDay
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import java.time.Clock

/**
 * "Share schedule" / "Share again" / "Mark as not shared" (TODO.md §4.2/§4.3). [ShareDay]
 * formats the day's *selected* events into busy-range text with [ScheduleTextFormatter],
 * records `day_plan.shared_at`/`shared_snapshot` (the merged ranges) and the
 * `change_snapshot` baseline (every event on the day — selected or not, meeting or
 * not — that PR-11's differ will compare a fresh read against), then hands the text to
 * `Navigation.kt` via [SetPendingShare]: `ShareCompat` needs a real Activity context and
 * must never launch from a receiver (§4.2), so the actual chooser call happens in the UI
 * layer, not here. [MarkNotShared] just clears that bookkeeping back out.
 *
 * We can't know whether the user actually sent anything from the chooser (§4.2), so
 * "shared" is recorded as soon as the tap is handled, same moment the text is handed off
 * to be shown — there is no later confirmation to wait for.
 */
@ContributesTo(AppScope::class)
interface ShareDaySideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun shareDay(dayPlanDao: DayPlanDao, changeSnapshotDao: ChangeSnapshotDao, clock: Clock): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<ShareDay>().flatMapMerge { action ->
            flow {
                val state = currentState()
                val selected = state.dayPlans[action.date]?.selected.orEmpty()
                val busyRanges = ScheduleTextFormatter.merge(selected.values.map { BusyRange(it.begin, it.end) })
                val text = ScheduleTextFormatter.format(action.date, busyRanges, clock.zone)
                val now = clock.instant()

                dayPlanDao.markShared(action.date, now.toEpochMilli(), encodeBusyRanges(busyRanges))
                changeSnapshotDao.upsert(
                    ChangeSnapshotEntity(
                        date = action.date,
                        takenAt = now.toEpochMilli(),
                        eventsJson = encodeChangeSnapshotEvents(
                            state.eventsByDay[action.date]?.events.orEmpty(),
                            selected.keys,
                        ),
                    ),
                )
                emit(SetPendingShare(PendingShare.next(action.date, text)))
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun markNotShared(dayPlanDao: DayPlanDao, changeSnapshotDao: ChangeSnapshotDao): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<MarkNotShared>().flatMapMerge { action ->
            flow<Action> {
                dayPlanDao.clearShared(action.date)
                changeSnapshotDao.delete(action.date)
            }
        }
    }
}

package com.episode6.meetingminder.store.sideeffects

import android.util.Log
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.data.db.promoteSyncedRsvps
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventResponse
import com.episode6.meetingminder.model.RsvpState
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.RsvpAccept
import com.episode6.meetingminder.store.RsvpAccepted
import com.episode6.meetingminder.store.RsvpResult
import com.episode6.meetingminder.store.SetDayEvents
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow

private const val TAG = "MeetingMinderRsvp"

/**
 * The RSVP write and its bookkeeping (TODO.md §4.6). [rsvpAccept] answers each
 * [RsvpAccept] through [CalendarRepository.respondToInstance] — every event its own write, so
 * failures are per event — and reports with [RsvpAccepted]; [rsvpState] records that on
 * the selection row, and promotes `ACCEPTED_LOCALLY` to `SYNCED` when, on a reload of
 * the day ([SetDayEvents], which is what our own `ContentObserver` triggers once the sync
 * adapter clears the provider's `DIRTY` flag), [CalendarRepository.syncedEventIds] reports
 * the written event clean. Nothing here is ever reversed: deselecting cancels the alarm
 * and leaves the RSVP as it is.
 *
 * `flatMapMerge` keeps the relay path non-suspending and lets several events' writes run
 * side by side.
 */
@ContributesTo(AppScope::class)
interface RsvpAcceptSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun rsvpAccept(repository: CalendarRepository): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<RsvpAccept>().flatMapMerge { accept ->
            flow {
                val event = currentState().eventsByDay[accept.date]?.events?.firstOrNull { it.key == accept.key }
                emit(RsvpAccepted(accept.date, accept.key, if (event == null) RsvpResult.Failed else repository.accept(event)))
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun rsvpState(dao: DayPlanDao, repository: CalendarRepository): SideEffect<AppState> = sideEffect {
        actions
            .filter { it is RsvpAccepted || it is SetDayEvents }
            .flatMapMerge { action ->
                flow<Action> {
                    when (action) {
                        is RsvpAccepted -> dao.setRsvp(
                            action.date, action.key.eventId, action.key.instanceTime,
                            state = if (action.result is RsvpResult.Accepted) RsvpState.ACCEPTED_LOCALLY else RsvpState.FAILED,
                            rsvpEventId = (action.result as? RsvpResult.Accepted)?.rsvpEventId,
                        )
                        is SetDayEvents -> dao.promoteSyncedRsvps(action.dayEvents.date, repository)
                    }
                }
            }
    }
}

/** Catches what the provider throws, never a cancellation of our own scope. */
private suspend fun CalendarRepository.accept(event: CalendarEvent): RsvpResult = try {
    RsvpResult.Accepted(respondToInstance(event, EventResponse.YES))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "RSVP for event ${event.eventId} failed", e)
    RsvpResult.Failed
}

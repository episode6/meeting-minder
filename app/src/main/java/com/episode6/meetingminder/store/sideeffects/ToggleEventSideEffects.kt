package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.data.db.toSelectedEventEntity
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.ToggleEvent
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapMerge

/**
 * Flips one event's selection for one day (TODO.md §3.2): if [ToggleEvent.key] is already
 * in `selected_event` for [ToggleEvent.date] it is deleted, otherwise the matching
 * [com.episode6.meetingminder.model.CalendarEvent] (looked up in the currently loaded
 * window — the chip that was tapped came from there) is copied into a new row.
 * `ObserveDayPlansSideEffects` reflects the write back into the store; this effect emits
 * no actions of its own. `flatMapMerge` (not `transformLatest`) so toggling two different
 * events in quick succession doesn't cancel the first write — but that also means two
 * back-to-back toggles of the *same* key can run concurrently, so the read-then-write is
 * pushed into [DayPlanDao.toggleSelectedEvent]'s single `@Transaction` rather than done here
 * as two separate DAO calls, which a double tap could interleave between.
 */
@ContributesTo(AppScope::class)
interface ToggleEventSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun toggleEvent(dao: DayPlanDao): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<ToggleEvent>().flatMapMerge { toggle ->
            val event = currentState().eventsByDay[toggle.date]?.events?.firstOrNull { it.key == toggle.key }
            if (event != null) {
                dao.toggleSelectedEvent(event.toSelectedEventEntity(toggle.date))
            }
            emptyFlow<Action>()
        }
    }
}

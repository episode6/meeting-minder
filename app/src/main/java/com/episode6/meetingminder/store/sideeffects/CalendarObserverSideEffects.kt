package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.data.calendar.CalendarChangeSource
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.SetPermissions
import com.episode6.redux.sideeffects.SideEffect
import com.episode6.redux.subscriberaware.SubscriberStatusChanged
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart

/** Provider notifications arrive in bursts (a sync writes many rows); one reload per burst. */
internal const val CALENDAR_CHANGE_DEBOUNCE_MILLIS = 500L

/**
 * The foreground calendar watcher (TODO.md §4.3, mechanism 1): while the store has
 * subscribers (`SubscriberStatusChanged`, i.e. some UI is visible) **and** calendar access
 * is granted, collect [CalendarChangeSource] — which registers the `ContentObserver` — and
 * turn each debounced burst into [CalendarContentChanged]. When either goes false the
 * collection is cancelled, which unregisters the observer; background changes are the
 * WorkManager trigger's job (PR-11).
 *
 * Becoming active also emits one [CalendarContentChanged] straight away: nothing was
 * observing while the UI was away, so the loaded days may be stale.
 */
@ContributesTo(AppScope::class)
interface CalendarObserverSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    @Provides @IntoSet
    fun calendarObserver(changeSource: CalendarChangeSource): SideEffect<AppState> = sideEffect {
        var subscribersActive = false
        var calendarGranted: Boolean? = null
        actions
            .mapNotNull { action ->
                when (action) {
                    is SubscriberStatusChanged -> subscribersActive = action.subscribersActive
                    // read from the action itself rather than the state, so this never
                    // depends on whether the reducer has run yet
                    is SetPermissions -> calendarGranted = action.permissions.calendarGranted
                    else -> return@mapNotNull null
                }
                subscribersActive && (calendarGranted ?: currentState().permissions.calendarGranted)
            }
            .distinctUntilChanged()
            .flatMapLatest { active ->
                if (!active) {
                    emptyFlow()
                } else {
                    changeSource.changes()
                        .debounce(CALENDAR_CHANGE_DEBOUNCE_MILLIS)
                        .map { CalendarContentChanged }
                        .onStart { emit(CalendarContentChanged) }
                }
            }
    }
}

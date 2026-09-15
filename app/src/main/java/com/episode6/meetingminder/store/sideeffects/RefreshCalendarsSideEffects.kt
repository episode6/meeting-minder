package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.calendar.CalendarSyncRequester
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.RefreshCalendars
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.UiMessage
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

/**
 * The app bar's "Refresh" button ([RefreshCalendars]): a sync request for every account's
 * calendars ([CalendarSyncRequester]) plus an immediate reload of the loaded window and
 * the calendar list ([CalendarContentChanged], which also reruns the change check and the
 * alarm maintenance, as any provider change does), with a snackbar so the tap is seen to
 * do something. The request itself is fire-and-forget: the sync's writes arrive through
 * the `ContentObserver` like any other change.
 */
@ContributesTo(AppScope::class)
interface RefreshCalendarsSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun refreshCalendars(syncRequester: CalendarSyncRequester): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<RefreshCalendars>().flatMapMerge {
            flow<Action> {
                syncRequester.requestSync()
                emit(ShowMessage(UiMessage.next(R.string.refresh_started)))
                emit(CalendarContentChanged)
            }
        }
    }
}

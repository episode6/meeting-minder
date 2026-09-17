package com.episode6.meetingminder.store.sideeffects

import android.util.Log
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.calendar.CalendarSyncRequester
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.EnableCalendarSync
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.ShowMessage
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

private const val TAG = "MeetingMinderBusySync"

/**
 * [EnableCalendarSync] (TODO.md §4.7): the calendar the user picked as the busy-calendar
 * sync's target has `SYNC_EVENTS` off, so blocks written to it would never be uploaded.
 * Turns the flag on ([CalendarRepository.enableCalendarSync]), asks the sync framework to
 * sync now ([CalendarSyncRequester]) and reloads the calendar list
 * ([CalendarContentChanged]) so the row loses its "not syncing" label and the sync becomes
 * effective. A calendar that is gone or a refused write is a snackbar; a `SecurityException`
 * re-checks permissions the way every other provider write does.
 */
@ContributesTo(AppScope::class)
interface EnableCalendarSyncSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun enableCalendarSync(repository: CalendarRepository, syncRequester: CalendarSyncRequester): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<EnableCalendarSync>().flatMapMerge { action ->
            flow<Action> {
                try {
                    if (repository.enableCalendarSync(action.calendarId)) {
                        syncRequester.requestSync()
                        emit(CalendarContentChanged)
                    } else {
                        emit(ShowMessage(UiMessage.next(R.string.busy_sync_enable_calendar_failed)))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    Log.w(TAG, "turning on sync for calendar ${action.calendarId} lost calendar access", e)
                    emit(PermissionsMaybeChanged)
                } catch (e: Exception) {
                    Log.w(TAG, "turning on sync for calendar ${action.calendarId} failed", e)
                    emit(ShowMessage(UiMessage.next(R.string.busy_sync_enable_calendar_failed)))
                }
            }
        }
    }
}

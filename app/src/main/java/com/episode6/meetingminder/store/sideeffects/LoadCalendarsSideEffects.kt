package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.LoadDay
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.SetCalendars
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformLatest

/**
 * Reads every calendar row into [AppState.calendars]: on every [CalendarContentChanged]
 * (a calendar may have been added, hidden or recoloured), and on [LoadDay] only while none
 * have been read yet, so paging between days doesn't re-read the list each time. Settings
 * (PR-12) is the list's main consumer.
 */
@ContributesTo(AppScope::class)
interface LoadCalendarsSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun loadCalendars(repository: CalendarRepository): SideEffect<AppState> = sideEffect {
        actions
            .filter { it is CalendarContentChanged || (it is LoadDay && currentState().calendars.isEmpty()) }
            .transformLatest {
                if (!currentState().permissions.calendarGranted) return@transformLatest
                try {
                    emit(SetCalendars(repository.calendars()))
                } catch (_: SecurityException) {
                    emit(PermissionsMaybeChanged)
                }
            }
    }
}

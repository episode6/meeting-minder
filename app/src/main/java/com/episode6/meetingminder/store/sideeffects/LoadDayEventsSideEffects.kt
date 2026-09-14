package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.LoadDay
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.SetDayEvents
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformLatest
import java.time.Clock
import java.time.LocalDate

/**
 * Loads the pager's window (TODO.md §3.5): on [LoadDay] the settled date and the day either
 * side of it, on [CalendarContentChanged] the same window around the current settled date.
 * The settled day is read first so the visible page fills in before its neighbours.
 *
 * `transformLatest` means a newer request cancels an in-flight load, so paging through
 * days never queues up reloads of days already left behind (and [SetDayEvents] drops any
 * result that lands outside the window anyway). Nothing is read without calendar access;
 * a `SecurityException` (access revoked under us) triggers a permission re-check instead.
 */
@ContributesTo(AppScope::class)
interface LoadDayEventsSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun loadDayEvents(repository: CalendarRepository, clock: Clock): SideEffect<AppState> = sideEffect {
        actions
            .filter { it is LoadDay || it is CalendarContentChanged }
            .transformLatest { action ->
                val state = currentState()
                if (!state.permissions.calendarGranted) return@transformLatest
                val center = (action as? LoadDay)?.date ?: state.settledDate
                try {
                    for (date in windowLoadOrder(center)) {
                        emit(SetDayEvents(DayEvents(date, repository.eventsOn(date), clock.instant())))
                    }
                } catch (_: SecurityException) {
                    emit(PermissionsMaybeChanged)
                }
            }
    }
}

/** The loaded window around [center], visible page first. */
internal fun windowLoadOrder(center: LocalDate): List<LocalDate> =
    listOf(center, center.plusDays(1), center.minusDays(1))

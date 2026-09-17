package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.data.calendar.CalendarFilter
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.calendar.effectiveCalendarFilter
import com.episode6.meetingminder.data.calendar.excludeDeclined
import com.episode6.meetingminder.data.calendar.excludeOwnBlocks
import com.episode6.meetingminder.data.db.BusyBlockDao
import com.episode6.meetingminder.data.settings.SettingsRepository
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
 *
 * The calendar filter and the "show declined" toggle (TODO.md §5 PR-12) are read fresh on
 * every window load, not observed: `SettingsViewModel` dispatches [CalendarContentChanged]
 * after any Settings change that affects which events show, which reruns this the same way
 * a provider change would. [AppState.calendars] is empty in a cold process until
 * `LoadCalendarsSideEffects`' `SetCalendars` lands, so with a calendar override stored the
 * filter is read straight from the provider ([CalendarRepository.calendars]) rather than
 * `state.calendars`, the same fallback `monitor.ChangeMonitor.runCheck` uses — otherwise
 * `effectiveCalendarFilter` would compute `CalendarFilter.Only(emptySet())` against an
 * empty calendar list and load the window with zero events.
 *
 * The busy blocks the app wrote itself are dropped here ([excludeOwnBlocks], TODO.md
 * §4.7) — the chosen calendar is usually visible, and a block that reached the timeline
 * could be selected and fold straight back into the next share.
 */
@ContributesTo(AppScope::class)
interface LoadDayEventsSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun loadDayEvents(
        repository: CalendarRepository,
        clock: Clock,
        settings: SettingsRepository,
        busyBlockDao: BusyBlockDao,
    ): SideEffect<AppState> = sideEffect {
        actions
            .filter { it is LoadDay || it is CalendarContentChanged }
            .transformLatest { action ->
                val state = currentState()
                if (!state.permissions.calendarGranted) return@transformLatest
                val center = (action as? LoadDay)?.date ?: state.settledDate
                val prefs = settings.current()
                // computed only when an override exists, same as ChangeMonitor.runCheck: state.calendars
                // can still be empty in a cold process, and Only(emptySet()) would load nothing
                val filter = if (prefs.calendarOverrides.isEmpty()) {
                    CalendarFilter.Visible
                } else {
                    effectiveCalendarFilter(repository.calendars(), prefs.calendarOverrides)
                }
                // the busy blocks the app wrote (TODO.md §4.7) never reach the itinerary,
                // the counts or a selection: read once per window load, like the filter
                val ownBlocks = busyBlockDao.eventIds()
                try {
                    for (date in windowLoadOrder(center)) {
                        val events = repository.eventsOn(date, filter).excludeDeclined(prefs.showDeclined).excludeOwnBlocks(ownBlocks)
                        emit(SetDayEvents(DayEvents(date, events, clock.instant())))
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

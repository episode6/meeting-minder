package com.episode6.meetingminder.store

import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.permissions.PermissionState
import com.episode6.redux.Action
import java.time.LocalDate

/**
 * The only actions [reduce] touches. Everything else is an [AsyncAction] handled by a
 * side effect, which in turn dispatches these to change state.
 */
sealed interface UpdateStateAction : Action

/** The day pager settled on [date] (including after the "Today" action scrolls it back). */
data class SetSettledDate(val date: LocalDate) : UpdateStateAction

/** Replaces [AppState.permissions] with a freshly re-checked value; see [PermissionsMaybeChanged]. */
data class SetPermissions(val permissions: PermissionState) : UpdateStateAction

/** Replaces [AppState.calendars] with a fresh read of every calendar row. */
data class SetCalendars(val calendars: List<CalendarInfo>) : UpdateStateAction

/**
 * Stores one day's freshly loaded events. Only days inside [AppState.loadedWindow] (the
 * settled date ± 1) are kept: a result for a day the pager has since left behind is
 * dropped, and so is any cached day that has fallen outside the window.
 */
data class SetDayEvents(val dayEvents: DayEvents) : UpdateStateAction

/** Shows [message] as a snackbar, replacing any message still pending. */
data class ShowMessage(val message: UiMessage) : UpdateStateAction

/** Clears the pending message, but only if it is still the one with [id]. */
data class ClearMessage(val id: Long) : UpdateStateAction

/**
 * Requests handled only by side effects under `store/sideeffects/` (never by the
 * reducer); see TODO.md §3.2 for the full list.
 */
sealed interface AsyncAction : Action

/**
 * Re-check OS permission grants and dispatch [SetPermissions] with the result. Dispatched
 * on every `ON_RESUME` (`Navigation.kt`) and right after a permission request or an "Open
 * settings" trip returns, since auto-revoke, hibernation and the system Settings app can
 * all change grants behind our back. Also emitted when a calendar read throws
 * `SecurityException`.
 */
data object PermissionsMaybeChanged : AsyncAction

/**
 * The pager settled on [date]: (re)load the events of [date] and of the day either side
 * (the pages `beyondViewportPageCount = 1` keeps composed), plus the calendar list if it
 * hasn't been read yet. Dispatched once per settled page, never per page a fling passes.
 */
data class LoadDay(val date: LocalDate) : AsyncAction

/**
 * The Calendar Provider changed (debounced), or the UI just became visible again after
 * changes may have been missed: reload the calendars and the loaded window around
 * [AppState.settledDate].
 */
data object CalendarContentChanged : AsyncAction

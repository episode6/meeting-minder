package com.episode6.meetingminder.store

import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.DayPlan
import com.episode6.meetingminder.model.EventKey
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

/**
 * Replaces [AppState.dayPlans] with a fresh read of `day_plan` + `selected_event`
 * (`ObserveDayPlansSideEffects`), full-replace like [SetCalendars] since the underlying
 * DAO flows already emit the complete table on every change.
 */
data class SetDayPlans(val dayPlans: Map<LocalDate, DayPlan>) : UpdateStateAction

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

/**
 * The user tapped an event chip on [date]: flip its selection ("I'm going to this") and
 * persist the change to `selected_event` (`ToggleEventSideEffects`). [key] alone doesn't
 * say which page's chip was tapped, since the same occurrence can appear on two adjacent
 * days' timelines (an event crossing midnight) with its own selection on each.
 */
data class ToggleEvent(val date: LocalDate, val key: EventKey) : AsyncAction

/**
 * The user tapped "Set alarms (N)" on [date]: make `scheduled_alarm` match the day's
 * selection (`ScheduleAlarmsSideEffects`, TODO.md §4.4) — cancel alarms for deselected
 * events, arm new ones, re-time moved ones, skip (and count in the snackbar) any whose
 * alarm time has already passed — and record `alarms_set_at`. The same reconcile fans out
 * one [RsvpAccept] per newly-armed event whose `rsvpDecision` is `PENDING` (TODO.md §4.6).
 */
data class SetAlarms(val date: LocalDate) : AsyncAction

/**
 * Mark [key]'s occurrence on [date] "Yes, going" on the calendar
 * (`RsvpAcceptSideEffects` → `CalendarRepository.acceptInstance`), then report back with
 * [RsvpAccepted]. Only ever dispatched by the "Set alarms" reconcile, for an event it just
 * armed; alarm scheduling never waits on it. Carries [date] like [ToggleEvent] does, since
 * the event is looked up in that day's loaded events and the state lands on that day's
 * `selected_event` row.
 */
data class RsvpAccept(val date: LocalDate, val key: EventKey) : AsyncAction

/**
 * The provider write for [RsvpAccept] finished: record [result] on the selection's
 * `rsvp_state`/`rsvp_event_id` (`RsvpAcceptSideEffects`; `ObserveDayPlans` streams it
 * back so the chip shows its "sent" tick or "couldn't RSVP" hint).
 */
data class RsvpAccepted(val date: LocalDate, val key: EventKey, val result: RsvpResult) : AsyncAction

/** The outcome of one RSVP write; failures are per event and never retried automatically. */
sealed interface RsvpResult {
    /** Our attendee row now says accepted on the event with [rsvpEventId] (the new exception's id for a recurring occurrence). */
    data class Accepted(val rsvpEventId: Long) : RsvpResult

    /** The provider refused the write, or the event had left the loaded window before it ran. */
    data object Failed : RsvpResult
}

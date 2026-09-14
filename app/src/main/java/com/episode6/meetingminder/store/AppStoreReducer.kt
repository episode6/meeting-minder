package com.episode6.meetingminder.store

import com.episode6.meetingminder.model.DayEvents
import com.episode6.redux.Action

internal fun AppState.reduce(action: Action): AppState = when (action) {
    is UpdateStateAction -> reduceUpdateStateAction(action)
    else -> this
}

private fun AppState.reduceUpdateStateAction(action: UpdateStateAction): AppState = when (action) {
    is SetSettledDate -> copy(settledDate = action.date)
    is SetAnchorDate -> copy(anchorDate = action.date)
    is SetPermissions -> copy(permissions = action.permissions)
    is SetCalendars -> copy(calendars = action.calendars)
    is SetDayEvents -> withDayEvents(action.dayEvents)
    is SetDayPlans -> copy(dayPlans = action.dayPlans)
    is ShowMessage -> copy(transientMessage = action.message)
    is ClearMessage -> if (transientMessage?.id == action.id) copy(transientMessage = null) else this
    // first wins: the wiring layer clears a pending share the moment it takes it, so one
    // still here is a chooser about to open, and a second ShareDay from a fast double tap
    // must not open another on top of it
    is SetPendingShare -> if (pendingShare == null) copy(pendingShare = action.share) else this
    is ClearPendingShare -> if (pendingShare?.id == action.id) copy(pendingShare = null) else this
    is SetRinging -> copy(ringing = action.ringing)
    is SetScheduleChanges -> copy(scheduleChanges = action.changes)
}

private fun AppState.withDayEvents(dayEvents: DayEvents): AppState {
    val window = loadedWindow
    val kept = eventsByDay.filterKeys { it in window }
    return copy(eventsByDay = if (dayEvents.date in window) kept + (dayEvents.date to dayEvents) else kept)
}

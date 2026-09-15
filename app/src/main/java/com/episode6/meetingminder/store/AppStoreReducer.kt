package com.episode6.meetingminder.store

import com.episode6.meetingminder.model.DayEvents
import com.episode6.redux.Action

internal fun AppState.reduce(action: Action): AppState = when (action) {
    is UpdateStateAction -> reduceUpdateStateAction(action)
    else -> this
}

private fun AppState.reduceUpdateStateAction(action: UpdateStateAction): AppState = when (action) {
    is SetSettledDate -> copy(settledDate = action.date)
    is SetPermissions -> copy(permissions = action.permissions)
    is SetCalendars -> copy(calendars = action.calendars)
    is SetDayEvents -> withDayEvents(action.dayEvents)
    is SetDayPlans -> copy(dayPlans = action.dayPlans)
    is ShowMessage -> copy(transientMessage = action.message)
    is ClearMessage -> if (transientMessage?.id == action.id) copy(transientMessage = null) else this
    is SetPendingShare -> copy(pendingShare = action.share)
    is ClearPendingShare -> if (pendingShare?.id == action.id) copy(pendingShare = null) else this
    is SetRinging -> copy(ringing = action.ringing)
}

private fun AppState.withDayEvents(dayEvents: DayEvents): AppState {
    val window = loadedWindow
    val kept = eventsByDay.filterKeys { it in window }
    return copy(eventsByDay = if (dayEvents.date in window) kept + (dayEvents.date to dayEvents) else kept)
}

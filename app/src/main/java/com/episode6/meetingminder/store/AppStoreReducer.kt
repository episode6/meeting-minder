package com.episode6.meetingminder.store

import com.episode6.redux.Action

internal fun AppState.reduce(action: Action): AppState = when (action) {
    is UpdateStateAction -> reduceUpdateStateAction(action)
    else -> this
}

private fun AppState.reduceUpdateStateAction(action: UpdateStateAction): AppState = when (action) {
    is SetSettledDate -> copy(settledDate = action.date)
    is SetPermissions -> copy(permissions = action.permissions)
    is ShowMessage -> copy(transientMessage = action.message)
    is ClearMessage -> if (transientMessage?.id == action.id) copy(transientMessage = null) else this
}

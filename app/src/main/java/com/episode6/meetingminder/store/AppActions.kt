package com.episode6.meetingminder.store

import com.episode6.meetingminder.permissions.PermissionState
import com.episode6.redux.Action
import java.time.LocalDate

/**
 * The only actions [reduce] touches. Everything else is an [AsyncAction] handled by a
 * side effect, which in turn dispatches these to change state.
 */
sealed interface UpdateStateAction : Action

/** The day pager settled on [date] (also used by the "Today" action). */
data class SetSettledDate(val date: LocalDate) : UpdateStateAction

/** Replaces [AppState.permissions] with a freshly re-checked value; see [PermissionsMaybeChanged]. */
data class SetPermissions(val permissions: PermissionState) : UpdateStateAction

/** Shows [message] as a snackbar, replacing any message still pending. */
data class ShowMessage(val message: UiMessage) : UpdateStateAction

/** Clears the pending message, but only if it is still the one with [id]. */
data class ClearMessage(val id: Long) : UpdateStateAction

/**
 * Requests handled only by side effects under `store/sideeffects/` (never by the
 * reducer). The first ones arrive with PR-4 (`PermissionsMaybeChanged`) and PR-6
 * (`LoadDay`, `CalendarContentChanged`); see TODO.md §3.2 for the full list.
 */
sealed interface AsyncAction : Action

/**
 * Re-check OS permission grants and dispatch [SetPermissions] with the result. Dispatched
 * on every `ON_RESUME` (`Navigation.kt`) and right after a permission request or an "Open
 * settings" trip returns, since auto-revoke, hibernation and the system Settings app can
 * all change grants behind our back.
 */
data object PermissionsMaybeChanged : AsyncAction

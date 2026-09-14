package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.permissions.PermissionState
import com.episode6.meetingminder.store.AppState
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import com.episode6.redux.sideeffects.SideEffectContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate

internal val TestAppState = AppState(anchorDate = LocalDate.of(2026, 9, 14))

/** [TestAppState] with calendar access granted, which every calendar-reading effect requires. */
internal val CalendarGrantedAppState = TestAppState.copy(permissions = PermissionState(calendarGranted = true))

/**
 * Runs one side effect in isolation, mockk-free: [input] is the complete `actions`
 * stream and [state] what `currentState()` returns. Collect the result (`toList()`, or
 * Turbine for effects that never complete) and assert with `containsExactly`.
 */
internal fun SideEffect<AppState>.output(vararg input: Action, state: AppState = TestAppState): Flow<Action> =
    output(flowOf(*input), state)

/**
 * Like the vararg overload, but [actions] can be a hot flow (e.g. a `MutableSharedFlow`)
 * the test emits into while collecting, for effects whose behaviour depends on timing.
 */
internal fun SideEffect<AppState>.output(actions: Flow<Action>, state: AppState = TestAppState): Flow<Action> {
    val context = object : SideEffectContext<AppState> {
        override val actions: Flow<Action> = actions
        override suspend fun currentState(): AppState = state
    }
    return with(this) { context.act() }
}

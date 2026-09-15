package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.store.AppState
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import com.episode6.redux.sideeffects.SideEffectContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate

internal val TestAppState = AppState(anchorDate = LocalDate.of(2026, 9, 14))

/**
 * Runs one side effect in isolation, mockk-free: [input] is the complete `actions`
 * stream and [state] what `currentState()` returns. Collect the result (`toList()`, or
 * Turbine for effects that never complete) and assert with `containsExactly`.
 */
internal fun SideEffect<AppState>.output(vararg input: Action, state: AppState = TestAppState): Flow<Action> {
    val context = object : SideEffectContext<AppState> {
        override val actions: Flow<Action> = flowOf(*input)
        override suspend fun currentState(): AppState = state
    }
    return with(this) { context.act() }
}

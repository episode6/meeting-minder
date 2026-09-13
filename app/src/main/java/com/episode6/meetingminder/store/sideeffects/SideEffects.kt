package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.store.AppState
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import com.episode6.redux.sideeffects.SideEffectContext
import kotlinx.coroutines.flow.Flow

/**
 * Convenience builder for a typed [AppState] side effect.
 *
 * Contribute each one from its own file under `store/sideeffects/`:
 *
 * ```kotlin
 * @ContributesTo(AppScope::class)
 * interface LoadDayEventsSideEffects {
 *     @Provides @IntoSet fun loadDayEvents(repo: CalendarRepository): SideEffect<AppState> = sideEffect {
 *         actions.filterIsInstance<LoadDay>().transformLatest { … }
 *     }
 * }
 * ```
 *
 * Two rules every effect must follow, because [com.episode6.redux.sideeffects.SideEffectMiddleware]
 * only relays an action once *every* effect is subscribed to `actions`:
 * - an observe-only effect (e.g. streaming a Room flow) must still subscribe to
 *   `actions`: `merge(actions.filter { false }, dao.observe().map { … })`;
 * - never suspend inline in the relay path; do IO inside `flatMapMerge`/`transformLatest`.
 *
 * Either mistake starves every other effect.
 */
internal fun sideEffect(effect: SideEffectContext<AppState>.() -> Flow<Action>): SideEffect<AppState> =
    SideEffect { effect() }

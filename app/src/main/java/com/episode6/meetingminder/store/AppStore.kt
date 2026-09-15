package com.episode6.meetingminder.store

import com.episode6.redux.StoreFlow
import com.episode6.redux.sideeffects.SideEffect
import com.episode6.redux.sideeffects.SideEffectMiddleware
import com.episode6.redux.subscriberaware.SubscriberAwareStoreFlow
import kotlinx.coroutines.CoroutineScope

typealias AppStore = StoreFlow<AppState>

/**
 * Builds the app store exactly as the graph provides it, so store tests exercise the
 * production wiring. Subscriber-aware so the calendar `ContentObserver` (PR-6) can be
 * registered only while something (visible UI) is collecting the store.
 */
fun createAppStore(
    scope: CoroutineScope,
    initialState: AppState,
    sideEffects: Set<SideEffect<AppState>>,
): AppStore = SubscriberAwareStoreFlow(
    scope = scope,
    initialValue = initialState,
    reducer = AppState::reduce,
    middlewares = listOf(SideEffectMiddleware(sideEffects)),
)

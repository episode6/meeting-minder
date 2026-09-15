package com.episode6.meetingminder.store

import com.episode6.redux.Action
import com.episode6.redux.StoreFlow
import com.episode6.redux.sideeffects.SideEffect
import com.episode6.redux.sideeffects.SideEffectMiddleware
import com.episode6.redux.subscriberaware.SubscriberStatusChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.shareIn

typealias AppStore = StoreFlow<AppState>

/**
 * Builds the app store exactly as the graph provides it, so store tests exercise the
 * production wiring. Subscriber-aware, like redux-store-flow's `SubscriberAwareStoreFlow`:
 * [SubscriberStatusChanged] is dispatched when the first collector arrives and when the
 * last one leaves, which is how the calendar `ContentObserver`
 * (`CalendarObserverSideEffects`) is registered only while something (visible UI) is
 * collecting the store.
 *
 * Built here rather than with the library function because of how each collector gets
 * the current state. The shared flow has `replay = 0` (a `WhileSubscribed` share would
 * otherwise replay a stale value after a restart), so a new collector must be handed the
 * current state explicitly. The library does that with `onStart { emit(store.state) }`,
 * which runs *before* the collector is registered with the shared flow: any state change
 * reduced between that emission and the registration is emitted to nobody and never
 * reaches the collector, which then shows the old state until the store changes again.
 * The window is not small — `combine` (see `DayViewModel`) calls `yield()` after every
 * value, so on `Dispatchers.Main` the registration waits behind whatever the main thread
 * is doing, e.g. the first frame's layout, which is exactly when the launch loads land.
 * [onSubscription] runs *after* the registration, so a change made while the current
 * state is being handed over is buffered for the collector instead of lost.
 * `AppStoreTest.collectorThatSuspendsOnTheFirstState_stillReceivesAChangeMadeMeanwhile`
 * pins this.
 */
fun createAppStore(
    scope: CoroutineScope,
    initialState: AppState,
    sideEffects: Set<SideEffect<AppState>>,
): AppStore {
    val store = StoreFlow(
        scope = scope,
        initialValue = initialState,
        reducer = AppState::reduce,
        middlewares = listOf(SideEffectMiddleware(sideEffects)),
    )
    val flow = store
        .onStart { store.dispatch(SubscriberStatusChanged(true)) }
        .onCompletion { store.dispatch(SubscriberStatusChanged(false)) }
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)
        .onSubscription { emit(store.state) }
        .distinctUntilChanged()
    return object : AppStore, Flow<AppState> by flow {
        override val initialState: AppState get() = store.initialState
        override val state: AppState get() = store.state
        override fun dispatch(action: Action) = store.dispatch(action)
    }
}

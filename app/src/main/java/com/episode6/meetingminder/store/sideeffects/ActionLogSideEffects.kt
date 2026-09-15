package com.episode6.meetingminder.store.sideeffects

import android.util.Log
import com.episode6.meetingminder.store.AppState
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach

private const val TAG = "MeetingMinderStore"

/**
 * A no-op side effect: it never emits an action back into the store. It exists as the
 * reference `@IntoSet` contribution (the graph's `Set<SideEffect<AppState>>` is never
 * empty) and as a debugging aid: `adb shell setprop log.tag.MeetingMinderStore DEBUG`
 * logs each dispatched action's type. Only the type is logged, never the payload, so
 * event titles never reach logcat.
 */
@ContributesTo(AppScope::class)
interface ActionLogSideEffects {
    @Provides @IntoSet
    fun actionLog(): SideEffect<AppState> = sideEffect {
        actions
            .onEach { if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "dispatched ${it::class.simpleName}") }
            .filter { false }
    }
}

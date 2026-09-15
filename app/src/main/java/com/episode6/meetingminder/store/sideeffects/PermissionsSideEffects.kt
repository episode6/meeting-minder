package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.permissions.PermissionChecker
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.SetPermissions
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * Re-checks OS permission grants on every [PermissionsMaybeChanged] and pushes the
 * result into the store as [SetPermissions]. The store's very first value is computed
 * synchronously in `di/AppGraph.kt` instead (so launch routing never flashes the wrong
 * screen); this effect only handles later refreshes.
 */
@ContributesTo(AppScope::class)
interface PermissionsSideEffects {
    @Provides @IntoSet
    fun permissions(checker: PermissionChecker): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<PermissionsMaybeChanged>().map { SetPermissions(checker.currentState()) }
    }
}

package com.episode6.meetingminder.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.AppStore
import com.episode6.meetingminder.store.createAppStore
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.LocalDate

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * The app-scoped graph. Receivers, services and workers reach it via `Context.appGraph`;
 * Composables only ever see ViewModels, created through [metroViewModelFactory].
 *
 * The Room database provider arrives with its first table in PR-7: Room refuses to
 * compile a database with no entities.
 */
@DependencyGraph(AppScope::class)
@SingleIn(AppScope::class)
interface AppGraph : ViewModelGraph {

    val appStore: AppStore

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AppGraph
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideAppCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @SingleIn(AppScope::class)
    fun provideSettingsDataStore(context: Context): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @SingleIn(AppScope::class)
    fun provideAppStore(scope: CoroutineScope, sideEffects: Set<SideEffect<AppState>>): AppStore =
        createAppStore(scope = scope, initialState = AppState(anchorDate = LocalDate.now()), sideEffects = sideEffects)
}

package com.episode6.meetingminder.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.episode6.meetingminder.alarm.AlarmRescheduler
import com.episode6.meetingminder.alarm.FiredAlarmHandler
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.permissions.PermissionChecker
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
import java.time.Clock
import java.time.LocalDate

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * The app-scoped graph. Receivers, services and workers reach it via `Context.appGraph`;
 * Composables only ever see ViewModels, created through [metroViewModelFactory].
 */
@DependencyGraph(AppScope::class)
@SingleIn(AppScope::class)
interface AppGraph : ViewModelGraph {

    val appStore: AppStore

    /** Bound in [CalendarModule]; the change-detection worker (PR-11) reads it from here. */
    val calendarRepository: CalendarRepository

    /** For receivers: work that must outlive `onReceive` (under `goAsync()`) runs here. */
    val appCoroutineScope: CoroutineScope

    /** `BootReceiver`'s re-arm of every stored alarm. */
    val alarmRescheduler: AlarmRescheduler

    /** `AlarmReceiver`'s handling of a fired alarm. */
    val firedAlarmHandler: FiredAlarmHandler

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AppGraph
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideAppCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The wall clock, in the device's zone. Deliberately unscoped: every injection reads the
     * zone afresh, so a ViewModel created after a timezone change sees the new one.
     */
    @Provides
    fun provideClock(): Clock = Clock.systemDefaultZone()

    @Provides
    @SingleIn(AppScope::class)
    fun provideSettingsDataStore(context: Context): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @SingleIn(AppScope::class)
    fun provideAppStore(
        scope: CoroutineScope,
        sideEffects: Set<SideEffect<AppState>>,
        permissionChecker: PermissionChecker,
    ): AppStore =
        createAppStore(
            scope = scope,
            // Computed synchronously (not via PermissionsMaybeChanged) so the very first
            // composition already knows whether to route to Onboarding or Day.
            initialState = AppState(anchorDate = LocalDate.now(), permissions = permissionChecker.currentState()),
            sideEffects = sideEffects,
        )
}

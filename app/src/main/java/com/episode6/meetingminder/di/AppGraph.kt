package com.episode6.meetingminder.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.episode6.meetingminder.alarm.AlarmMaintainer
import com.episode6.meetingminder.alarm.AlarmRescheduler
import com.episode6.meetingminder.alarm.AlarmRinger
import com.episode6.meetingminder.alarm.AlarmScheduler
import com.episode6.meetingminder.alarm.RecentAlarmSounds
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.db.BusyBlockDao
import com.episode6.meetingminder.data.db.ChangeSnapshotDao
import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import com.episode6.meetingminder.monitor.ChangeMonitor
import com.episode6.meetingminder.data.settings.SettingsRepository
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

    val calendarRepository: CalendarRepository

    /** `CalendarChangeWorker`'s change check (TODO.md §4.3). */
    val changeMonitor: ChangeMonitor

    /** For `CalendarChangeWorkerTest`, which seeds a shared day's baseline the way a share does. */
    val changeSnapshotDao: ChangeSnapshotDao

    /** For `BusyCalendarSyncDeviceTest`, which reads back (and tidies up) what a real sync recorded. */
    val busyBlockDao: BusyBlockDao

    /** For receivers: work that must outlive `onReceive` (under `goAsync()`) runs here. */
    val appCoroutineScope: CoroutineScope

    /** `BootReceiver`'s re-arm of every stored alarm. */
    val alarmRescheduler: AlarmRescheduler

    /** `BootReceiver`'s re-timing of armed alarms whose meetings moved after boot or a clock/timezone change. */
    val alarmMaintainer: AlarmMaintainer

    /** `AlarmRingingService`'s row transitions (fire, snooze, dismiss, auto-timeout), and `AlarmReceiver`'s fallback. */
    val alarmRinger: AlarmRinger

    /** The ringing service's sound player reads these two. */
    val recentAlarmSounds: RecentAlarmSounds
    val settingsRepository: SettingsRepository

    /** For `AlarmRingingDeviceTest`, which arms a real alarm the way the reconcile does: a row, then `setAlarmClock`. */
    val scheduledAlarmDao: ScheduledAlarmDao
    val alarmScheduler: AlarmScheduler

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AppGraph
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideAppCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The wall clock, in the device's zone as it is at each call ([DeviceClock]): app-scoped
     * singletons hold their `Clock` for the life of the process, so a clock that captured
     * the zone once would leave them in the old zone after a timezone change.
     */
    @Provides
    fun provideClock(): Clock = DeviceClock

    @Provides
    @SingleIn(AppScope::class)
    fun provideSettingsDataStore(context: Context): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @SingleIn(AppScope::class)
    fun provideAppStore(
        scope: CoroutineScope,
        sideEffects: Set<SideEffect<AppState>>,
        permissionChecker: PermissionChecker,
        clock: Clock,
    ): AppStore =
        createAppStore(
            scope = scope,
            // Computed synchronously (not via PermissionsMaybeChanged) so the very first
            // composition already knows whether to route to Onboarding or Day.
            initialState = AppState(anchorDate = LocalDate.now(clock), permissions = permissionChecker.currentState()),
            sideEffects = sideEffects,
        )
}

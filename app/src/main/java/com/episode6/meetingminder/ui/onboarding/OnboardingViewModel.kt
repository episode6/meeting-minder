package com.episode6.meetingminder.ui.onboarding

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.permissions.SleepyManufacturer
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.AppStore
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.redux.mapStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MILLIS = 5_000L

/**
 * [OnboardingScreen]'s store adapter. The permission-request launcher itself is UI
 * framework plumbing wired in `Navigation.kt` (the designated place for launchers); this
 * ViewModel only turns the store's permission state into UI state, re-triggers the check
 * after a request or an "Open settings" trip returns, and keeps the launcher's
 * "asked before" memory ([SettingsRepository.requestedPermissions]).
 */
@Inject
@ViewModelKey(OnboardingViewModel::class)
@ContributesIntoMap(AppScope::class)
class OnboardingViewModel(private val store: AppStore, private val settings: SettingsRepository) : ViewModel() {

    // the device can't change maker, so this is read once
    private val sleepyManufacturer = SleepyManufacturer.of(Build.MANUFACTURER)

    val state: StateFlow<OnboardingUiState> = store
        .mapStore { it.toOnboardingUiState(sleepyManufacturer) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            store.state.toOnboardingUiState(sleepyManufacturer),
        )

    /** The runtime permissions ever requested, for the "two denials → Open settings" detection in `Navigation.kt`. */
    val requestedPermissions: StateFlow<Set<String>> = settings.requestedPermissions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptySet())

    fun onPermissionsMaybeChanged() {
        store.dispatch(PermissionsMaybeChanged)
    }

    /** The launcher is about to show the system dialog for [permission]. */
    fun onPermissionRequested(permission: String) {
        viewModelScope.launch { settings.markPermissionRequested(permission) }
    }
}

internal fun AppState.toOnboardingUiState(sleepyManufacturer: SleepyManufacturer? = null) = OnboardingUiState(
    calendarGranted = permissions.calendarGranted,
    notificationsGranted = permissions.notificationsGranted,
    exactAlarmsGranted = permissions.exactAlarmsGranted,
    fullScreenAlarmsGranted = permissions.fullScreenIntentGranted,
    batteryOptimizationIgnored = permissions.ignoringBatteryOptimizations,
    backgroundRestricted = permissions.backgroundRestricted,
    sleepyManufacturer = sleepyManufacturer,
)

package com.episode6.meetingminder.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

private const val STOP_TIMEOUT_MILLIS = 5_000L

/**
 * [OnboardingScreen]'s store adapter. The permission-request launcher itself is UI
 * framework plumbing wired in `Navigation.kt` (the designated place for launchers); this
 * ViewModel only turns the store's permission state into UI state and re-triggers the
 * check after a request or an "Open settings" trip returns.
 */
@Inject
@ViewModelKey(OnboardingViewModel::class)
@ContributesIntoMap(AppScope::class)
class OnboardingViewModel(private val store: AppStore) : ViewModel() {

    val state: StateFlow<OnboardingUiState> = store
        .mapStore { it.toOnboardingUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), store.state.toOnboardingUiState())

    fun onPermissionsMaybeChanged() {
        store.dispatch(PermissionsMaybeChanged)
    }
}

internal fun AppState.toOnboardingUiState() = OnboardingUiState(
    calendarGranted = permissions.calendarGranted,
    notificationsGranted = permissions.notificationsGranted,
    exactAlarmsGranted = permissions.exactAlarmsGranted,
    fullScreenAlarmsGranted = permissions.fullScreenIntentGranted,
)

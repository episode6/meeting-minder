package com.episode6.meetingminder.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * [MeetingMinderNavigation]'s store adapter (AGENTS.md: "Composables do not see the
 * store... Only ViewModels (and non-UI components) touch the store"). Reached via
 * `metroViewModel()` at the top of the wiring layer, this is the only thing that lets
 * `Navigation.kt` decide the start destination and react to a permission grant changing
 * without importing `appGraph` or dispatching directly.
 */
@Inject
@ViewModelKey(NavigationViewModel::class)
@ContributesIntoMap(AppScope::class)
class NavigationViewModel(private val store: AppStore) : ViewModel() {

    /**
     * [com.episode6.meetingminder.di.AppGraph] seeds `AppState.permissions` synchronously,
     * so the initial value here is already correct and the very first composition never
     * flashes the wrong screen.
     */
    val calendarGranted: StateFlow<Boolean> = store
        .mapStore { it.permissions.calendarGranted }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), store.state.permissions.calendarGranted)

    /**
     * Auto-revoke/hibernation and a trip to system Settings can change grants without any
     * action of ours, so the wiring layer calls this on every `ON_RESUME`, app-wide (not
     * just while Onboarding is shown).
     */
    fun onResumed() {
        store.dispatch(PermissionsMaybeChanged)
    }
}

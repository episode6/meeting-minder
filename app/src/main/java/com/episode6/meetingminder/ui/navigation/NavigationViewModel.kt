package com.episode6.meetingminder.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.episode6.meetingminder.store.AppStore
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.ShareDay
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
 * `Navigation.kt` decide the start destination, react to a permission grant changing and
 * act on a deep link without importing `appGraph` or dispatching directly.
 */
@Inject
@ViewModelKey(NavigationViewModel::class)
@ContributesIntoMap(AppScope::class)
class NavigationViewModel(private val store: AppStore) : ViewModel() {

    /**
     * Whether every required onboarding row (calendar, notifications, exact alarms; TODO.md
     * §4.5) is granted — the routing decision between Day and Onboarding.
     * [com.episode6.meetingminder.di.AppGraph] seeds `AppState.permissions` synchronously,
     * so the initial value here is already correct and the very first composition never
     * flashes the wrong screen.
     */
    val requiredPermissionsGranted: StateFlow<Boolean> = store
        .mapStore { it.permissions.allRequiredGranted }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), store.state.permissions.allRequiredGranted)

    /**
     * Auto-revoke/hibernation and a trip to system Settings can change grants without any
     * action of ours, so the wiring layer calls this on every `ON_RESUME`, app-wide (not
     * just while Onboarding is shown).
     */
    fun onResumed() {
        store.dispatch(PermissionsMaybeChanged)
    }

    /**
     * A notification opened [link] (TODO.md §4.3). Returns whether the wiring layer should
     * show the link's day: not while a required grant is missing (Onboarding comes first,
     * and the link is dropped). "Share update" ([DeepLink.Share]) dispatches the share right
     * away: `ShareDaySideEffects` reads what it needs from Room and the provider, so it
     * doesn't wait for the day to load, and the chooser opens once the day view is showing.
     */
    fun onDeepLink(link: DeepLink): Boolean {
        if (!store.state.permissions.allRequiredGranted) return false
        if (link is DeepLink.Share) store.dispatch(ShareDay(link.date))
        return true
    }
}

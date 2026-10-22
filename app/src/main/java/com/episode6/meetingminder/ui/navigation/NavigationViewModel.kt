package com.episode6.meetingminder.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.episode6.meetingminder.store.AppStore
import com.episode6.meetingminder.store.DismissAlarm
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.ShareFinished
import com.episode6.meetingminder.store.ShowDay
import com.episode6.meetingminder.store.startShare
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
     *
     * It also ends any share still in flight ([ShareFinished]): the share sheet is an
     * activity above ours, so our activity resuming means no sheet is up any more. The
     * result that normally ends the share (`Navigation.kt`'s launcher) can be lost when
     * the activity is torn down behind the sheet, and the store outlives the activity, so
     * without this a lost result left `AppState.shareInFlight` set for the life of the
     * process and every later "Share schedule" tap silently did nothing. A fast double tap
     * is still caught: nothing resumes between the two taps. The one exception is a cold
     * start from a notification's "Share update" ([DeepLink.Share]): its [onDeepLink] can
     * run before the activity's first `ON_RESUME`, so that resume ends the share while
     * `ShareDaySideEffects` is still formatting the text, and the guard is down until the
     * chooser opens. Accepted: no tap lands during a cold start.
     */
    fun onResumed() {
        store.dispatch(ShareFinished)
        store.dispatch(PermissionsMaybeChanged)
    }

    /**
     * A notification opened [link] (TODO.md §4.3), or the app came to the foreground before
     * today's page had been shown today ([DeepLink.Today]). Returns whether the wiring layer should show the
     * day view: not while a required grant is missing (Onboarding comes first, and the link
     * is dropped). Otherwise the pager is sent to the link's day ([ShowDay]). "Share update"
     * ([DeepLink.Share]) dispatches the share right away: `ShareDaySideEffects` reads what it
     * needs from Room and the provider, so it doesn't wait for the day to load, and the
     * chooser opens once the day view is showing.
     *
     * A notification's link into a day whose schedule-change alert is ringing answers the
     * alert: its "Open itinerary" and "Sync & Re-share" come straight here (an action can't
     * go through the ringing service first, that would be a trampoline), so this is what
     * stops the sound. Merely opening the app on today doesn't.
     */
    fun onDeepLink(link: DeepLink): Boolean {
        if (link !is DeepLink.Today) {
            store.state.ringing?.takeIf { it.scheduleChange != null && it.date == link.date }?.let { store.dispatch(DismissAlarm(it.alarmId)) }
        }
        if (!store.state.permissions.allRequiredGranted) return false
        store.dispatch(ShowDay(link.date))
        if (link is DeepLink.Share) store.startShare(link.date)
        return true
    }
}

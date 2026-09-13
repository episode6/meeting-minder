package com.episode6.meetingminder.ui.day

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.AppStore
import com.episode6.meetingminder.store.ClearMessage
import com.episode6.meetingminder.store.SetSettledDate
import com.episode6.meetingminder.store.UiMessage
import com.episode6.redux.mapStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val STOP_TIMEOUT_MILLIS = 5_000L

/**
 * The thin store adapter for [DayScreen] — the pattern every screen's ViewModel copies:
 * derive an immutable UI state with `mapStore { … }.stateIn(…)`, expose `on…` callbacks
 * that dispatch, and turn the store's `transientMessage` into a one-shot flow.
 */
@Inject
@ViewModelKey(DayViewModel::class)
@ContributesIntoMap(AppScope::class)
class DayViewModel(private val store: AppStore) : ViewModel() {

    val state: StateFlow<DayUiState> = store
        .mapStore { it.toDayUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), store.state.toDayUiState())

    /** Each pending snackbar message once; call [onMessageShown] as it is displayed. */
    val messages: Flow<UiMessage> = store
        .map { it.transientMessage }
        .filterNotNull()
        .distinctUntilChanged { old, new -> old.id == new.id }

    fun onTodayClick() {
        store.dispatch(SetSettledDate(store.state.anchorDate))
    }

    fun onMessageShown(message: UiMessage) {
        store.dispatch(ClearMessage(message.id))
    }
}

internal fun AppState.toDayUiState() = DayUiState(
    date = settledDate,
    isToday = settledDate == anchorDate,
)

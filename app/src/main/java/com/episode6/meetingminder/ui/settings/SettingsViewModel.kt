package com.episode6.meetingminder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.episode6.meetingminder.data.calendar.defaultBusyCalendar
import com.episode6.meetingminder.data.calendar.insertable
import com.episode6.meetingminder.data.calendar.writable
import com.episode6.meetingminder.data.settings.Settings
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.data.settings.AlarmSoundPool
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.permissions.PermissionState
import com.episode6.meetingminder.store.AppStore
import com.episode6.meetingminder.store.BusySyncSettingChanged
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.ClearMessage
import com.episode6.meetingminder.store.EnableCalendarSync
import com.episode6.meetingminder.store.TestAlarm
import com.episode6.meetingminder.store.UiMessage
import com.episode6.redux.mapStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration

private const val STOP_TIMEOUT_MILLIS = 5_000L

/** One row of the Settings → Calendars list: [included] is [Settings.calendarOverrides]`[info.id] ?: info.visible`. */
data class CalendarRow(val info: CalendarInfo, val included: Boolean)

/**
 * The "Permissions" row's subtitle (TODO.md §5 PR-12: "permissions status re-entry to
 * onboarding"), derived from [PermissionState]. [MissingSome.count] excludes calendar
 * access from the granted-count denominator implicitly by counting only the flags that
 * are false, so it grows to cover any new required permission without needing an update.
 * [BackgroundRestricted] is the one optional problem worth surfacing here (TODO.md §5
 * PR-13's "restricted standby bucket warning"): everything required is granted, but Android
 * is holding the app back in the background. A missing required grant always wins.
 */
sealed interface PermissionsStatus {
    data object AllGranted : PermissionsStatus
    data object BackgroundRestricted : PermissionsStatus
    data class MissingSome(val count: Int) : PermissionsStatus
}

internal fun PermissionState.toStatus(): PermissionsStatus {
    val missing = listOf(calendarGranted, notificationsGranted, exactAlarmsGranted, fullScreenIntentGranted).count { !it }
    return when {
        missing > 0 -> PermissionsStatus.MissingSome(missing)
        backgroundRestricted -> PermissionsStatus.BackgroundRestricted
        else -> PermissionsStatus.AllGranted
    }
}

/** What [SettingsScreen] renders (TODO.md §5 PR-12): the current [Settings] plus every calendar as a [CalendarRow]. */
data class SettingsUiState(
    val leadTime: Duration = Duration.ZERO,
    val snoozeLength: Duration = Duration.ZERO,
    val autoTimeout: Duration = Duration.ZERO,
    val soundPool: AlarmSoundPool = AlarmSoundPool.ALL,
    val showDeclined: Boolean = true,
    val calendars: List<CalendarRow> = emptyList(),
    val permissionsStatus: PermissionsStatus = PermissionsStatus.AllGranted,
    /** Settings → Busy calendar (TODO.md §4.7): the toggle's state and the calendar it's set to. */
    val busySyncEnabled: Boolean = false,
    val busySyncCalendarId: Long? = null,
    /** The first name busy blocks are titled with ("Geoff busy"); blank keeps the bare `busy`. */
    val busySyncFirstName: String = "",
    /**
     * The calendars the toggle's radio list offers; see
     * [com.episode6.meetingminder.data.calendar.insertable]. One with `SYNC_EVENTS` off is
     * listed too, and picking it turns sync on.
     */
    val busyCalendars: List<CalendarInfo> = emptyList(),
)

/**
 * [SettingsScreen]'s store adapter (TODO.md §5 PR-12). Unlike every other screen this one
 * is driven mostly by [SettingsRepository] (DataStore), not [AppStore]: [AppStore] is only
 * consulted for [AppState.calendars][com.episode6.meetingminder.store.AppState.calendars]
 * (Settings → Calendars) and to dispatch [TestAlarm] and the one-shot snackbar it (and a
 * calendar/declined change) produce. A calendar-override or "show declined" change also
 * dispatches [CalendarContentChanged] — the same action a provider change would — so the
 * day view's loaded window and the change monitor re-read with the new filter immediately
 * (`LoadDayEventsSideEffects`, `monitor.ChangeMonitor`) instead of waiting for the next
 * natural reload.
 */
@Inject
@ViewModelKey(SettingsViewModel::class)
@ContributesIntoMap(AppScope::class)
class SettingsViewModel(private val store: AppStore, private val settings: SettingsRepository) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settings.settings,
        store.mapStore { it.calendars },
        store.mapStore { it.permissions },
    ) { prefs, calendars, permissions ->
        prefs.toUiState(calendars, permissions.toStatus())
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        // DataStore's first emission is asynchronous (unlike the store, it has no
        // synchronous `.state`), so this seeds with the defaults for one frame at most;
        // the real preferences follow almost immediately.
        Settings().toUiState(store.state.calendars, store.state.permissions.toStatus()),
    )

    val messages: Flow<UiMessage> = store
        .map { it.transientMessage }
        .filterNotNull()
        .distinctUntilChanged { old, new -> old.id == new.id }

    fun onLeadTimeSelected(leadTime: Duration) = viewModelScope.launch { settings.setLeadTime(leadTime) }

    fun onSnoozeLengthSelected(snoozeLength: Duration) = viewModelScope.launch { settings.setSnoozeLength(snoozeLength) }

    fun onAutoTimeoutSelected(autoTimeout: Duration) = viewModelScope.launch { settings.setAutoTimeout(autoTimeout) }

    fun onSoundPoolSelected(soundPool: AlarmSoundPool) = viewModelScope.launch { settings.setSoundPool(soundPool) }

    fun onShowDeclinedToggle(showDeclined: Boolean) = viewModelScope.launch {
        settings.setShowDeclined(showDeclined)
        store.dispatch(CalendarContentChanged)
    }

    /**
     * Stores `included` as [Settings.calendarOverrides]`[calendar.id]`, unless it now
     * matches [CalendarInfo.visible] — the provider's own flag — in which case the
     * override is cleared instead, so a calendar toggled back to its default tracks any
     * later change to the provider's own `VISIBLE` flag rather than staying pinned.
     */
    fun onCalendarToggle(calendar: CalendarInfo, included: Boolean) = viewModelScope.launch {
        settings.setCalendarOverride(calendar.id, included.takeIf { it != calendar.visible })
        store.dispatch(CalendarContentChanged)
    }

    /**
     * Settings → Busy calendar's toggle (TODO.md §4.7). Turning it on with no calendar
     * chosen yet applies [defaultBusyCalendar] (the "Family" default) and writes its id
     * explicitly, as one [SettingsRepository.setBusySync] edit so a collector never sees the
     * toggle on with no calendar chosen in between. Either way, [BusySyncSettingChanged] is
     * dispatched, with the calendar before and after, so PR-15c's cleanup side effect can
     * react (to the toggle going off; a toggle-on changes nothing on the calendar).
     */
    fun onBusySyncToggle(enabled: Boolean) = viewModelScope.launch {
        val previousCalendarId = settings.current().busySync.calendarId
        val calendarId: Long?
        if (enabled && previousCalendarId == null) {
            calendarId = defaultBusyCalendar(store.state.calendars.writable())?.id
            settings.setBusySync(enabled = true, calendarId = calendarId)
        } else {
            calendarId = previousCalendarId
            settings.setBusySyncEnabled(enabled)
        }
        store.dispatch(BusySyncSettingChanged(previousCalendarId, calendarId, enabledNow = enabled))
    }

    /**
     * Settings → Busy calendar's radio row for [calendar]; a re-tap of the already selected
     * calendar changes no setting. Either way a calendar with `SYNC_EVENTS` off gets
     * [EnableCalendarSync]: blocks written to it would never be uploaded, and the re-tap is
     * the retry when sync was turned off again (or the first attempt failed).
     */
    fun onBusyCalendarSelected(calendar: CalendarInfo) = viewModelScope.launch {
        if (!calendar.syncEvents) store.dispatch(EnableCalendarSync(calendar.id))
        val current = settings.current().busySync
        if (current.calendarId == calendar.id) return@launch
        settings.setBusySyncCalendar(calendar.id)
        store.dispatch(BusySyncSettingChanged(current.calendarId, calendar.id, enabledNow = current.enabled))
    }

    /**
     * Settings → Busy calendar's "Your first name" field, on every edit. Only the setting
     * changes: blocks already on the calendar are re-titled by the next share of their day,
     * so there is no cleanup to dispatch.
     */
    fun onBusyFirstNameChanged(firstName: String) = viewModelScope.launch { settings.setBusySyncFirstName(firstName) }

    fun onTestAlarmClick() {
        store.dispatch(TestAlarm)
    }

    fun onMessageShown(message: UiMessage) {
        store.dispatch(ClearMessage(message.id))
    }
}

private fun Settings.toUiState(calendars: List<CalendarInfo>, permissionsStatus: PermissionsStatus) = SettingsUiState(
    leadTime = leadTime,
    snoozeLength = snoozeLength,
    autoTimeout = autoTimeout,
    soundPool = soundPool,
    showDeclined = showDeclined,
    calendars = calendars.map { CalendarRow(it, included = calendarOverrides[it.id] ?: it.visible) },
    permissionsStatus = permissionsStatus,
    busySyncEnabled = busySync.enabled,
    busySyncCalendarId = busySync.calendarId,
    busySyncFirstName = busySync.firstName,
    busyCalendars = calendars.insertable(),
)

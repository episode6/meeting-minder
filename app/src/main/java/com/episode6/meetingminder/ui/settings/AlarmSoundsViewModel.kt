package com.episode6.meetingminder.ui.settings

import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.episode6.meetingminder.alarm.AlarmSound
import com.episode6.meetingminder.alarm.SoundCatalog
import com.episode6.meetingminder.alarm.SoundCatalogSource
import com.episode6.meetingminder.data.settings.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MILLIS = 5_000L

/** The groups Settings → Alarm sounds presents, one per source of TODO.md §4.4's draw. */
enum class AlarmSoundGroup { SYSTEM, BUNDLED, SIREN }

/** One checkbox row of Settings → Alarm sounds: an [AlarmSound.id], its shown [name] and whether it's checked. */
data class SoundChoice(val id: String, val name: String, val enabled: Boolean)

/**
 * What `AlarmSoundsScreen` renders: the device's alarm ringtones and the bundled OGGs as
 * checkbox rows, and the siren's one checkbox. [loaded] is false until the catalog has been
 * read (listing ringtones is a provider query), so the screen can hold its groups back
 * rather than flash them empty.
 */
data class AlarmSoundsUiState(
    val loaded: Boolean = false,
    val system: List<SoundChoice> = emptyList(),
    val bundled: List<SoundChoice> = emptyList(),
    val sirenEnabled: Boolean = true,
) {
    /** The rows of [group]; the siren's is its one synthetic row, named by the screen. */
    fun choices(group: AlarmSoundGroup): List<SoundChoice> = when (group) {
        AlarmSoundGroup.SYSTEM -> system
        AlarmSoundGroup.BUNDLED -> bundled
        AlarmSoundGroup.SIREN -> listOf(SoundChoice(AlarmSound.SIREN_ID, "", sirenEnabled))
    }
}

/** A group's checkbox: on when every row is, off when none is, indeterminate in between (and off for an empty group). */
fun groupState(choices: List<SoundChoice>): ToggleableState = when {
    choices.isEmpty() || choices.none { it.enabled } -> ToggleableState.Off
    choices.all { it.enabled } -> ToggleableState.On
    else -> ToggleableState.Indeterminate
}

/**
 * Settings → Alarm sounds' store adapter (TODO.md §4.4): the device's [SoundCatalog] (read
 * once per screen) combined with `Settings.disabledAlarmSounds`. Every write goes through
 * [SettingsRepository.setAlarmSoundsEnabled], a group's as one edit; the ringing player reads
 * the result on its next alarm, so nothing is dispatched to the store.
 */
@Inject
@ViewModelKey(AlarmSoundsViewModel::class)
@ContributesIntoMap(AppScope::class)
class AlarmSoundsViewModel(private val settings: SettingsRepository, catalogSource: SoundCatalogSource) : ViewModel() {

    private val catalog = viewModelScope.async { catalogSource.load() }

    val state: StateFlow<AlarmSoundsUiState> = combine(flow { emit(catalog.await()) }, settings.settings) { catalog, prefs ->
        AlarmSoundsUiState(
            loaded = true,
            system = catalog.system.map { SoundChoice(it.id, it.title, it.id !in prefs.disabledAlarmSounds) },
            bundled = catalog.bundled.map { SoundChoice(it.id, it.name, it.id !in prefs.disabledAlarmSounds) },
            sirenEnabled = AlarmSound.SIREN_ID !in prefs.disabledAlarmSounds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AlarmSoundsUiState())

    fun onSoundToggle(soundId: String, enabled: Boolean) = viewModelScope.launch {
        settings.setAlarmSoundsEnabled(listOf(soundId), enabled)
    }

    /** Checks or unchecks every sound of [group] in one edit; the ids come from the loaded catalog, not the rendered rows. */
    fun onGroupToggle(group: AlarmSoundGroup, enabled: Boolean) = viewModelScope.launch {
        val loaded = catalog.await()
        val ids = when (group) {
            AlarmSoundGroup.SYSTEM -> loaded.system.map { it.id }
            AlarmSoundGroup.BUNDLED -> loaded.bundled.map { it.id }
            AlarmSoundGroup.SIREN -> listOf(AlarmSound.SIREN_ID)
        }
        settings.setAlarmSoundsEnabled(ids, enabled)
    }
}

package com.episode6.meetingminder.ui.settings

import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.episode6.meetingminder.alarm.AlarmSound
import com.episode6.meetingminder.alarm.SoundCatalog
import com.episode6.meetingminder.alarm.SoundCatalogSource
import com.episode6.meetingminder.alarm.SoundPreviewer
import com.episode6.meetingminder.alarm.nextSirenParams
import com.episode6.meetingminder.data.settings.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val STOP_TIMEOUT_MILLIS = 5_000L

/** The groups Settings → Alarm sounds presents, one per source of TODO.md §4.4's draw. */
enum class AlarmSoundGroup { SYSTEM, BUNDLED, SIREN }

/** One checkbox row of Settings → Alarm sounds: an [AlarmSound.id], its shown [name] and whether it's checked. */
data class SoundChoice(val id: String, val name: String, val enabled: Boolean)

/**
 * What `AlarmSoundsScreen` renders: the device's alarm ringtones and the bundled OGGs as
 * checkbox rows, and the siren's one checkbox. [loaded] is false until the catalog has been
 * read (listing ringtones is a provider query), so the screen can hold its groups back
 * rather than flash them empty. [previewing] is the [AlarmSound.id] of the sound playing
 * because its row was tapped, if any.
 */
data class AlarmSoundsUiState(
    val loaded: Boolean = false,
    val system: List<SoundChoice> = emptyList(),
    val bundled: List<SoundChoice> = emptyList(),
    val sirenEnabled: Boolean = true,
    val previewing: String? = null,
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
 *
 * Tapping a sound plays it through the [SoundPreviewer], one at a time: tapping it again,
 * tapping another, leaving the screen ([onStopPreview]) or the ViewModel going away stops it.
 *
 * Opening the page also prunes the off set: an id that is no longer in the catalog (a
 * ringtone a system update removed or renamed) can't be reached by any checkbox, and
 * would otherwise count toward the Settings row's "N sounds off" for good.
 */
@Inject
@ViewModelKey(AlarmSoundsViewModel::class)
@ContributesIntoMap(AppScope::class)
class AlarmSoundsViewModel(
    private val settings: SettingsRepository,
    catalogSource: SoundCatalogSource,
    private val previewer: SoundPreviewer,
) : ViewModel() {

    /** Null when the device's list couldn't be read: the page then shows the siren alone rather than crashing the collector. */
    private val catalog: Deferred<SoundCatalog?> = viewModelScope.async {
        try {
            catalogSource.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    init {
        viewModelScope.launch {
            // never on a failed load: an empty list would read every stored id as stale
            val loaded = catalog.await() ?: return@launch
            val stale = settings.current().disabledAlarmSounds - loaded.ids() - AlarmSound.SIREN_ID
            if (stale.isNotEmpty()) settings.setAlarmSoundsEnabled(stale, enabled = true)
        }
    }

    private val previewing = MutableStateFlow<String?>(null)
    private var previewJob: Job? = null
    private var previewToken = 0

    val state: StateFlow<AlarmSoundsUiState> = combine(
        flow { emit(catalog.await() ?: SoundCatalog(emptyList(), emptyList())) },
        settings.settings,
        previewing,
    ) { catalog, prefs, previewing ->
        AlarmSoundsUiState(
            loaded = true,
            system = catalog.system.map { SoundChoice(it.id, it.title, it.id !in prefs.disabledAlarmSounds) },
            bundled = catalog.bundled.map { SoundChoice(it.id, it.name, it.id !in prefs.disabledAlarmSounds) },
            sirenEnabled = AlarmSound.SIREN_ID !in prefs.disabledAlarmSounds,
            previewing = previewing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AlarmSoundsUiState())

    fun onSoundToggle(soundId: String, enabled: Boolean) = viewModelScope.launch {
        settings.setAlarmSoundsEnabled(listOf(soundId), enabled)
    }

    /** Plays [soundId] in place of whatever was playing, or stops it when it is the one playing. */
    fun onSoundPreview(soundId: String) {
        if (previewing.value == soundId) {
            onStopPreview()
            return
        }
        val previous = previewJob
        val token = ++previewToken
        previewing.value = soundId
        previewJob = viewModelScope.launch {
            // one sound at a time: the last one is released before this one opens
            previous?.cancelAndJoin()
            try {
                val loaded = catalog.await() ?: SoundCatalog(emptyList(), emptyList())
                // each preview of the siren is a fresh draw, as each siren segment of a ring is
                val sound = if (soundId == AlarmSound.SIREN_ID) AlarmSound.Siren(Random.nextSirenParams()) else loaded.find(soundId)
                if (sound != null) previewer.play(sound)
            } finally {
                // by token, not id: a stopped preview of this same sound finishes after the new one is shown
                if (previewToken == token) previewing.value = null
            }
        }
    }

    fun onStopPreview() {
        previewToken++
        previewJob?.cancel()
        previewing.value = null
    }

    /** Checks or unchecks every sound of [group] in one edit; the ids come from the loaded catalog, not the rendered rows. */
    fun onGroupToggle(group: AlarmSoundGroup, enabled: Boolean) = viewModelScope.launch {
        val loaded = catalog.await() ?: SoundCatalog(emptyList(), emptyList())
        val ids = when (group) {
            AlarmSoundGroup.SYSTEM -> loaded.system.map { it.id }
            AlarmSoundGroup.BUNDLED -> loaded.bundled.map { it.id }
            AlarmSoundGroup.SIREN -> listOf(AlarmSound.SIREN_ID)
        }
        settings.setAlarmSoundsEnabled(ids, enabled)
    }
}

private fun SoundCatalog.ids(): Set<String> = (system.map { it.id } + bundled.map { it.id }).toSet()

private fun SoundCatalog.find(id: String): AlarmSound? = system.find { it.id == id } ?: bundled.find { it.id == id }

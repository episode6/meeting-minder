package com.episode6.meetingminder.alarm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * The "recently used" ring buffer of TODO.md §4.4: the first sound each of the last few
 * alarms played, so a new alarm doesn't open with the same ringtone as any of the last
 * [AlarmSoundDefaults.RECENT_ALARMS]. Persisted, because alarms are hours apart and the
 * process rarely lives that long. Entries are per alarm, and an alarm never avoids its
 * own entry — that is what lets a snoozed alarm come back with the same sound.
 */
interface RecentAlarmSounds {
    /** The sound ids the first sound of alarm [alarmId] should avoid. */
    suspend fun avoidFor(alarmId: Long): Set<String>

    /** Records that alarm [alarmId] opened with [soundId]. */
    suspend fun record(alarmId: Long, soundId: String)
}

internal data class RecentSound(val alarmId: Long, val soundId: String)

/** Newest first; one entry per alarm; room for the last few others plus the alarm itself. */
internal fun List<RecentSound>.recording(alarmId: Long, soundId: String): List<RecentSound> =
    (listOf(RecentSound(alarmId, soundId)) + filter { it.alarmId != alarmId }).take(AlarmSoundDefaults.RECENT_ALARMS + 1)

internal fun List<RecentSound>.avoidFor(alarmId: Long): Set<String> =
    filter { it.alarmId != alarmId }.take(AlarmSoundDefaults.RECENT_ALARMS).map { it.soundId }.toSet()

internal fun encodeRecentSounds(sounds: List<RecentSound>): String = sounds.joinToString("\n") { "${it.alarmId}\t${it.soundId}" }

internal fun decodeRecentSounds(encoded: String?): List<RecentSound> = encoded.orEmpty().lineSequence().mapNotNull { line ->
    val parts = line.split('\t', limit = 2)
    val alarmId = parts.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
    parts.getOrNull(1)?.let { RecentSound(alarmId, it) }
}.toList()

/** [RecentAlarmSounds] in the app's preferences DataStore (bound in `di/AlarmModule.kt`); not a user setting, just state. */
class DataStoreRecentAlarmSounds(private val dataStore: DataStore<Preferences>) : RecentAlarmSounds {

    override suspend fun avoidFor(alarmId: Long): Set<String> = decodeRecentSounds(dataStore.data.first()[Key]).avoidFor(alarmId)

    override suspend fun record(alarmId: Long, soundId: String) {
        dataStore.edit { it[Key] = encodeRecentSounds(decodeRecentSounds(it[Key]).recording(alarmId, soundId)) }
    }

    private companion object {
        val Key = stringPreferencesKey("recent_alarm_sounds")
    }
}

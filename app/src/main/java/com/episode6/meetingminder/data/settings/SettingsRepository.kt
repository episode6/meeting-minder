package com.episode6.meetingminder.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Duration

/**
 * Which sounds a ringing alarm may draw from (TODO.md §4.4). The synthesised siren is
 * generated in-app, so it belongs to the in-app pool: [BUNDLED_ONLY] keeps the bundled OGGs
 * and the siren, [SYSTEM_ONLY] only the device's alarm ringtones.
 */
enum class SoundPool { ALL, BUNDLED_ONLY, SYSTEM_ONLY }

/**
 * The user's preferences (TODO.md §6 item 8 for the defaults). PR-12's Settings screen
 * edits them; until then only [leadTime] has a setter and the rest read their defaults
 * (their DataStore keys are already read, so PR-12 only adds the writes).
 */
data class Settings(
    /** How long before an event its alarm rings. */
    val leadTime: Duration = SettingsDefaults.LeadTime,
    /** How long Snooze silences a ringing alarm for. */
    val snoozeLength: Duration = SettingsDefaults.SnoozeLength,
    /** How long an alarm rings unanswered before it snoozes itself (once) and then gives up. */
    val autoTimeout: Duration = SettingsDefaults.AutoTimeout,
    val soundPool: SoundPool = SoundPool.ALL,
)

object SettingsDefaults {
    val LeadTime: Duration = Duration.ofMinutes(5)
    val SnoozeLength: Duration = Duration.ofMinutes(2)
    val AutoTimeout: Duration = Duration.ofMinutes(3)
}

/** Read/write access to [Settings]; `DataStoreSettingsRepository` is the production binding. */
interface SettingsRepository {
    val settings: Flow<Settings>

    /** The current value, for one-shot reads in side effects, receivers and the ringing service. */
    suspend fun current(): Settings = settings.first()

    suspend fun setLeadTime(leadTime: Duration)
}

/** [SettingsRepository] over the app's preferences DataStore (bound in `di/SettingsModule.kt`). */
class DataStoreSettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsRepository {

    override val settings: Flow<Settings> = dataStore.data.map { it.toSettings() }

    override suspend fun setLeadTime(leadTime: Duration) {
        dataStore.edit { it[Keys.LeadTimeMinutes] = leadTime.toMinutes().toInt() }
    }

    private fun Preferences.toSettings() = Settings(
        leadTime = minutes(Keys.LeadTimeMinutes) ?: SettingsDefaults.LeadTime,
        snoozeLength = minutes(Keys.SnoozeMinutes) ?: SettingsDefaults.SnoozeLength,
        autoTimeout = minutes(Keys.AutoTimeoutMinutes) ?: SettingsDefaults.AutoTimeout,
        soundPool = this[Keys.SoundPool]?.let { name -> SoundPool.entries.firstOrNull { it.name == name } } ?: SoundPool.ALL,
    )

    private fun Preferences.minutes(key: Preferences.Key<Int>): Duration? =
        this[key]?.takeIf { it > 0 }?.let { Duration.ofMinutes(it.toLong()) }

    internal object Keys {
        val LeadTimeMinutes = intPreferencesKey("lead_time_minutes")
        val SnoozeMinutes = intPreferencesKey("snooze_minutes")
        val AutoTimeoutMinutes = intPreferencesKey("auto_timeout_minutes")
        val SoundPool = stringPreferencesKey("sound_pool")
    }
}

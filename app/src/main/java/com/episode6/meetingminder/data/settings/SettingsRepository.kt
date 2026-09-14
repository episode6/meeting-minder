package com.episode6.meetingminder.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Duration

/** The user's preferences (TODO.md §6 item 8 for the defaults); PR-12's Settings screen edits them. */
data class Settings(
    /** How long before an event its alarm rings. */
    val leadTime: Duration = SettingsDefaults.LeadTime,
)

object SettingsDefaults {
    val LeadTime: Duration = Duration.ofMinutes(5)
}

/** Read/write access to [Settings]; `DataStoreSettingsRepository` is the production binding. */
interface SettingsRepository {
    val settings: Flow<Settings>

    /** The current value, for one-shot reads in side effects and receivers. */
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
        leadTime = this[Keys.LeadTimeMinutes]?.let { Duration.ofMinutes(it.toLong()) } ?: SettingsDefaults.LeadTime,
    )

    private object Keys {
        val LeadTimeMinutes = intPreferencesKey("lead_time_minutes")
    }
}

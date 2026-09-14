package com.episode6.meetingminder.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
 * The user's preferences (TODO.md §6 item 8 for the defaults, §5 PR-12 for the screen that
 * edits them). [showDeclined] defaults to on: seeing what you declined is useful when
 * planning the day (TODO.md §4.1). [calendarOverrides] is the Settings → Calendars
 * per-calendar override: a calendar id maps to `true` (force include, even if hidden in
 * Google Calendar) or `false` (force exclude, even if visible there); a calendar absent
 * from the map falls back to its own `VISIBLE` flag, which is why an empty map is exactly
 * [com.episode6.meetingminder.data.calendar.CalendarFilter.Visible] (see
 * [com.episode6.meetingminder.data.calendar.effectiveCalendarFilter]).
 */
data class Settings(
    /** How long before an event its alarm rings. */
    val leadTime: Duration = SettingsDefaults.LeadTime,
    /** How long Snooze silences a ringing alarm for. */
    val snoozeLength: Duration = SettingsDefaults.SnoozeLength,
    /** How long an alarm rings unanswered before it snoozes itself (once) and then gives up. */
    val autoTimeout: Duration = SettingsDefaults.AutoTimeout,
    val soundPool: SoundPool = SoundPool.ALL,
    /** Whether events you declined in Google Calendar still show (dashed/strikethrough) in the itinerary. */
    val showDeclined: Boolean = true,
    /** Per-calendar include override; see the class doc. Empty means "respect `VISIBLE` for every calendar". */
    val calendarOverrides: Map<Long, Boolean> = emptyMap(),
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
    suspend fun setSnoozeLength(snoozeLength: Duration)
    suspend fun setAutoTimeout(autoTimeout: Duration)
    suspend fun setSoundPool(soundPool: SoundPool)
    suspend fun setShowDeclined(showDeclined: Boolean)

    /** Sets [Settings.calendarOverrides] for [calendarId]: `true`/`false` to force it, or null to clear the override. */
    suspend fun setCalendarOverride(calendarId: Long, included: Boolean?)
}

/** [SettingsRepository] over the app's preferences DataStore (bound in `di/SettingsModule.kt`). */
class DataStoreSettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsRepository {

    override val settings: Flow<Settings> = dataStore.data.map { it.toSettings() }

    override suspend fun setLeadTime(leadTime: Duration) {
        dataStore.edit { it[Keys.LeadTimeMinutes] = leadTime.toMinutes().toInt() }
    }

    override suspend fun setSnoozeLength(snoozeLength: Duration) {
        dataStore.edit { it[Keys.SnoozeMinutes] = snoozeLength.toMinutes().toInt() }
    }

    override suspend fun setAutoTimeout(autoTimeout: Duration) {
        dataStore.edit { it[Keys.AutoTimeoutMinutes] = autoTimeout.toMinutes().toInt() }
    }

    override suspend fun setSoundPool(soundPool: SoundPool) {
        dataStore.edit { it[Keys.SoundPool] = soundPool.name }
    }

    override suspend fun setShowDeclined(showDeclined: Boolean) {
        dataStore.edit { it[Keys.ShowDeclined] = showDeclined }
    }

    override suspend fun setCalendarOverride(calendarId: Long, included: Boolean?) {
        val id = calendarId.toString()
        dataStore.edit { prefs ->
            val currentlyIncluded = prefs[Keys.CalendarOverridesIncluded].orEmpty()
            val currentlyExcluded = prefs[Keys.CalendarOverridesExcluded].orEmpty()
            prefs[Keys.CalendarOverridesIncluded] = when (included) {
                true -> currentlyIncluded + id
                else -> currentlyIncluded - id
            }
            prefs[Keys.CalendarOverridesExcluded] = when (included) {
                false -> currentlyExcluded + id
                else -> currentlyExcluded - id
            }
        }
    }

    private fun Preferences.toSettings() = Settings(
        leadTime = minutes(Keys.LeadTimeMinutes) ?: SettingsDefaults.LeadTime,
        snoozeLength = minutes(Keys.SnoozeMinutes) ?: SettingsDefaults.SnoozeLength,
        autoTimeout = minutes(Keys.AutoTimeoutMinutes) ?: SettingsDefaults.AutoTimeout,
        soundPool = this[Keys.SoundPool]?.let { name -> SoundPool.entries.firstOrNull { it.name == name } } ?: SoundPool.ALL,
        showDeclined = this[Keys.ShowDeclined] ?: true,
        calendarOverrides = calendarOverrides(),
    )

    private fun Preferences.calendarOverrides(): Map<Long, Boolean> {
        val included = this[Keys.CalendarOverridesIncluded].orEmpty().mapNotNull { it.toLongOrNull() }
        val excluded = this[Keys.CalendarOverridesExcluded].orEmpty().mapNotNull { it.toLongOrNull() }
        return included.associateWith { true } + excluded.associateWith { false }
    }

    private fun Preferences.minutes(key: Preferences.Key<Int>): Duration? =
        this[key]?.takeIf { it > 0 }?.let { Duration.ofMinutes(it.toLong()) }

    internal object Keys {
        val LeadTimeMinutes = intPreferencesKey("lead_time_minutes")
        val SnoozeMinutes = intPreferencesKey("snooze_minutes")
        val AutoTimeoutMinutes = intPreferencesKey("auto_timeout_minutes")
        val SoundPool = stringPreferencesKey("sound_pool")
        val ShowDeclined = booleanPreferencesKey("show_declined")
        val CalendarOverridesIncluded = stringSetPreferencesKey("calendar_overrides_included")
        val CalendarOverridesExcluded = stringSetPreferencesKey("calendar_overrides_excluded")
    }
}

package com.episode6.meetingminder.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
enum class AlarmSoundPool { ALL, BUNDLED_ONLY, SYSTEM_ONLY }

/** The most [BusySync.firstName] holds: it ends up in a calendar event's title. */
const val BUSY_FIRST_NAME_MAX_LENGTH = 30

/**
 * Settings → Busy calendar (TODO.md §4.7). [calendarId] is null until the user (or the Family
 * default) picks one. The repository stores only what the user chose; the "Family" default is
 * applied by the ViewModel at the moment the toggle is turned on, as an explicit id, so a later
 * rename of the Family calendar can't silently move the sync. [firstName] goes into each
 * block's title ("Geoff busy", see [com.episode6.meetingminder.data.calendar.busyBlockTitle]);
 * blank, the default, keeps the bare `busy`. [sendText] off makes the sync the whole share:
 * "Sync busy times" writes the blocks and records the day as synced without opening the
 * chooser (see [com.episode6.meetingminder.data.calendar.shareMode]); on, the default, a
 * share sends the text as well.
 */
data class BusySync(
    val enabled: Boolean = false,
    val calendarId: Long? = null,
    val firstName: String = "",
    val sendText: Boolean = true,
)

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
    val soundPool: AlarmSoundPool = AlarmSoundPool.ALL,
    /** Whether events you declined in Google Calendar still show (dashed/strikethrough) in the itinerary. */
    val showDeclined: Boolean = true,
    /** Per-calendar include override; see the class doc. Empty means "respect `VISIBLE` for every calendar". */
    val calendarOverrides: Map<Long, Boolean> = emptyMap(),
    /** Settings → Busy calendar (TODO.md §4.7): whether, and to which calendar, a share also syncs busy blocks. */
    val busySync: BusySync = BusySync(),
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
    suspend fun setSoundPool(soundPool: AlarmSoundPool)
    suspend fun setShowDeclined(showDeclined: Boolean)

    /** Sets [Settings.calendarOverrides] for [calendarId]: `true`/`false` to force it, or null to clear the override. */
    suspend fun setCalendarOverride(calendarId: Long, included: Boolean?)

    /** Sets [BusySync.enabled]. Applying the Family default when turning it on is the ViewModel's job. */
    suspend fun setBusySyncEnabled(enabled: Boolean)

    /** Sets [BusySync.calendarId]; null clears it back to "no calendar chosen". */
    suspend fun setBusySyncCalendar(calendarId: Long?)

    /**
     * Sets [BusySync.enabled] and [BusySync.calendarId] together. The ViewModel uses this
     * (rather than [setBusySyncEnabled] followed by [setBusySyncCalendar]) when turning the
     * toggle on applies the Family default in the same gesture, so a collector never observes
     * the intermediate "enabled, no calendar chosen yet" state between two separate edits.
     */
    suspend fun setBusySync(enabled: Boolean, calendarId: Long?) {
        setBusySyncEnabled(enabled)
        setBusySyncCalendar(calendarId)
    }

    /**
     * Sets [BusySync.firstName], trimmed and cut to [BUSY_FIRST_NAME_MAX_LENGTH]; blank clears it. Nothing on the calendar changes
     * at once: a day's blocks are re-titled by its next share (`reconcileBusyBlocks`).
     */
    suspend fun setBusySyncFirstName(firstName: String)

    /** Sets [BusySync.sendText]: whether a share still sends the schedule text while the sync is on. */
    suspend fun setBusySyncSendText(sendText: Boolean)

    /**
     * The runtime permissions (`Manifest.permission` names) this app has asked the system
     * for at least once, ever. Not a preference, but it has to outlive the process: the
     * "two denials → Open settings" detection (TODO.md §4.1) can't tell a permanently denied
     * permission from a never-requested one — both report no rationale — except by knowing a
     * request already happened, and a permanently denied user who relaunches the app would
     * otherwise be stuck on an "Allow" button whose dialog the system silently refuses.
     */
    val requestedPermissions: Flow<Set<String>>

    /** Records that [permission] has been requested; see [requestedPermissions]. */
    suspend fun markPermissionRequested(permission: String)
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

    override suspend fun setSoundPool(soundPool: AlarmSoundPool) {
        dataStore.edit { it[Keys.AlarmSoundPool] = soundPool.name }
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

    override suspend fun setBusySyncEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BusySyncEnabled] = enabled }
    }

    override suspend fun setBusySyncCalendar(calendarId: Long?) {
        dataStore.edit { prefs ->
            if (calendarId == null) prefs.remove(Keys.BusySyncCalendarId) else prefs[Keys.BusySyncCalendarId] = calendarId
        }
    }

    override suspend fun setBusySync(enabled: Boolean, calendarId: Long?) {
        dataStore.edit { prefs ->
            prefs[Keys.BusySyncEnabled] = enabled
            if (calendarId == null) prefs.remove(Keys.BusySyncCalendarId) else prefs[Keys.BusySyncCalendarId] = calendarId
        }
    }

    override suspend fun setBusySyncFirstName(firstName: String) {
        val name = firstName.trim().take(BUSY_FIRST_NAME_MAX_LENGTH).trim()
        dataStore.edit { prefs ->
            if (name.isEmpty()) prefs.remove(Keys.BusySyncFirstName) else prefs[Keys.BusySyncFirstName] = name
        }
    }

    override suspend fun setBusySyncSendText(sendText: Boolean) {
        dataStore.edit { it[Keys.BusySyncSendText] = sendText }
    }

    override val requestedPermissions: Flow<Set<String>> = dataStore.data.map { it[Keys.RequestedPermissions].orEmpty() }

    override suspend fun markPermissionRequested(permission: String) {
        dataStore.edit { it[Keys.RequestedPermissions] = it[Keys.RequestedPermissions].orEmpty() + permission }
    }

    private fun Preferences.toSettings() = Settings(
        leadTime = minutes(Keys.LeadTimeMinutes) ?: SettingsDefaults.LeadTime,
        snoozeLength = minutes(Keys.SnoozeMinutes) ?: SettingsDefaults.SnoozeLength,
        autoTimeout = minutes(Keys.AutoTimeoutMinutes) ?: SettingsDefaults.AutoTimeout,
        soundPool = this[Keys.AlarmSoundPool]?.let { name -> AlarmSoundPool.entries.firstOrNull { it.name == name } } ?: AlarmSoundPool.ALL,
        showDeclined = this[Keys.ShowDeclined] ?: true,
        calendarOverrides = calendarOverrides(),
        busySync = BusySync(
            enabled = this[Keys.BusySyncEnabled] ?: false,
            calendarId = this[Keys.BusySyncCalendarId],
            firstName = this[Keys.BusySyncFirstName].orEmpty(),
            sendText = this[Keys.BusySyncSendText] ?: true,
        ),
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
        val AlarmSoundPool = stringPreferencesKey("sound_pool")
        val ShowDeclined = booleanPreferencesKey("show_declined")
        val CalendarOverridesIncluded = stringSetPreferencesKey("calendar_overrides_included")
        val CalendarOverridesExcluded = stringSetPreferencesKey("calendar_overrides_excluded")
        val RequestedPermissions = stringSetPreferencesKey("requested_permissions")
        val BusySyncEnabled = booleanPreferencesKey("busy_sync_enabled")
        val BusySyncCalendarId = longPreferencesKey("busy_sync_calendar_id")
        val BusySyncFirstName = stringPreferencesKey("busy_sync_first_name")
        val BusySyncSendText = booleanPreferencesKey("busy_sync_send_text")
    }
}

package com.episode6.meetingminder.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.episode6.meetingminder.alarm.AlarmRingingCommands
import com.episode6.meetingminder.alarm.AlarmScheduler
import com.episode6.meetingminder.alarm.AndroidAlarmScheduler
import com.episode6.meetingminder.alarm.DataStoreRecentAlarmSounds
import com.episode6.meetingminder.alarm.DeviceSoundCatalogSource
import com.episode6.meetingminder.alarm.DeviceSoundPreviewer
import com.episode6.meetingminder.alarm.RecentAlarmSounds
import com.episode6.meetingminder.alarm.ScheduleChangeAlertContent
import com.episode6.meetingminder.alarm.ScheduleChangeAlerts
import com.episode6.meetingminder.alarm.ServiceAlarmRingingCommands
import com.episode6.meetingminder.alarm.SoundCatalogSource
import com.episode6.meetingminder.alarm.SoundPreviewer
import com.episode6.meetingminder.monitor.ScheduleChangeAlerter
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlin.random.Random

/**
 * Binds the `AlarmManager`-backed [AlarmScheduler] and the ringing service's collaborators;
 * tests substitute `FakeAlarmScheduler` / fakes.
 */
@ContributesTo(AppScope::class)
interface AlarmModule {
    @Provides
    @SingleIn(AppScope::class)
    fun alarmScheduler(context: Context): AlarmScheduler = AndroidAlarmScheduler(context)

    @Provides
    fun alarmRingingCommands(context: Context): AlarmRingingCommands = ServiceAlarmRingingCommands(context)

    @Provides
    @SingleIn(AppScope::class)
    fun recentAlarmSounds(dataStore: DataStore<Preferences>): RecentAlarmSounds = DataStoreRecentAlarmSounds(dataStore)

    /** The device's alarm ringtones and the bundled sounds, as the ringing player and Settings → Alarm sounds both list them. */
    @Provides
    fun soundCatalogSource(context: Context): SoundCatalogSource = DeviceSoundCatalogSource(context)

    /** Plays a sound on its own when it is tapped in Settings → Alarm sounds. */
    @Provides
    fun soundPreviewer(context: Context): SoundPreviewer = DeviceSoundPreviewer(context)

    /** The loud schedule-change alert (TODO.md §4.3), as `ChangeMonitor` sees it. */
    @Provides
    fun scheduleChangeAlerter(alerts: ScheduleChangeAlerts): ScheduleChangeAlerter = alerts

    /** The same alert, as `AlarmRinger` sees it. */
    @Provides
    fun scheduleChangeAlertContent(alerts: ScheduleChangeAlerts): ScheduleChangeAlertContent = alerts

    /** Draws each new alarm's `sound_index`; tests inject a seeded one. */
    @Provides
    fun random(): Random = Random.Default
}

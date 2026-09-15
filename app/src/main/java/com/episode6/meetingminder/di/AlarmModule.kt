package com.episode6.meetingminder.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.episode6.meetingminder.alarm.AlarmRingingCommands
import com.episode6.meetingminder.alarm.AlarmScheduler
import com.episode6.meetingminder.alarm.AndroidAlarmScheduler
import com.episode6.meetingminder.alarm.DataStoreRecentAlarmSounds
import com.episode6.meetingminder.alarm.RecentAlarmSounds
import com.episode6.meetingminder.alarm.ServiceAlarmRingingCommands
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

    /** Draws each new alarm's `sound_index`; tests inject a seeded one. */
    @Provides
    fun random(): Random = Random.Default
}

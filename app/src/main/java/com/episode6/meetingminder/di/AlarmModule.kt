package com.episode6.meetingminder.di

import android.content.Context
import com.episode6.meetingminder.alarm.AlarmScheduler
import com.episode6.meetingminder.alarm.AndroidAlarmScheduler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlin.random.Random

/** Binds the `AlarmManager`-backed [AlarmScheduler]; tests substitute `FakeAlarmScheduler`. */
@ContributesTo(AppScope::class)
interface AlarmModule {
    @Provides
    @SingleIn(AppScope::class)
    fun alarmScheduler(context: Context): AlarmScheduler = AndroidAlarmScheduler(context)

    /** Draws each new alarm's `sound_index`; tests inject a seeded one. */
    @Provides
    fun random(): Random = Random.Default
}

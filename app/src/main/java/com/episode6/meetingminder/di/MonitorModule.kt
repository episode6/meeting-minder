package com.episode6.meetingminder.di

import android.content.Context
import com.episode6.meetingminder.monitor.AndroidScheduleChangeNotifier
import com.episode6.meetingminder.monitor.ChangeWorkScheduler
import com.episode6.meetingminder.monitor.ScheduleChangeNotifier
import com.episode6.meetingminder.monitor.WorkManagerChangeWorkScheduler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import java.time.Clock

/**
 * Binds change detection's Android edges (TODO.md §4.3): the WorkManager-backed
 * [ChangeWorkScheduler] and the `schedule_updates` [ScheduleChangeNotifier]; tests of
 * `ChangeMonitor` substitute fakes for both.
 */
@ContributesTo(AppScope::class)
interface MonitorModule {
    @Provides
    fun changeWorkScheduler(context: Context, clock: Clock): ChangeWorkScheduler = WorkManagerChangeWorkScheduler(context, clock)

    @Provides
    fun scheduleChangeNotifier(context: Context, clock: Clock): ScheduleChangeNotifier = AndroidScheduleChangeNotifier(context, clock)
}

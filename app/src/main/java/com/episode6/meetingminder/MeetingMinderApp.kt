package com.episode6.meetingminder

import android.app.Application
import android.content.Context
import com.episode6.meetingminder.alarm.AlarmNotifications
import com.episode6.meetingminder.di.AppGraph
import com.episode6.meetingminder.monitor.ScheduleChangeNotifications
import dev.zacsweers.metro.createGraphFactory

class MeetingMinderApp : Application() {
    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // before the graph: AndroidPermissionChecker reads the alarms channel's importance
        AlarmNotifications.createChannels(this)
        ScheduleChangeNotifications.createChannel(this)
        appGraph = createGraphFactory<AppGraph.Factory>().create(this)
    }
}

/** The app graph, reachable from activities, Composables, receivers, services and workers. */
val Context.appGraph: AppGraph
    get() = (applicationContext as MeetingMinderApp).appGraph

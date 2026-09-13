package com.episode6.meetingminder

import android.app.Application
import android.content.Context
import com.episode6.meetingminder.di.AppGraph
import dev.zacsweers.metro.createGraphFactory

class MeetingMinderApp : Application() {
    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph = createGraphFactory<AppGraph.Factory>().create(this)
    }
}

/** The app graph, reachable from activities, Composables, receivers, services and workers. */
val Context.appGraph: AppGraph
    get() = (applicationContext as MeetingMinderApp).appGraph

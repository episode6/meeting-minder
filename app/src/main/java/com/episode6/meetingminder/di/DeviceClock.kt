package com.episode6.meetingminder.di

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The wall clock in whatever zone the device is in *right now*. `Clock.systemDefaultZone()`
 * captures `ZoneId.systemDefault()` once, when it is created, and every app-scoped
 * singleton (side effects, `ChangeMonitor`, the alarm reconcile) holds the one instance it
 * was injected with for the life of the process: after a timezone change they would all
 * keep computing "today", local midnights and alarm day labels in the old zone. Android
 * resets the process's default `TimeZone` on `TIMEZONE_CHANGED`, so re-reading it on
 * every [getZone] is enough to follow the change (TODO.md §5 PR-13).
 */
internal object DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()

    override fun millis(): Long = System.currentTimeMillis()
}

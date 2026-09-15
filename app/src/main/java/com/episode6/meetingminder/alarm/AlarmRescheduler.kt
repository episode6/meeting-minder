package com.episode6.meetingminder.alarm

import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.time.Clock

/**
 * Re-arms every `SCHEDULED` row from Room (TODO.md §4.4): the OS drops all alarms on
 * shutdown (and some OEMs on update), so [BootReceiver] calls this on `BOOT_COMPLETED`,
 * `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` and when the exact-alarm grant
 * changes. `setAlarmClock` with a past trigger fires immediately, which is what we want
 * for a meeting that is about to start or under way: a stale alarm after a reboot is a
 * cheaper failure than a missed meeting. Rows whose event has already ended are left
 * alone (not re-armed; their state is untouched).
 */
@Inject
@SingleIn(AppScope::class)
class AlarmRescheduler(
    private val dao: ScheduledAlarmDao,
    private val scheduler: AlarmScheduler,
    private val clock: Clock,
) {
    /** Returns how many alarms were (re-)armed; 0 without the exact-alarm grant. */
    suspend fun rescheduleAll(): Int {
        if (!scheduler.canScheduleExactAlarms()) return 0
        val now = clock.millis()
        return dao.allScheduled().count { it.endMillis > now && scheduler.schedule(it) }
    }
}

package com.episode6.meetingminder.monitor

import java.time.LocalDate

/**
 * The loud side of a schedule change (TODO.md §4.3): a full-screen, ringing alert on top of
 * the quiet `schedule_updates` notification. `alarm/ScheduleChangeAlerts` is the production
 * binding; it rings through the alarm path.
 */
interface ScheduleChangeAlerter {
    /** Rings [date]'s alert now. False when it couldn't be armed (no exact-alarm grant), so the quiet notification should make the noise instead. */
    suspend fun alert(date: LocalDate): Boolean

    /** [date] was re-shared or un-shared: its alert, armed or ringing, no longer describes anything. */
    suspend fun cancel(date: LocalDate)
}

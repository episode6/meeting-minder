package com.episode6.meetingminder.alarm

import com.episode6.meetingminder.data.db.ScheduledAlarmEntity

/** Records what would be armed with `AlarmManager`: [armed] is keyed by `alarmId`, like the real PendingIntents. */
internal class FakeAlarmScheduler(var canSchedule: Boolean = true, var refuse: Boolean = false) : AlarmScheduler {
    val armed = linkedMapOf<Long, ScheduledAlarmEntity>()
    val cancelled = mutableListOf<Long>()

    override fun canScheduleExactAlarms(): Boolean = canSchedule

    override fun schedule(alarm: ScheduledAlarmEntity): Boolean {
        if (refuse) return false
        armed[alarm.alarmId] = alarm
        return true
    }

    override fun cancel(alarmId: Long) {
        armed.remove(alarmId)
        cancelled += alarmId
    }
}

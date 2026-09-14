package com.episode6.meetingminder.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.episode6.meetingminder.model.EventKey
import java.time.LocalDate

/** Lifecycle of one [ScheduledAlarmEntity] row; stored as its name. */
enum class AlarmState {
    /** Armed with `AlarmManager`; the only state [ScheduledAlarmDao.allScheduled] re-arms after boot. */
    SCHEDULED,
    FIRED,
    DISMISSED,
    SNOOZED,

    /** Cancelled by a reconcile (deselected, or moved into the past); never re-armed. */
    CANCELLED,
}

/**
 * `scheduled_alarm` (TODO.md §3.4): the source of truth for what is armed with
 * `AlarmManager`. [alarmId] is the identity of the alarm's `PendingIntent` (its request
 * code and `meetingminder://alarm/{alarmId}` data), so it must never be reused for a
 * different event. [title]/[beginMillis]/[endMillis] are denormalised copies so the
 * ringing path and the boot reschedule work without touching the provider; [soundIndex]
 * seeds PR-10's randomised sound so a snoozed alarm sounds the same when it returns.
 */
@Entity(tableName = "scheduled_alarm")
data class ScheduledAlarmEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "alarm_id") val alarmId: Long = 0,
    val date: LocalDate,
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "instance_time") val instanceTime: Long,
    @ColumnInfo(name = "fire_at") val fireAt: Long,
    val title: String,
    @ColumnInfo(name = "begin_millis") val beginMillis: Long,
    @ColumnInfo(name = "end_millis") val endMillis: Long,
    @ColumnInfo(name = "sound_index") val soundIndex: Int,
    val state: AlarmState = AlarmState.SCHEDULED,
) {
    val key: EventKey get() = EventKey(eventId, instanceTime)
}

package com.episode6.meetingminder.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.episode6.meetingminder.model.EventKey
import java.time.LocalDate

/** Lifecycle of one [ScheduledAlarmEntity] row; stored as its name. */
enum class AlarmState {
    /** Armed with `AlarmManager` at [ScheduledAlarmEntity.fireAt]. */
    SCHEDULED,

    /** Rang (`AlarmRingingService` is ringing it, or was when the process died); no longer armed. */
    FIRED,

    /** Rang and was dismissed, or went unanswered twice (TODO.md §4.4's auto-timeout gave up). */
    DISMISSED,

    /**
     * Rang and was snoozed (by the user or by the first auto-timeout): armed again with
     * `AlarmManager` at [ScheduledAlarmEntity.fireAt], the snooze time. Counts as armed
     * everywhere [SCHEDULED] does — the boot re-arm, the "Set alarms" reconcile, the FAB's
     * armed keys — see [armed].
     */
    SNOOZED,

    /**
     * Cancelled by a reconcile (deselected, moved into the past, or the OS refused to arm
     * it); never re-armed. A refused row is retried by the next "Set alarms" tap, which
     * sees no armed row for the key and inserts a fresh one.
     */
    CANCELLED,
    ;

    /** An `AlarmManager` alarm is (supposed to be) set for this row: [SCHEDULED] or [SNOOZED]. */
    val armed: Boolean get() = this == SCHEDULED || this == SNOOZED
}

/**
 * `scheduled_alarm` (TODO.md §3.4): the source of truth for what is armed with
 * `AlarmManager`. [alarmId] is the identity of the alarm's `PendingIntent` (its request
 * code and `meetingminder://alarm/{alarmId}` data), so it must never be reused for a
 * different event. [title]/[location]/[beginMillis]/[endMillis] are denormalised copies so
 * the ringing screen and the boot reschedule work without touching the provider;
 * [soundIndex] seeds the randomised sound (`AlarmSoundRecipe`) so a snoozed alarm sounds
 * the same when it returns. [timedOut] records that the ringing already auto-snoozed once
 * because nobody answered, so the next unanswered ring gives up instead of snoozing again.
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
    val location: String? = null,
    @ColumnInfo(name = "timed_out") val timedOut: Boolean = false,
) {
    val key: EventKey get() = EventKey(eventId, instanceTime)
}

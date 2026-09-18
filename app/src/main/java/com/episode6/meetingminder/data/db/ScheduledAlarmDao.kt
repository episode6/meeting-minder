package com.episode6.meetingminder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.episode6.meetingminder.model.SCHEDULE_CHANGE_ALARM_EVENT_ID
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Room access to `scheduled_alarm` (TODO.md §3.4); written by the alarm reconcile, the
 * receivers and the ringing service under `alarm/`. "Scheduled" in the query names means
 * *armed*: `SCHEDULED` or `SNOOZED` ([AlarmState.armed]) — a snoozed alarm is as much
 * set with `AlarmManager` as a fresh one, and must be re-armed after boot and cancellable
 * by a reconcile just the same.
 */
@Dao
interface ScheduledAlarmDao {
    /** Returns the new row's generated `alarm_id`. */
    @Insert
    suspend fun insert(entity: ScheduledAlarmEntity): Long

    @Update
    suspend fun update(entity: ScheduledAlarmEntity)

    @Query("SELECT * FROM scheduled_alarm WHERE alarm_id = :alarmId")
    suspend fun byId(alarmId: Long): ScheduledAlarmEntity?

    /** The rows still armed for [date]: what a `SetAlarms(date)` reconcile starts from. */
    @Query("SELECT * FROM scheduled_alarm WHERE date = :date AND state IN ('SCHEDULED', 'SNOOZED')")
    suspend fun scheduledOn(date: LocalDate): List<ScheduledAlarmEntity>

    /** Every armed row on any day: what the boot / time-change reschedule re-arms. */
    @Query("SELECT * FROM scheduled_alarm WHERE state IN ('SCHEDULED', 'SNOOZED')")
    suspend fun allScheduled(): List<ScheduledAlarmEntity>

    /**
     * [allScheduled] as a stream, for `ObserveDayPlansSideEffects`: the store needs to know
     * a day still has armed rows after every selection on it was removed, or the FAB would
     * hide and leave them impossible to cancel.
     */
    @Query("SELECT * FROM scheduled_alarm WHERE state IN ('SCHEDULED', 'SNOOZED')")
    fun observeScheduled(): Flow<List<ScheduledAlarmEntity>>

    /** The newest row for [eventId] on [date], in whatever state; see [changeAlertOn], its one caller. */
    @Query("SELECT * FROM scheduled_alarm WHERE date = :date AND event_id = :eventId ORDER BY alarm_id DESC LIMIT 1")
    suspend fun latestOn(date: LocalDate, eventId: Long): ScheduledAlarmEntity?

    /**
     * Moves [alarmId] from [from] to [to] in one statement and returns 1, or returns 0 and
     * changes nothing when the row isn't in [from] any more. Every transition out of `FIRED`
     * uses it: a schedule-change alert's row is re-armed (`SCHEDULED`) while it rings, and a
     * Dismiss that read `FIRED` a moment earlier must not write over that.
     */
    @Query("UPDATE scheduled_alarm SET state = :to WHERE alarm_id = :alarmId AND state = :from")
    suspend fun transition(alarmId: Long, from: AlarmState, to: AlarmState): Int

    @Query("UPDATE scheduled_alarm SET state = :state WHERE alarm_id = :alarmId")
    suspend fun setState(alarmId: Long, state: AlarmState)
}

/**
 * [date]'s schedule-change alert row (`SCHEDULE_CHANGE_ALARM_EVENT_ID`), in whatever state:
 * `ScheduleChangeAlerts` keeps one per day and re-arms it for every new change, so an alert
 * that is still ringing is replaced rather than queued behind.
 */
suspend fun ScheduledAlarmDao.changeAlertOn(date: LocalDate): ScheduledAlarmEntity? = latestOn(date, SCHEDULE_CHANGE_ALARM_EVENT_ID)

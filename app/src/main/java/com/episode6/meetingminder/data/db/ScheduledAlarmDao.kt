package com.episode6.meetingminder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Room access to `scheduled_alarm` (TODO.md §3.4); written by the alarm reconcile and the receivers under `alarm/`. */
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
    @Query("SELECT * FROM scheduled_alarm WHERE date = :date AND state = 'SCHEDULED'")
    suspend fun scheduledOn(date: LocalDate): List<ScheduledAlarmEntity>

    /** Every armed row on any day: what the boot / time-change reschedule re-arms. */
    @Query("SELECT * FROM scheduled_alarm WHERE state = 'SCHEDULED'")
    suspend fun allScheduled(): List<ScheduledAlarmEntity>

    /**
     * [allScheduled] as a stream, for `ObserveDayPlansSideEffects`: the store needs to know
     * a day still has armed rows after every selection on it was removed, or the FAB would
     * hide and leave them impossible to cancel.
     */
    @Query("SELECT * FROM scheduled_alarm WHERE state = 'SCHEDULED'")
    fun observeScheduled(): Flow<List<ScheduledAlarmEntity>>

    @Query("UPDATE scheduled_alarm SET state = :state WHERE alarm_id = :alarmId")
    suspend fun setState(alarmId: Long, state: AlarmState)
}

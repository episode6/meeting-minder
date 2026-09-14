package com.episode6.meetingminder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Room access to `day_plan` + `selected_event` (TODO.md §3.4); [ObserveDayPlansSideEffects] is its only reader. */
@Dao
interface DayPlanDao {
    @Query("SELECT * FROM day_plan")
    fun observeDayPlans(): Flow<List<DayPlanEntity>>

    @Query("SELECT * FROM selected_event")
    fun observeSelectedEvents(): Flow<List<SelectedEventEntity>>

    /** Unused until PR-8 (`SetAlarms`) writes a real `alarms_set_at`; kept here since it owns the table. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDayPlan(entity: DayPlanEntity)

    @Query("SELECT * FROM selected_event WHERE date = :date")
    suspend fun selectedEventsOn(date: LocalDate): List<SelectedEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSelectedEvent(entity: SelectedEventEntity)

    @Query("DELETE FROM selected_event WHERE date = :date AND event_id = :eventId AND instance_time = :instanceTime")
    suspend fun deleteSelectedEvent(date: LocalDate, eventId: Long, instanceTime: Long)
}

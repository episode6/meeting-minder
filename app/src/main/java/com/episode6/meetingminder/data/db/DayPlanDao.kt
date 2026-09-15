package com.episode6.meetingminder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /**
     * `REPLACE` deletes and re-inserts the row, so calling this on an existing key resets
     * every column to [entity]'s values — including [SelectedEventEntity.alarmId]/`alarmAt`/
     * `rsvpState`. Safe today only because [toggleSelectedEvent] never calls it on a key
     * that already exists; a future PR re-timing a moved *selected* event should use a
     * targeted `@Update`/`@Query` write (or Room's `@Upsert`) instead.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSelectedEvent(entity: SelectedEventEntity)

    /** Returns the number of rows deleted (0 or 1), so callers can tell whether the row existed. */
    @Query("DELETE FROM selected_event WHERE date = :date AND event_id = :eventId AND instance_time = :instanceTime")
    suspend fun deleteSelectedEvent(date: LocalDate, eventId: Long, instanceTime: Long): Int

    /**
     * Atomically flips [entity]'s selection: deletes its row if present, otherwise inserts it.
     * `@Transaction` on this default method makes the delete-then-maybe-insert one unit against
     * concurrent callers (e.g. two back-to-back taps on the same chip racing under `flatMapMerge`
     * in [com.episode6.meetingminder.store.sideeffects.ToggleEventSideEffects]) so a double toggle
     * of the same key can't both read "not selected" and both insert.
     */
    @Transaction
    suspend fun toggleSelectedEvent(entity: SelectedEventEntity) {
        val deleted = deleteSelectedEvent(entity.date, entity.eventId, entity.instanceTime)
        if (deleted == 0) {
            upsertSelectedEvent(entity)
        }
    }
}

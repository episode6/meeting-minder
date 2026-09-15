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

    /**
     * `REPLACE` deletes and re-inserts the row, so this resets every column of an existing
     * plan — including `shared_at`. Nothing calls it in production; [markAlarmsSet] (and
     * PR-9's share write) update their own columns instead.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDayPlan(entity: DayPlanEntity)

    @Query("INSERT OR IGNORE INTO day_plan (date) VALUES (:date)")
    suspend fun ensureDayPlan(date: LocalDate)

    @Query("UPDATE day_plan SET alarms_set_at = :alarmsSetAt WHERE date = :date")
    suspend fun setAlarmsSetAt(date: LocalDate, alarmsSetAt: Long?)

    /**
     * Records that the alarms for [date] now match its selection (`SetAlarms` reconciled
     * them at [alarmsSetAt]), creating the plan row if the day never had one. Flips the FAB
     * to "Share schedule".
     */
    @Transaction
    suspend fun markAlarmsSet(date: LocalDate, alarmsSetAt: Long) {
        ensureDayPlan(date)
        setAlarmsSetAt(date, alarmsSetAt)
    }

    @Query("SELECT * FROM selected_event WHERE date = :date")
    suspend fun selectedEventsOn(date: LocalDate): List<SelectedEventEntity>

    /**
     * `REPLACE` deletes and re-inserts the row, so calling this on an existing key resets
     * every column to [entity]'s values — including [SelectedEventEntity.alarmId]/`alarmAt`/
     * `rsvpState`. Safe today only because [toggleSelectedEvent] never calls it on a key
     * that already exists; re-timing a moved *selected* event goes through [armSelectedEvent].
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
     *
     * Any change of selection also clears the day's `alarms_set_at`: the armed set no longer
     * matches the selection, so the FAB goes back to "Set alarms" until the user re-taps it and
     * the reconcile catches up (TODO.md §2 interaction rules). The alarms themselves stay
     * armed until then.
     */
    @Transaction
    suspend fun toggleSelectedEvent(entity: SelectedEventEntity) {
        val deleted = deleteSelectedEvent(entity.date, entity.eventId, entity.instanceTime)
        if (deleted == 0) {
            upsertSelectedEvent(entity)
        }
        setAlarmsSetAt(entity.date, null)
    }

    /**
     * Points a selection at its armed alarm and refreshes the denormalised times/title
     * (the reconcile re-times a moved event, TODO.md §4.4). A targeted update rather than
     * a `REPLACE` so `rsvp_state`/`rsvp_event_id` (PR-8b) survive.
     */
    @Query(
        "UPDATE selected_event SET alarm_id = :alarmId, alarm_at = :alarmAt, title = :title, " +
            "begin_millis = :beginMillis, end_millis = :endMillis " +
            "WHERE date = :date AND event_id = :eventId AND instance_time = :instanceTime",
    )
    suspend fun armSelectedEvent(
        date: LocalDate,
        eventId: Long,
        instanceTime: Long,
        alarmId: Long?,
        alarmAt: Long?,
        title: String,
        beginMillis: Long,
        endMillis: Long,
    )
}

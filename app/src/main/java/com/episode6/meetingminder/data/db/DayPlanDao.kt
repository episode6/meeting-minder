package com.episode6.meetingminder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.episode6.meetingminder.model.RsvpState
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

    @Query("UPDATE day_plan SET shared_at = :sharedAt, shared_snapshot = :sharedSnapshot WHERE date = :date")
    suspend fun setShared(date: LocalDate, sharedAt: Long?, sharedSnapshot: String?)

    /** Records a share (or re-share) of [date] at [sharedAt], creating the plan row if the day never had one. */
    @Transaction
    suspend fun markShared(date: LocalDate, sharedAt: Long, sharedSnapshot: String) {
        ensureDayPlan(date)
        setShared(date, sharedAt, sharedSnapshot)
    }

    /** "Mark as not shared" (TODO.md §4.2): clears the share bookkeeping, leaving the selection/alarms alone. */
    suspend fun clearShared(date: LocalDate) = setShared(date, sharedAt = null, sharedSnapshot = null)

    @Query("SELECT * FROM selected_event WHERE date = :date")
    suspend fun selectedEventsOn(date: LocalDate): List<SelectedEventEntity>

    /**
     * `REPLACE` deletes and re-inserts the row, so calling this on an existing key resets
     * every column to [entity]'s values — including [SelectedEventEntity.alarmId]/`alarmAt`/
     * `rsvpState`. Safe today only because [toggleSelectedEvent] never calls it on a key
     * that already exists; re-timing a moved *selected* event goes through [armSelectedEvent]
     * and the RSVP columns through [setRsvp].
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

    /**
     * Records where a selection's RSVP stands (TODO.md §4.6): `ACCEPTED_LOCALLY`/`FAILED`
     * (with the id the write went to) once the provider write returns, and `SYNCED` when a
     * reload sees that row clean. The initial decision goes through [recordRsvpDecision].
     * A targeted update so the alarm pointer survives, like [armSelectedEvent].
     */
    @Query(
        "UPDATE selected_event SET rsvp_state = :state, rsvp_event_id = :rsvpEventId " +
            "WHERE date = :date AND event_id = :eventId AND instance_time = :instanceTime",
    )
    suspend fun setRsvp(date: LocalDate, eventId: Long, instanceTime: Long, state: RsvpState, rsvpEventId: Long?)

    /**
     * Records a fresh `rsvpDecision` on a selection the reconcile just armed, unless the row
     * was already answered (`ACCEPTED_LOCALLY`/`SYNCED`): a row that leaves `keep` and gets
     * re-armed (its event moved into the past and back, say) would otherwise lose its "sent"
     * tick, since by then the fresh event reads `ACCEPTED` from our own write and the
     * decision is `NOT_APPLICABLE`. Returns the number of rows updated (0 or 1); 0 means
     * the answer stands and nothing is to be written.
     */
    @Query(
        "UPDATE selected_event SET rsvp_state = :state, rsvp_event_id = NULL " +
            "WHERE date = :date AND event_id = :eventId AND instance_time = :instanceTime " +
            "AND rsvp_state NOT IN ('ACCEPTED_LOCALLY', 'SYNCED')",
    )
    suspend fun recordRsvpDecision(date: LocalDate, eventId: Long, instanceTime: Long, state: RsvpState): Int
}

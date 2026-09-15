package com.episode6.meetingminder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Room access to `change_snapshot` (TODO.md §3.4). [com.episode6.meetingminder.store
 * .sideeffects.ShareDaySideEffects] writes the baseline at share time; `monitor/ChangeMonitor`
 * reads it, records what changed, and drops the rows of days that have ended.
 */
@Dao
interface ChangeSnapshotDao {
    /** `REPLACE`: a re-share replaces the baseline and resets [ChangeSnapshotEntity.changesJson]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChangeSnapshotEntity)

    @Query("SELECT * FROM change_snapshot WHERE date = :date")
    suspend fun forDate(date: LocalDate): ChangeSnapshotEntity?

    /** Every shared day's baseline, in date order. */
    @Query("SELECT * FROM change_snapshot ORDER BY date")
    suspend fun all(): List<ChangeSnapshotEntity>

    /** Streams every row on each change, for the "changed since you shared" banner. */
    @Query("SELECT * FROM change_snapshot ORDER BY date")
    fun observeAll(): Flow<List<ChangeSnapshotEntity>>

    /**
     * Records what the latest check found changed on [date], but only onto the baseline
     * taken at [takenAt]: a re-share in the meantime replaced it, and changes found against
     * the old one mean nothing any more. Returns the rows updated (0 or 1).
     */
    @Query("UPDATE change_snapshot SET changes_json = :changesJson WHERE date = :date AND taken_at = :takenAt")
    suspend fun setChanges(date: LocalDate, takenAt: Long, changesJson: String): Int

    /** "Mark as not shared" drops the baseline along with `day_plan.shared_at`, and so does a day ending. */
    @Query("DELETE FROM change_snapshot WHERE date = :date")
    suspend fun delete(date: LocalDate)
}

package com.episode6.meetingminder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.time.LocalDate

/**
 * Room access to `change_snapshot` (TODO.md §3.4). [com.episode6.meetingminder.store
 * .sideeffects.ShareDaySideEffects] is the only writer in this PR; PR-11's differ is the
 * reader.
 */
@Dao
interface ChangeSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChangeSnapshotEntity)

    @Query("SELECT * FROM change_snapshot WHERE date = :date")
    suspend fun forDate(date: LocalDate): ChangeSnapshotEntity?

    /** "Mark as not shared" drops the baseline along with `day_plan.shared_at`. */
    @Query("DELETE FROM change_snapshot WHERE date = :date")
    suspend fun delete(date: LocalDate)
}

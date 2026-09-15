package com.episode6.meetingminder.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * `day_plan` (TODO.md §3.4): one row per day that has ever had a selection, alarms set, or
 * a share. [alarmsSetAt]/[sharedAt]/[sharedSnapshot] stay null until "Set alarms"/"Share
 * schedule" writes them; [sharedSnapshot] is the merged busy ranges that went into the
 * last share text (`encodeBusyRanges`/`decodeBusyRanges`), read back by PR-11's "Update:"
 * re-share.
 */
@Entity(tableName = "day_plan")
data class DayPlanEntity(
    @PrimaryKey val date: LocalDate,
    @ColumnInfo(name = "alarms_set_at") val alarmsSetAt: Long? = null,
    @ColumnInfo(name = "shared_at") val sharedAt: Long? = null,
    @ColumnInfo(name = "shared_snapshot") val sharedSnapshot: String? = null,
)

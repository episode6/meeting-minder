package com.episode6.meetingminder.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * `change_snapshot` (TODO.md §3.4/§4.3): the baseline the differ
 * (`monitor/ChangeDetector`) compares a fresh `Instances` read against, one row per
 * **shared** day. Written by [com.episode6.meetingminder.store.sideeffects.ShareDaySideEffects]
 * at share time — every event on the day, selected or not, meeting or not, as opposed to
 * [DayPlanEntity.sharedSnapshot] (only the *busy ranges* that went into the message).
 * [eventsJson] is a JSON array of [ChangeSnapshotEventDto]; see
 * [encodeChangeSnapshotEvents]/[decodeChangeSnapshotEvents].
 *
 * [changesJson] (PR-11) is what the last check found changed since the share
 * ([encodeScheduleChanges]); `monitor/ChangeMonitor` rewrites it, the day view's "changed
 * since you shared" banner is streamed from it, and it's how a check tells a change it has
 * already notified about from a new one. A re-share replaces the whole row, so it starts
 * empty again.
 */
@Entity(tableName = "change_snapshot")
data class ChangeSnapshotEntity(
    @PrimaryKey val date: LocalDate,
    @ColumnInfo(name = "taken_at") val takenAt: Long,
    @ColumnInfo(name = "events_json") val eventsJson: String,
    @ColumnInfo(name = "changes_json", defaultValue = "[]") val changesJson: String = "[]",
)

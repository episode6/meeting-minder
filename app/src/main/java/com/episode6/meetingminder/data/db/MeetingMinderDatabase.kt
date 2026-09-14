package com.episode6.meetingminder.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The app's Room database (TODO.md §3.4). Schemas are exported to `app/schemas/` so
 * migrations stay reviewable; the **pre-1.0 policy** (AGENTS.md) is
 * `fallbackToDestructiveMigration` until the first `v1.0.0` tag, so new tables (and the
 * columns PR-8b/9 add) just bump [Database.version] without a hand-written migration.
 * Version 2 added `scheduled_alarm` (PR-8); version 3 adds `change_snapshot` (PR-9; PR-11
 * is the reader).
 */
@Database(
    entities = [DayPlanEntity::class, SelectedEventEntity::class, ScheduledAlarmEntity::class, ChangeSnapshotEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MeetingMinderDatabase : RoomDatabase() {
    abstract fun dayPlanDao(): DayPlanDao
    abstract fun scheduledAlarmDao(): ScheduledAlarmDao
    abstract fun changeSnapshotDao(): ChangeSnapshotDao
}

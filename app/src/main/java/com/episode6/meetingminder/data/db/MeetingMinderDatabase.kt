package com.episode6.meetingminder.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The app's Room database (TODO.md §3.4). Schemas are exported to `app/schemas/` so
 * migrations stay reviewable; the **pre-1.0 policy** (AGENTS.md) is
 * `fallbackToDestructiveMigration` until the first `v1.0.0` tag, so this PR's tables (and
 * the columns PR-8/8b/9 add to them) never need a real migration written by hand. Later
 * PRs add `scheduled_alarm` (PR-8) and `change_snapshot` (PR-11) here.
 */
@Database(
    entities = [DayPlanEntity::class, SelectedEventEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MeetingMinderDatabase : RoomDatabase() {
    abstract fun dayPlanDao(): DayPlanDao
}

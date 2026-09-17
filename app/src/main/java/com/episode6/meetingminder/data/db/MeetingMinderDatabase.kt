package com.episode6.meetingminder.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The app's Room database (TODO.md §3.4). Schemas are exported to `app/schemas/` so
 * migrations stay reviewable; the **pre-1.0 policy** (AGENTS.md) is
 * `fallbackToDestructiveMigration` until the first `v1.0.0` tag, so new tables (and the
 * columns PR-8b/9 add) just bump [Database.version] without a hand-written migration.
 * Version 2 added `scheduled_alarm` (PR-8); version 3 adds `change_snapshot` (PR-9); version 4 adds `scheduled_alarm.location` and `timed_out` (PR-10's
 * ringing screen and auto-timeout); version 5 adds `change_snapshot.changes_json` (PR-11's
 * record of what changed since a day was shared); version 6 adds `busy_block` (PR-15b's
 * record of the busy blocks the app wrote to the user's chosen calendar, TODO.md §4.7).
 */
@Database(
    entities = [
        DayPlanEntity::class,
        SelectedEventEntity::class,
        ScheduledAlarmEntity::class,
        ChangeSnapshotEntity::class,
        BusyBlockEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MeetingMinderDatabase : RoomDatabase() {
    abstract fun dayPlanDao(): DayPlanDao
    abstract fun scheduledAlarmDao(): ScheduledAlarmDao
    abstract fun changeSnapshotDao(): ChangeSnapshotDao
    abstract fun busyBlockDao(): BusyBlockDao
}

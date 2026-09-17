package com.episode6.meetingminder.data.db

import androidx.room.AutoMigration
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
 * Version 7 adds `busy_block.title` and is the first **real** migration (an [AutoMigration],
 * the new column defaulting to `busy`): dropping `busy_block` would orphan every block
 * already on the user's calendar, since the app only ever deletes ids it finds there.
 */
@Database(
    entities = [
        DayPlanEntity::class,
        SelectedEventEntity::class,
        ScheduledAlarmEntity::class,
        ChangeSnapshotEntity::class,
        BusyBlockEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 6, to = 7)],
)
@TypeConverters(Converters::class)
abstract class MeetingMinderDatabase : RoomDatabase() {
    abstract fun dayPlanDao(): DayPlanDao
    abstract fun scheduledAlarmDao(): ScheduledAlarmDao
    abstract fun changeSnapshotDao(): ChangeSnapshotDao
    abstract fun busyBlockDao(): BusyBlockDao
}

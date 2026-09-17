package com.episode6.meetingminder.di

import android.content.Context
import androidx.room.Room
import com.episode6.meetingminder.data.db.BusyBlockDao
import com.episode6.meetingminder.data.db.ChangeSnapshotDao
import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.data.db.MeetingMinderDatabase
import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Binds [MeetingMinderDatabase] (TODO.md §3.4). `fallbackToDestructiveMigration(true)` is
 * the pre-1.0 policy (AGENTS.md): every schema change until the first `v1.0.0` tag just
 * drops and recreates the tables instead of a hand-written `Migration`. It stays as the
 * fallback for a version with no migration path; 6 → 7 has one (the database's
 * `autoMigrations`), which Room prefers over the destructive fallback.
 */
@ContributesTo(AppScope::class)
interface DatabaseModule {
    @Provides
    @SingleIn(AppScope::class)
    fun meetingMinderDatabase(context: Context): MeetingMinderDatabase =
        Room.databaseBuilder(context, MeetingMinderDatabase::class.java, "meeting-minder.db")
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    fun dayPlanDao(database: MeetingMinderDatabase): DayPlanDao = database.dayPlanDao()

    @Provides
    fun scheduledAlarmDao(database: MeetingMinderDatabase): ScheduledAlarmDao = database.scheduledAlarmDao()

    @Provides
    fun changeSnapshotDao(database: MeetingMinderDatabase): ChangeSnapshotDao = database.changeSnapshotDao()

    @Provides
    fun busyBlockDao(database: MeetingMinderDatabase): BusyBlockDao = database.busyBlockDao()
}

package com.episode6.meetingminder.data.db

import kotlinx.coroutines.flow.first
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** [ChangeSnapshotDao] against a real (in-memory) SQLite database, like [DayPlanDaoTest]. */
@RunWith(RobolectricTestRunner::class)
class ChangeSnapshotDaoTest {

    private lateinit var database: MeetingMinderDatabase
    private lateinit var dao: ChangeSnapshotDao

    private val today = LocalDate.of(2026, 9, 14)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MeetingMinderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.changeSnapshotDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun upsert_replacesAnExistingRowForTheSameDate() = runTest {
        dao.upsert(ChangeSnapshotEntity(today, takenAt = 1_000, eventsJson = "[]"))
        dao.upsert(ChangeSnapshotEntity(today, takenAt = 2_000, eventsJson = "[1]"))

        assertThat(dao.forDate(today)).isEqualTo(ChangeSnapshotEntity(today, takenAt = 2_000, eventsJson = "[1]"))
    }

    @Test
    fun forDate_withNoRow_returnsNull() = runTest {
        assertThat(dao.forDate(today)).isNull()
    }

    @Test
    fun delete_removesTheRow() = runTest {
        dao.upsert(ChangeSnapshotEntity(today, takenAt = 1_000, eventsJson = "[]"))

        dao.delete(today)

        assertThat(dao.forDate(today)).isNull()
    }

    @Test
    fun upsert_startsWithNoRecordedChanges_andAReShareResetsThem() = runTest {
        dao.upsert(ChangeSnapshotEntity(today, takenAt = 1_000, eventsJson = "[]"))
        assertThat(dao.forDate(today)!!.changesJson).isEqualTo("[]")
        dao.setChanges(today, takenAt = 1_000, changesJson = "[1]")

        dao.upsert(ChangeSnapshotEntity(today, takenAt = 2_000, eventsJson = "[]"))

        assertThat(dao.forDate(today)!!.changesJson).isEqualTo("[]")
    }

    @Test
    fun setChanges_onlyWritesOntoTheBaselineItWasFoundAgainst() = runTest {
        dao.upsert(ChangeSnapshotEntity(today, takenAt = 1_000, eventsJson = "[]"))

        assertThat(dao.setChanges(today, takenAt = 999, changesJson = "[1]")).isEqualTo(0)
        assertThat(dao.forDate(today)!!.changesJson).isEqualTo("[]")
        assertThat(dao.setChanges(today, takenAt = 1_000, changesJson = "[1]")).isEqualTo(1)
        assertThat(dao.forDate(today)!!.changesJson).isEqualTo("[1]")
    }

    @Test
    fun allAndObserveAll_listEverySharedDayInDateOrder() = runTest {
        dao.upsert(ChangeSnapshotEntity(today.plusDays(1), takenAt = 1, eventsJson = "[]"))
        dao.upsert(ChangeSnapshotEntity(today, takenAt = 1, eventsJson = "[]"))

        assertThat(dao.all().map { it.date }).isEqualTo(listOf(today, today.plusDays(1)))
        assertThat(dao.observeAll().first().map { it.date }).isEqualTo(listOf(today, today.plusDays(1)))
    }
}

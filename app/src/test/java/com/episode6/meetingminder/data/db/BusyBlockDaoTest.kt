package com.episode6.meetingminder.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** `busy_block` against a real in-memory database: the `event_id` key, the day and from-day queries, the id stream. */
@RunWith(RobolectricTestRunner::class)
class BusyBlockDaoTest {

    private lateinit var database: MeetingMinderDatabase
    private lateinit var dao: BusyBlockDao

    private val yesterday = LocalDate.of(2026, 9, 13)
    private val today = LocalDate.of(2026, 9, 14)
    private val tomorrow = LocalDate.of(2026, 9, 15)

    private fun row(eventId: Long, date: LocalDate, begin: Long = 1_000, calendarId: Long = 7) =
        BusyBlockEntity(eventId = eventId, date = date, calendarId = calendarId, beginMillis = begin, endMillis = begin + 1_000)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MeetingMinderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.busyBlockDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun blocksOn_listsOnlyThatDay_inTimeOrder() = runTest {
        dao.upsert(row(3, today, begin = 3_000))
        dao.upsert(row(1, today, begin = 1_000))
        dao.upsert(row(2, tomorrow, begin = 500))

        assertThat(dao.blocksOn(today)).containsExactly(row(1, today, begin = 1_000), row(3, today, begin = 3_000))
        assertThat(dao.blocksOn(yesterday)).isEmpty()
    }

    @Test
    fun blocksFrom_listsThatDayAndLater_leavingThePastAsHistory() = runTest {
        dao.upsert(row(1, yesterday))
        dao.upsert(row(2, tomorrow))
        dao.upsert(row(3, today))

        assertThat(dao.blocksFrom(today)).containsExactly(row(3, today), row(2, tomorrow))
    }

    @Test
    fun upsert_replacesTheRowWithTheSameEventId() = runTest {
        dao.upsert(row(1, today, begin = 1_000))
        dao.upsert(row(1, tomorrow, begin = 2_000, calendarId = 8))

        assertThat(dao.blocksOn(today)).isEmpty()
        assertThat(dao.blocksOn(tomorrow)).containsExactly(row(1, tomorrow, begin = 2_000, calendarId = 8))
    }

    @Test
    fun delete_removesOneRow_andReportsWhetherItWasThere() = runTest {
        dao.upsert(row(1, today))
        dao.upsert(row(2, today))

        assertThat(dao.delete(1)).isEqualTo(1)
        assertThat(dao.delete(1)).isEqualTo(0)
        assertThat(dao.blocksOn(today)).containsExactly(row(2, today))
    }

    @Test
    fun deleteOn_removesTheWholeDay_andNothingElse() = runTest {
        dao.upsert(row(1, today))
        dao.upsert(row(2, today))
        dao.upsert(row(3, tomorrow))

        assertThat(dao.deleteOn(today)).isEqualTo(2)
        assertThat(dao.blocksOn(today)).isEmpty()
        assertThat(dao.blocksOn(tomorrow)).containsExactly(row(3, tomorrow))
    }

    @Test
    fun observeEventIds_streamsEveryRecordedId_asASet() = runTest {
        assertThat(dao.observeEventIds().first()).isEmpty()

        dao.upsert(row(1, today))
        dao.upsert(row(2, tomorrow))

        assertThat(dao.observeEventIds().first()).isEqualTo(setOf(1L, 2L))
    }
}

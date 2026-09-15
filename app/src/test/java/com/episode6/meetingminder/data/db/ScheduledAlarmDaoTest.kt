package com.episode6.meetingminder.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** `scheduled_alarm` against a real in-memory database: autoincrement ids, the state filter, the enum column. */
@RunWith(RobolectricTestRunner::class)
class ScheduledAlarmDaoTest {

    private lateinit var database: MeetingMinderDatabase
    private lateinit var dao: ScheduledAlarmDao

    private val today = LocalDate.of(2026, 9, 14)
    private val tomorrow = today.plusDays(1)

    private fun row(date: LocalDate, eventId: Long) = ScheduledAlarmEntity(
        date = date, eventId = eventId, instanceTime = 0, fireAt = 1_000, title = "Event $eventId",
        beginMillis = 2_000, endMillis = 3_000, soundIndex = 4,
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MeetingMinderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.scheduledAlarmDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun insert_assignsIncreasingIds() = runTest {
        val first = dao.insert(row(today, 1))
        val second = dao.insert(row(today, 2))

        assertThat(first).isEqualTo(1L)
        assertThat(second).isEqualTo(2L)
        assertThat(dao.byId(second)).isEqualTo(row(today, 2).copy(alarmId = 2))
        assertThat(dao.byId(99)).isNull()
    }

    @Test
    fun scheduledOn_returnsOnlyThatDaysArmedRows() = runTest {
        dao.insert(row(today, 1))
        val cancelledId = dao.insert(row(today, 2))
        dao.insert(row(tomorrow, 3))

        dao.setState(cancelledId, AlarmState.CANCELLED)

        assertThat(dao.scheduledOn(today)).containsExactly(row(today, 1).copy(alarmId = 1))
        assertThat(dao.allScheduled()).containsExactly(row(today, 1).copy(alarmId = 1), row(tomorrow, 3).copy(alarmId = 3))
        assertThat(dao.byId(cancelledId)?.state).isEqualTo(AlarmState.CANCELLED)
    }

    @Test
    fun observeScheduled_streamsTheArmedRows_acrossEveryDay() = runTest {
        dao.insert(row(today, 1))
        val cancelledId = dao.insert(row(tomorrow, 2))
        dao.setState(cancelledId, AlarmState.CANCELLED)

        assertThat(dao.observeScheduled().first()).containsExactly(row(today, 1).copy(alarmId = 1))

        dao.insert(row(tomorrow, 3))

        assertThat(dao.observeScheduled().first()).containsExactly(row(today, 1).copy(alarmId = 1), row(tomorrow, 3).copy(alarmId = 3))
    }

    @Test
    fun snoozedRows_countAsArmed() = runTest {
        dao.insert(row(today, 1))
        val snoozedId = dao.insert(row(today, 2))
        val firedId = dao.insert(row(today, 3))
        dao.setState(snoozedId, AlarmState.SNOOZED)
        dao.setState(firedId, AlarmState.FIRED)

        val armed = arrayOf(row(today, 1).copy(alarmId = 1), row(today, 2).copy(alarmId = snoozedId, state = AlarmState.SNOOZED))
        assertThat(dao.scheduledOn(today)).containsExactly(*armed)
        assertThat(dao.allScheduled()).containsExactly(*armed)
        assertThat(dao.observeScheduled().first()).containsExactly(*armed)
    }

    @Test
    fun locationAndTimedOut_roundTrip() = runTest {
        val id = dao.insert(row(today, 1).copy(location = "Room 4", timedOut = true))

        assertThat(dao.byId(id)).isEqualTo(row(today, 1).copy(alarmId = id, location = "Room 4", timedOut = true))
    }

    @Test
    fun update_retimesInPlace() = runTest {
        val id = dao.insert(row(today, 1))

        dao.update(row(today, 1).copy(alarmId = id, fireAt = 5_000, beginMillis = 6_000))

        assertThat(dao.byId(id)).isEqualTo(row(today, 1).copy(alarmId = id, fireAt = 5_000, beginMillis = 6_000))
    }
}

package com.episode6.meetingminder.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * [MeetingMinderDatabase] against a real (in-memory) SQLite database: proves the two
 * entities' Room annotations, the `selected_event` composite primary key, and
 * [DayPlanDao]'s queries actually compile to working SQL, not just that [buildDayPlans]
 * maps rows correctly ([DayPlanMappingTest]).
 */
@RunWith(RobolectricTestRunner::class)
class DayPlanDaoTest {

    private lateinit var database: MeetingMinderDatabase
    private lateinit var dao: DayPlanDao

    private val today = LocalDate.of(2026, 9, 14)
    private val standup = SelectedEventEntity(
        date = today,
        eventId = 1,
        instanceTime = 0,
        title = "Standup",
        beginMillis = 1_000,
        endMillis = 2_000,
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MeetingMinderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.dayPlanDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun upsertThenDelete_roundTripsThroughTheCompositeKey() = runTest {
        dao.upsertSelectedEvent(standup)

        assertThat(dao.selectedEventsOn(today)).containsExactly(standup)
        assertThat(dao.observeSelectedEvents().first()).containsExactly(standup)

        dao.deleteSelectedEvent(today, standup.eventId, standup.instanceTime)

        assertThat(dao.selectedEventsOn(today)).isEmpty()
    }

    @Test
    fun upsert_replacesAnExistingRowForTheSameKey() = runTest {
        dao.upsertSelectedEvent(standup)
        dao.upsertSelectedEvent(standup.copy(title = "Standup (moved)"))

        assertThat(dao.selectedEventsOn(today)).containsExactly(standup.copy(title = "Standup (moved)"))
    }

    @Test
    fun dayPlanRow_isObservedIndependentlyOfSelections() = runTest {
        val plan = DayPlanEntity(date = today, alarmsSetAt = 5_000)

        dao.upsertDayPlan(plan)

        assertThat(dao.observeDayPlans().first()).containsExactly(plan)
    }
}

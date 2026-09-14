package com.episode6.meetingminder.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.episode6.meetingminder.model.RsvpState
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

    @Test
    fun markAlarmsSet_createsThePlanRowIfMissing_andKeepsOtherColumnsIfNot() = runTest {
        dao.markAlarmsSet(today, 5_000)
        assertThat(dao.observeDayPlans().first()).containsExactly(DayPlanEntity(date = today, alarmsSetAt = 5_000))

        dao.upsertDayPlan(DayPlanEntity(date = today, alarmsSetAt = 5_000, sharedAt = 6_000))
        dao.markAlarmsSet(today, 7_000)

        assertThat(dao.observeDayPlans().first()).containsExactly(DayPlanEntity(date = today, alarmsSetAt = 7_000, sharedAt = 6_000))
    }

    @Test
    fun toggle_clearsTheDaysAlarmsSetAt_soTheFabRevertsToSetAlarms() = runTest {
        dao.markAlarmsSet(today, 5_000)

        dao.toggleSelectedEvent(standup)

        assertThat(dao.observeDayPlans().first().single().alarmsSetAt).isNull()
        assertThat(dao.selectedEventsOn(today)).containsExactly(standup)
    }

    @Test
    fun armSelectedEvent_pointsTheRowAtItsAlarm_withoutTouchingRsvpColumns() = runTest {
        dao.upsertSelectedEvent(standup.copy(rsvpState = RsvpState.PENDING, rsvpEventId = 42))

        dao.armSelectedEvent(today, standup.eventId, standup.instanceTime, alarmId = 9, alarmAt = 700, title = "Standup (moved)", beginMillis = 1_500, endMillis = 2_500)

        assertThat(dao.selectedEventsOn(today).single()).isEqualTo(
            standup.copy(alarmId = 9, alarmAt = 700, title = "Standup (moved)", beginMillis = 1_500, endMillis = 2_500, rsvpState = RsvpState.PENDING, rsvpEventId = 42),
        )
    }

    @Test
    fun setRsvp_storesTheStateByName_withoutTouchingTheAlarmPointer() = runTest {
        dao.upsertSelectedEvent(standup.copy(alarmId = 9, alarmAt = 700))

        dao.setRsvp(today, standup.eventId, standup.instanceTime, RsvpState.ACCEPTED_LOCALLY, rsvpEventId = 1_000)

        assertThat(dao.selectedEventsOn(today).single()).isEqualTo(
            standup.copy(alarmId = 9, alarmAt = 700, rsvpState = RsvpState.ACCEPTED_LOCALLY, rsvpEventId = 1_000),
        )
        assertThat(database.query("SELECT rsvp_state FROM selected_event", null).use { it.moveToFirst(); it.getString(0) })
            .isEqualTo("ACCEPTED_LOCALLY")
    }

    @Test
    fun recordRsvpDecision_overwritesAnUnansweredRow_butLeavesAnAnsweredOneAlone() = runTest {
        dao.upsertSelectedEvent(standup.copy(alarmId = 9, alarmAt = 700, rsvpState = RsvpState.UNRESPONDABLE))

        assertThat(dao.recordRsvpDecision(today, standup.eventId, standup.instanceTime, RsvpState.PENDING)).isEqualTo(1)
        assertThat(dao.selectedEventsOn(today).single())
            .isEqualTo(standup.copy(alarmId = 9, alarmAt = 700, rsvpState = RsvpState.PENDING))

        dao.setRsvp(today, standup.eventId, standup.instanceTime, RsvpState.ACCEPTED_LOCALLY, rsvpEventId = 1_000)
        assertThat(dao.recordRsvpDecision(today, standup.eventId, standup.instanceTime, RsvpState.NOT_APPLICABLE)).isEqualTo(0)
        assertThat(dao.selectedEventsOn(today).single())
            .isEqualTo(standup.copy(alarmId = 9, alarmAt = 700, rsvpState = RsvpState.ACCEPTED_LOCALLY, rsvpEventId = 1_000))

        dao.setRsvp(today, standup.eventId, standup.instanceTime, RsvpState.SYNCED, rsvpEventId = 1_000)
        assertThat(dao.recordRsvpDecision(today, standup.eventId, standup.instanceTime, RsvpState.PENDING)).isEqualTo(0)
        assertThat(dao.selectedEventsOn(today).single().rsvpState).isEqualTo(RsvpState.SYNCED)
    }

    @Test
    fun markShared_recordsSharedAtAndSnapshot_creatingTheRowIfNeeded() = runTest {
        dao.markShared(today, sharedAt = 8_000, sharedSnapshot = "[]")

        assertThat(dao.observeDayPlans().first()).containsExactly(DayPlanEntity(today, sharedAt = 8_000, sharedSnapshot = "[]"))
    }

    @Test
    fun clearShared_clearsSharedAtAndSnapshot_leavingAlarmsSetAtAlone() = runTest {
        dao.upsertDayPlan(DayPlanEntity(today, alarmsSetAt = 5_000, sharedAt = 8_000, sharedSnapshot = "[]"))

        dao.clearShared(today)

        assertThat(dao.observeDayPlans().first()).containsExactly(DayPlanEntity(today, alarmsSetAt = 5_000))
    }
}

package com.episode6.meetingminder.store.sideeffects

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.data.db.DayPlanEntity
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.db.SelectedEventEntity
import com.episode6.meetingminder.model.DayPlan
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.SelectedEvent
import com.episode6.meetingminder.store.SetDayPlans
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ObserveDayPlansSideEffectsTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val standup = SelectedEventEntity(
        date = today,
        eventId = 1,
        instanceTime = 0,
        title = "Standup",
        beginMillis = 1_000,
        endMillis = 2_000,
    )

    @Test
    fun emitsTheCurrentTables_wheneverAnyFlowChanges() = runTest {
        val dao = FakeDayPlanDao()
        val alarmDao = FakeScheduledAlarmDao()
        val sideEffect = object : ObserveDayPlansSideEffects {}.observeDayPlans(dao, alarmDao)

        sideEffect.output(MutableSharedFlow(), state = TestAppState).test {
            assertThat(awaitItem()).isEqualTo(SetDayPlans(emptyMap()))

            dao.selectionsFlow.value = listOf(standup)

            assertThat(awaitItem()).isEqualTo(
                SetDayPlans(
                    mapOf(
                        today to DayPlan(
                            date = today,
                            selected = mapOf(
                                EventKey(1, 0) to SelectedEvent(
                                    key = EventKey(1, 0),
                                    title = "Standup",
                                    begin = Instant.ofEpochMilli(1_000),
                                    end = Instant.ofEpochMilli(2_000),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            dao.plansFlow.value = listOf(DayPlanEntity(date = today, alarmsSetAt = 5_000))

            val updated = awaitItem() as SetDayPlans
            assertThat(updated.dayPlans.getValue(today).alarmsSetAt).isEqualTo(Instant.ofEpochMilli(5_000))

            alarmDao.insert(
                ScheduledAlarmEntity(date = today, eventId = 1, instanceTime = 0, fireAt = 700, title = "Standup", beginMillis = 1_000, endMillis = 2_000, soundIndex = 3),
            )

            val armed = awaitItem() as SetDayPlans
            assertThat(armed.dayPlans.getValue(today).armedKeys).isEqualTo(setOf(EventKey(1, 0)))
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The observe-only gotcha (AGENTS.md): a real `actions` flow must still be subscribable. */
    @Test
    fun subscribesToActions_soItDoesntStarveOtherEffects() = runTest {
        val actions = MutableSharedFlow<com.episode6.redux.Action>()
        val sideEffect = object : ObserveDayPlansSideEffects {}.observeDayPlans(FakeDayPlanDao(), FakeScheduledAlarmDao())

        sideEffect.output(actions, state = TestAppState).test {
            assertThat(awaitItem()).isEqualTo(SetDayPlans(emptyMap()))
            assertThat(actions.subscriptionCount.value).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

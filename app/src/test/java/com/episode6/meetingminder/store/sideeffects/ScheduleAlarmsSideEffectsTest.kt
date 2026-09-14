package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.episode6.meetingminder.R
import com.episode6.meetingminder.alarm.AlarmReconciliation
import com.episode6.meetingminder.alarm.FakeAlarmScheduler
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.DayPlanEntity
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.db.SelectedEventEntity
import com.episode6.meetingminder.data.db.toSelectedEventEntity
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.Settings
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.SetAlarms
import com.episode6.meetingminder.store.ShowMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

class ScheduleAlarmsSideEffectsTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val tomorrow = today.plusDays(1)
    private val now = Instant.parse("2026-09-14T08:35:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun at(hour: Int, minute: Int = 0): Instant = Instant.parse("2026-09-14T%02d:%02d:00Z".format(hour, minute))

    private val standup = testCalendarEvent(1, at(9), at(9, 30), title = "Daily standup")
    private val designReview = testCalendarEvent(2, at(10), at(11), title = "Design review")
    private val earlyBird = testCalendarEvent(3, at(8), at(8, 30), title = "Early bird")
    private val stateWithEvents = TestAppState.copy(
        eventsByDay = mapOf(today to DayEvents(today, listOf(standup, designReview, earlyBird), Instant.EPOCH)),
    )

    private val scheduler = FakeAlarmScheduler()
    private val alarmDao = FakeScheduledAlarmDao()
    private val settings = FakeSettingsRepository(Settings(leadTime = Duration.ofMinutes(5)))

    private fun sideEffect(dayPlanDao: FakeDayPlanDao) =
        object : ScheduleAlarmsSideEffects {}.scheduleAlarms(dayPlanDao, alarmDao, scheduler, settings, clock, Random(seed = 1))

    private fun selection(event: com.episode6.meetingminder.model.CalendarEvent) = event.toSelectedEventEntity(today)

    @Test
    fun setAlarms_armsEverySelection_pointsSelectionsAtTheirAlarms_andRecordsAlarmsSetAt() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup), selection(designReview)))

        val output = sideEffect(dayPlanDao).output(SetAlarms(today), state = stateWithEvents).toList()

        val standupAlarm = alarmDao.rows.getValue(1)
        val reviewAlarm = alarmDao.rows.getValue(2)
        assertThat(standupAlarm.fireAt).isEqualTo(at(8, 55).toEpochMilli())
        assertThat(reviewAlarm.fireAt).isEqualTo(at(9, 55).toEpochMilli())
        assertThat(scheduler.armed.values.toList()).containsExactly(standupAlarm, reviewAlarm)
        assertThat(dayPlanDao.selectedEventsOn(today)).containsExactly(
            selection(standup).copy(alarmId = 1, alarmAt = at(8, 55).toEpochMilli()),
            selection(designReview).copy(alarmId = 2, alarmAt = at(9, 55).toEpochMilli()),
        )
        assertThat(dayPlanDao.plansFlow.value).containsExactly(DayPlanEntity(today, alarmsSetAt = now.toEpochMilli()))
        val message = (output.single() as ShowMessage).message
        assertThat(message.text).isEqualTo(R.plurals.day_alarms_set_today)
        assertThat(message.quantity).isEqualTo(2)
        assertThat(message.formatArgs).isEqualTo(listOf<Any>(2))
    }

    @Test
    fun setAlarms_skipsPastAlarmTimes_andSaysSo() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(earlyBird), selection(standup)))

        val output = sideEffect(dayPlanDao).output(SetAlarms(today), state = stateWithEvents).toList()

        assertThat(alarmDao.rows.values.map { it.eventId }).containsExactly(standup.eventId)
        assertThat(dayPlanDao.selectedEventsOn(today).first { it.eventId == earlyBird.eventId }.alarmId).isNull()
        val message = (output.single() as ShowMessage).message
        assertThat(message.text).isEqualTo(R.string.day_alarms_set_some_skipped)
        assertThat(message.formatArgs).isEqualTo(listOf<Any>(1, 1))
    }

    @Test
    fun setAlarms_whenEverySelectionIsPast_reportsOnlyTheSkippedCount() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(earlyBird)))

        val output = sideEffect(dayPlanDao).output(SetAlarms(today), state = stateWithEvents).toList()

        val message = (output.single() as ShowMessage).message
        assertThat(message.text).isEqualTo(R.plurals.day_alarms_skipped)
        assertThat(message.quantity).isEqualTo(1)
        // the day still counts as "alarms set": the FAB flips to Share with nothing armed
        assertThat(dayPlanDao.plansFlow.value.single().alarmsSetAt).isEqualTo(now.toEpochMilli())
    }

    @Test
    fun setAlarms_cancelsTheAlarmOfADeselectedEvent() = runTest {
        val armedStandup = ScheduledAlarmEntity(
            alarmId = 1, date = today, eventId = standup.eventId, instanceTime = 0, fireAt = at(8, 55).toEpochMilli(),
            title = standup.title, beginMillis = standup.begin.toEpochMilli(), endMillis = standup.end.toEpochMilli(), soundIndex = 5,
        )
        alarmDao.rows[1] = armedStandup
        scheduler.schedule(armedStandup)
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(designReview)))

        sideEffect(dayPlanDao).output(SetAlarms(today), state = stateWithEvents).toList()

        assertThat(alarmDao.rows.getValue(1).state).isEqualTo(AlarmState.CANCELLED)
        assertThat(scheduler.cancelled).containsExactly(1L)
        assertThat(scheduler.armed.keys.toList()).containsExactly(2L)
    }

    @Test
    fun setAlarms_retimesAMovedEventInPlace() = runTest {
        val armedStandup = ScheduledAlarmEntity(
            alarmId = 1, date = today, eventId = standup.eventId, instanceTime = 0, fireAt = at(8, 55).toEpochMilli(),
            title = standup.title, beginMillis = standup.begin.toEpochMilli(), endMillis = standup.end.toEpochMilli(), soundIndex = 5,
        )
        alarmDao.rows[1] = armedStandup
        val moved = standup.copy(begin = at(9, 30), end = at(10))
        val state = TestAppState.copy(eventsByDay = mapOf(today to DayEvents(today, listOf(moved), Instant.EPOCH)))
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup).copy(alarmId = 1, alarmAt = armedStandup.fireAt)))

        sideEffect(dayPlanDao).output(SetAlarms(today), state = state).toList()

        val retimed = armedStandup.copy(fireAt = at(9, 25).toEpochMilli(), beginMillis = at(9, 30).toEpochMilli(), endMillis = at(10).toEpochMilli())
        assertThat(alarmDao.rows.getValue(1)).isEqualTo(retimed)
        assertThat(scheduler.armed.getValue(1)).isEqualTo(retimed)
        assertThat(dayPlanDao.selectedEventsOn(today).single()).isEqualTo(
            selection(moved).copy(alarmId = 1, alarmAt = at(9, 25).toEpochMilli()),
        )
    }

    @Test
    fun setAlarms_pointsAReselectedEventBackAtItsKeptAlarm() = runTest {
        val armedStandup = ScheduledAlarmEntity(
            alarmId = 1, date = today, eventId = standup.eventId, instanceTime = 0, fireAt = at(8, 55).toEpochMilli(),
            title = standup.title, beginMillis = standup.begin.toEpochMilli(), endMillis = standup.end.toEpochMilli(), soundIndex = 5,
        )
        alarmDao.rows[1] = armedStandup
        // toggled off and on again: the selection row was re-created without its pointer
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        sideEffect(dayPlanDao).output(SetAlarms(today), state = stateWithEvents).toList()

        assertThat(dayPlanDao.selectedEventsOn(today).single().alarmId).isEqualTo(1)
        assertThat(alarmDao.rows.getValue(1)).isEqualTo(armedStandup)
    }

    @Test
    fun setAlarms_usesTheLeadTimeSetting() = runTest {
        settings.setLeadTime(Duration.ofMinutes(15))
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        sideEffect(dayPlanDao).output(SetAlarms(today), state = stateWithEvents).toList()

        assertThat(alarmDao.rows.getValue(1).fireAt).isEqualTo(at(8, 45).toEpochMilli())
    }

    @Test
    fun setAlarms_onAnotherDay_namesTheDayInTheSnackbar() = runTest {
        val laterStandup = testCalendarEvent(9, Instant.parse("2026-09-15T09:00:00Z"), Instant.parse("2026-09-15T09:30:00Z"))
        val state = TestAppState.copy(eventsByDay = mapOf(tomorrow to DayEvents(tomorrow, listOf(laterStandup), Instant.EPOCH)))
        val dayPlanDao = FakeDayPlanDao(selections = listOf(laterStandup.toSelectedEventEntity(tomorrow)))

        val output = sideEffect(dayPlanDao).output(SetAlarms(tomorrow), state = state).toList()

        val message = (output.single() as ShowMessage).message
        assertThat(message.text).isEqualTo(R.plurals.day_alarms_set_on_day)
        assertThat(message.formatArgs).isEqualTo(listOf<Any>(1, "Tuesday, Sep 15"))
        assertThat(alarmDao.rows.getValue(1).date).isEqualTo(tomorrow)
    }

    @Test
    fun withoutTheExactAlarmGrant_nothingIsWritten_andAPermissionRecheckIsRequested() = runTest {
        scheduler.canSchedule = false
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        val output = sideEffect(dayPlanDao).output(SetAlarms(today), state = stateWithEvents).toList()

        assertThat(alarmDao.rows).isEmpty()
        assertThat(dayPlanDao.plansFlow.value).isEmpty()
        assertThat(output.map { (it as? ShowMessage)?.message?.text ?: it }).containsExactly(
            R.string.alarms_exact_permission_missing,
            PermissionsMaybeChanged,
        )
    }

    @Test
    fun whenTheOsRefusesToArm_theRowIsCancelled_andThePermissionMessageShown() = runTest {
        scheduler.refuse = true
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        val output = sideEffect(dayPlanDao).output(SetAlarms(today), state = stateWithEvents).toList()

        assertThat(alarmDao.rows.getValue(1).state).isEqualTo(AlarmState.CANCELLED)
        assertThat(dayPlanDao.selectedEventsOn(today).single().alarmId).isNull()
        assertThat((output.first() as ShowMessage).message.text).isEqualTo(R.string.alarms_exact_permission_missing)
    }

    @Test
    fun alarmsSetMessage_picksTheResourceByOutcome() {
        val row = ScheduledAlarmEntity(date = today, eventId = 1, instanceTime = 0, fireAt = 1, title = "", beginMillis = 2, endMillis = 3, soundIndex = 0)
        val skipped = SelectedEventEntity(date = today, eventId = 2, instanceTime = 0, title = "", beginMillis = 2, endMillis = 3)

        assertThat(alarmsSetMessage(AlarmReconciliation(schedule = listOf(row)), today, today).text).isEqualTo(R.plurals.day_alarms_set_today)
        assertThat(alarmsSetMessage(AlarmReconciliation(keep = listOf(row)), tomorrow, today).text).isEqualTo(R.plurals.day_alarms_set_on_day)
        assertThat(alarmsSetMessage(AlarmReconciliation(skipped = listOf(skipped)), today, today).text).isEqualTo(R.plurals.day_alarms_skipped)
        assertThat(alarmsSetMessage(AlarmReconciliation(schedule = listOf(row), skipped = listOf(skipped)), today, today).text)
            .isEqualTo(R.string.day_alarms_set_some_skipped)
    }
}

package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.episode6.meetingminder.R
import com.episode6.meetingminder.alarm.AlarmReconciliation
import com.episode6.meetingminder.alarm.FakeAlarmScheduler
import com.episode6.meetingminder.data.calendar.CalendarFilter
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.DayPlanEntity
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.db.SelectedEventEntity
import com.episode6.meetingminder.data.db.toSelectedEventEntity
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.Settings
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.RsvpState
import com.episode6.meetingminder.model.SelfStatus
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.RsvpAccept
import com.episode6.meetingminder.store.SetAlarms
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.redux.Action
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

    private val calendar = CalendarInfo(
        id = 1, accountName = "me", accountType = "LOCAL", displayName = "Work", color = 0, visible = true,
        syncEvents = true, ownerAccount = "me", isPrimary = true, accessLevel = 700, canOrganizerRespond = false,
    )
    private val repository = FakeCalendarRepository(calendars = listOf(calendar))

    private fun sideEffect(dayPlanDao: FakeDayPlanDao) =
        object : ScheduleAlarmsSideEffects {}.scheduleAlarms(dayPlanDao, alarmDao, scheduler, settings, repository, clock, Random(seed = 1))

    /**
     * Runs `SetAlarms(date)` with the provider holding the same events the store's window
     * has loaded (the effect reconciles against the provider, every calendar, declined
     * included; the window is only its fallback).
     */
    private suspend fun run(dayPlanDao: FakeDayPlanDao, state: AppState = stateWithEvents, date: LocalDate = today): List<Action> {
        repository.events.clear()
        state.eventsByDay.forEach { (day, events) -> repository.events[day] = events.events }
        return sideEffect(dayPlanDao).output(SetAlarms(date), state = state).toList()
    }

    private fun selection(event: com.episode6.meetingminder.model.CalendarEvent) = event.toSelectedEventEntity(today)

    /** The snackbar of a reconcile: the one [ShowMessage] in its output (the rest are `RsvpAccept`s). */
    private val List<Action>.message get() = filterIsInstance<ShowMessage>().single().message

    @Test
    fun setAlarms_armsEverySelection_pointsSelectionsAtTheirAlarms_andRecordsAlarmsSetAt() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup), selection(designReview)))

        val output = run(dayPlanDao)

        val standupAlarm = alarmDao.rows.getValue(1)
        val reviewAlarm = alarmDao.rows.getValue(2)
        assertThat(standupAlarm.fireAt).isEqualTo(at(8, 55).toEpochMilli())
        assertThat(reviewAlarm.fireAt).isEqualTo(at(9, 55).toEpochMilli())
        assertThat(scheduler.armed.values.toList()).containsExactly(standupAlarm, reviewAlarm)
        assertThat(dayPlanDao.selectedEventsOn(today)).containsExactly(
            selection(standup).copy(alarmId = 1, alarmAt = at(8, 55).toEpochMilli(), rsvpState = RsvpState.PENDING),
            selection(designReview).copy(alarmId = 2, alarmAt = at(9, 55).toEpochMilli(), rsvpState = RsvpState.PENDING),
        )
        assertThat(dayPlanDao.plansFlow.value).containsExactly(DayPlanEntity(today, alarmsSetAt = now.toEpochMilli()))
        val message = output.message
        assertThat(message.text).isEqualTo(R.plurals.day_alarms_set_today)
        assertThat(message.quantity).isEqualTo(2)
        assertThat(message.formatArgs).isEqualTo(listOf<Any>(2))
    }

    @Test
    fun setAlarms_fansOutOneRsvpAcceptPerNewlyArmedInvite_afterMarkingItPending() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup), selection(designReview)))

        val output = run(dayPlanDao)

        assertThat(output.filterIsInstance<RsvpAccept>()).containsExactly(RsvpAccept(today, standup.key), RsvpAccept(today, designReview.key))
        assertThat(dayPlanDao.selectedEventsOn(today).map { it.rsvpState }).containsExactly(RsvpState.PENDING, RsvpState.PENDING)
    }

    @Test
    fun setAlarms_recordsTheSkipReason_andSendsNoRsvp_forEventsTheTableSkips() = runTest {
        val soloBlock = testCalendarEvent(4, at(12), at(13), title = "Dentist", meeting = false)
        val readOnlyInvite = testCalendarEvent(5, at(14), at(15), title = "Read-only invite").copy(calendarAccessLevel = 200)
        val alreadyAccepted = testCalendarEvent(6, at(15), at(16), title = "Accepted").copy(selfStatus = SelfStatus.ACCEPTED)
        val state = TestAppState.copy(
            eventsByDay = mapOf(today to DayEvents(today, listOf(standup, soloBlock, readOnlyInvite, alreadyAccepted), Instant.EPOCH)),
        )
        val dayPlanDao = FakeDayPlanDao(
            selections = listOf(selection(standup), selection(soloBlock), selection(readOnlyInvite), selection(alreadyAccepted)),
        )

        val output = run(dayPlanDao, state)

        assertThat(output.filterIsInstance<RsvpAccept>()).containsExactly(RsvpAccept(today, standup.key))
        assertThat(dayPlanDao.selectedEventsOn(today).associate { it.eventId to it.rsvpState }).isEqualTo(
            mapOf(
                standup.eventId to RsvpState.PENDING,
                soloBlock.eventId to RsvpState.NOT_APPLICABLE,
                readOnlyInvite.eventId to RsvpState.UNRESPONDABLE,
                alreadyAccepted.eventId to RsvpState.NOT_APPLICABLE,
            ),
        )
        // every one of them still got its alarm: the RSVP never gates scheduling
        assertThat(scheduler.armed.values.map { it.eventId }.sorted()).isEqualTo(listOf(1L, 4L, 5L, 6L))
    }

    @Test
    fun setAlarms_neverArmsADeclinedOrCancelledSelection_andSaysSo() = runTest {
        // selected earlier, then declined by the user (or cancelled by the organizer) in Google
        // Calendar while the selection row stayed stored: no alarm (MaintainAlarms would only
        // cancel it again), no RSVP (TODO.md §4.6), and the day still counts as "alarms set"
        val declined = testCalendarEvent(4, at(12), at(13), title = "Declined later").copy(selfStatus = SelfStatus.DECLINED)
        val cancelled = testCalendarEvent(5, at(14), at(15), title = "Cancelled later").copy(status = EventStatus.CANCELED)
        val state = TestAppState.copy(
            eventsByDay = mapOf(today to DayEvents(today, listOf(standup, declined, cancelled), Instant.EPOCH)),
        )
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup), selection(declined), selection(cancelled)))

        val output = run(dayPlanDao, state)

        assertThat(output.filterIsInstance<RsvpAccept>()).containsExactly(RsvpAccept(today, standup.key))
        assertThat(dayPlanDao.selectedEventsOn(today).associate { it.eventId to it.rsvpState }).isEqualTo(
            mapOf(
                standup.eventId to RsvpState.PENDING,
                declined.eventId to RsvpState.NOT_APPLICABLE,
                cancelled.eventId to RsvpState.NOT_APPLICABLE,
            ),
        )
        assertThat(scheduler.armed.values.map { it.eventId }).containsExactly(1L)
        assertThat(dayPlanDao.selectedEventsOn(today).filter { it.eventId != standup.eventId }.map { it.alarmId }).isEqualTo(listOf<Long?>(null, null))
        assertThat(dayPlanDao.plansFlow.value.single().alarmsSetAt).isEqualTo(now.toEpochMilli())
        val message = output.message
        assertThat(message.text).isEqualTo(R.string.day_alarms_set_some_not_attending)
        assertThat(message.formatArgs).isEqualTo(listOf<Any>(1, 2))
    }

    @Test
    fun setAlarms_afterMaintenanceCancelledADeclinedMeeting_leavesItUnarmed_andTheDayRests() = runTest {
        // MaintainAlarms cancelled the review's alarm (declined since), kept its selection and
        // put the day back to "Set alarms"; the re-tap must not arm it again, or the next
        // maintenance would cancel it again, for ever
        val declinedReview = designReview.copy(selfStatus = SelfStatus.DECLINED)
        val armedStandup = ScheduledAlarmEntity(
            alarmId = 1, date = today, eventId = standup.eventId, instanceTime = 0, fireAt = at(8, 55).toEpochMilli(),
            title = standup.title, beginMillis = standup.begin.toEpochMilli(), endMillis = standup.end.toEpochMilli(), soundIndex = 5,
        )
        val cancelledReview = armedStandup.copy(
            alarmId = 2, eventId = designReview.eventId, fireAt = at(9, 55).toEpochMilli(), title = designReview.title,
            beginMillis = designReview.begin.toEpochMilli(), endMillis = designReview.end.toEpochMilli(), state = AlarmState.CANCELLED,
        )
        alarmDao.rows[1] = armedStandup
        alarmDao.rows[2] = cancelledReview
        scheduler.schedule(armedStandup)
        val state = TestAppState.copy(eventsByDay = mapOf(today to DayEvents(today, listOf(standup, declinedReview), Instant.EPOCH)))
        val dayPlanDao = FakeDayPlanDao(
            plans = listOf(DayPlanEntity(today, alarmsSetAt = null)),
            selections = listOf(selection(standup).copy(alarmId = 1, alarmAt = armedStandup.fireAt), selection(designReview)),
        )

        val output = run(dayPlanDao, state)

        assertThat(alarmDao.rows.keys.toList()).containsExactly(1L, 2L)
        assertThat(scheduler.armed.keys.toList()).containsExactly(1L)
        assertThat(dayPlanDao.plansFlow.value.single().alarmsSetAt).isEqualTo(now.toEpochMilli())
        assertThat(output.message.text).isEqualTo(R.string.day_alarms_set_some_not_attending)
    }

    @Test
    fun setAlarms_readsTheDayFromEveryCalendar_declinedIncluded_notFromTheDisplayWindow() = runTest {
        // "show declined" is off and the calendar is hidden in Settings: the window has nothing,
        // the provider still has the (now declined) meeting, and that is what the tap must see
        repository.calendars = listOf(calendar.copy(visible = false))
        repository.events[today] = listOf(standup.copy(selfStatus = SelfStatus.DECLINED), designReview)
        val state = TestAppState.copy(eventsByDay = mapOf(today to DayEvents(today, emptyList(), Instant.EPOCH)))
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup), selection(designReview)))

        val output = sideEffect(dayPlanDao).output(SetAlarms(today), state = state).toList()

        assertThat(repository.eventQueries.last()).isEqualTo(today to CalendarFilter.Only(setOf(1L)))
        assertThat(scheduler.armed.values.map { it.eventId }).containsExactly(2L)
        assertThat(output.message.text).isEqualTo(R.string.day_alarms_set_some_not_attending)
    }

    @Test
    fun setAlarms_fallsBackToTheLoadedWindow_whenTheProviderCannotBeRead() = runTest {
        repository.error = SecurityException("calendar access revoked")
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        run(dayPlanDao)

        assertThat(scheduler.armed.values.map { it.eventId }).containsExactly(1L)
    }

    @Test
    fun setAlarms_reArmingAnAlreadyAnsweredSelection_keepsItsAnswerAndSendsNoRsvp() = runTest {
        // answered on an earlier tap, then the event moved into the past (alarm cancelled,
        // selection skipped) and back again: the fresh event now reads ACCEPTED from our own
        // write, so a fresh decision would be NOT_APPLICABLE and would erase the "sent" tick
        val accepted = standup.copy(selfStatus = SelfStatus.ACCEPTED)
        val state = TestAppState.copy(eventsByDay = mapOf(today to DayEvents(today, listOf(accepted), Instant.EPOCH)))
        val dayPlanDao = FakeDayPlanDao(
            selections = listOf(selection(standup).copy(rsvpState = RsvpState.ACCEPTED_LOCALLY, rsvpEventId = 100)),
        )

        val output = run(dayPlanDao, state)

        assertThat(output.filterIsInstance<RsvpAccept>()).isEmpty()
        assertThat(scheduler.armed.keys.toList()).containsExactly(1L)
        assertThat(dayPlanDao.selectedEventsOn(today).single()).isEqualTo(
            selection(standup).copy(alarmId = 1, alarmAt = at(8, 55).toEpochMilli(), rsvpState = RsvpState.ACCEPTED_LOCALLY, rsvpEventId = 100),
        )
    }

    @Test
    fun setAlarms_neverRsvpsForAKeptRetimedOrSkippedSelection() = runTest {
        // standup already armed (kept), the review armed but moved (re-timed), the early bird past (skipped)
        val armedStandup = ScheduledAlarmEntity(
            alarmId = 1, date = today, eventId = standup.eventId, instanceTime = 0, fireAt = at(8, 55).toEpochMilli(),
            title = standup.title, beginMillis = standup.begin.toEpochMilli(), endMillis = standup.end.toEpochMilli(), soundIndex = 5,
        )
        val armedReview = armedStandup.copy(
            alarmId = 2, eventId = designReview.eventId, fireAt = at(9, 25).toEpochMilli(), title = designReview.title,
            beginMillis = at(9, 30).toEpochMilli(), endMillis = at(10, 30).toEpochMilli(),
        )
        alarmDao.rows[1] = armedStandup
        alarmDao.rows[2] = armedReview
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup), selection(designReview), selection(earlyBird)))

        val output = run(dayPlanDao)

        assertThat(output.filterIsInstance<RsvpAccept>()).isEmpty()
        assertThat(dayPlanDao.selectedEventsOn(today).map { it.rsvpState }).containsExactly(
            RsvpState.NOT_APPLICABLE, RsvpState.NOT_APPLICABLE, RsvpState.NOT_APPLICABLE,
        )
    }

    @Test
    fun setAlarms_skipsPastAlarmTimes_andSaysSo() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(earlyBird), selection(standup)))

        val output = run(dayPlanDao)

        assertThat(alarmDao.rows.values.map { it.eventId }).containsExactly(standup.eventId)
        assertThat(dayPlanDao.selectedEventsOn(today).first { it.eventId == earlyBird.eventId }.alarmId).isNull()
        val message = output.message
        assertThat(message.text).isEqualTo(R.string.day_alarms_set_some_skipped)
        assertThat(message.formatArgs).isEqualTo(listOf<Any>(1, 1))
    }

    @Test
    fun setAlarms_whenEverySelectionIsPast_reportsOnlyTheSkippedCount() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(earlyBird)))

        val output = run(dayPlanDao)

        val message = output.message
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

        run(dayPlanDao)

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

        run(dayPlanDao, state)

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

        run(dayPlanDao)

        assertThat(dayPlanDao.selectedEventsOn(today).single().alarmId).isEqualTo(1)
        assertThat(alarmDao.rows.getValue(1)).isEqualTo(armedStandup)
    }

    @Test
    fun setAlarms_usesTheLeadTimeSetting() = runTest {
        settings.setLeadTime(Duration.ofMinutes(15))
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        run(dayPlanDao)

        assertThat(alarmDao.rows.getValue(1).fireAt).isEqualTo(at(8, 45).toEpochMilli())
    }

    @Test
    fun setAlarms_onAnotherDay_namesTheDayInTheSnackbar() = runTest {
        val laterStandup = testCalendarEvent(9, Instant.parse("2026-09-15T09:00:00Z"), Instant.parse("2026-09-15T09:30:00Z"))
        val state = TestAppState.copy(eventsByDay = mapOf(tomorrow to DayEvents(tomorrow, listOf(laterStandup), Instant.EPOCH)))
        val dayPlanDao = FakeDayPlanDao(selections = listOf(laterStandup.toSelectedEventEntity(tomorrow)))

        val output = run(dayPlanDao, state, tomorrow)

        val message = output.message
        assertThat(message.text).isEqualTo(R.plurals.day_alarms_set_on_day)
        assertThat(message.formatArgs).isEqualTo(listOf<Any>(1, "Tuesday, Sep 15"))
        assertThat(alarmDao.rows.getValue(1).date).isEqualTo(tomorrow)
    }

    @Test
    fun withoutTheExactAlarmGrant_nothingIsWritten_andAPermissionRecheckIsRequested() = runTest {
        scheduler.canSchedule = false
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        val output = run(dayPlanDao)

        assertThat(alarmDao.rows).isEmpty()
        assertThat(dayPlanDao.plansFlow.value).isEmpty()
        assertThat(output.map { (it as? ShowMessage)?.message?.text ?: it }).containsExactly(
            R.string.alarms_exact_permission_missing,
            PermissionsMaybeChanged,
        )
    }

    @Test
    fun whenTheOsRefusesToArm_theRowIsCancelled_theDayStaysInSetAlarms_andThePermissionMessageShown() = runTest {
        scheduler.refuse = true
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        val output = run(dayPlanDao)

        assertThat(alarmDao.rows.getValue(1).state).isEqualTo(AlarmState.CANCELLED)
        assertThat(dayPlanDao.selectedEventsOn(today).single().alarmId).isNull()
        // not "alarms set": the FAB must keep reading "Set alarms (1)" so the tap can be retried
        assertThat(dayPlanDao.plansFlow.value.mapNotNull { it.alarmsSetAt }).isEmpty()
        // and no alarm means no RSVP either
        assertThat(dayPlanDao.selectedEventsOn(today).single().rsvpState).isEqualTo(RsvpState.NOT_APPLICABLE)
        assertThat(output.map { (it as? ShowMessage)?.message?.text ?: it }).containsExactly(
            R.string.alarms_exact_permission_missing,
            PermissionsMaybeChanged,
        )
    }

    @Test
    fun aRefusedArm_isRetriedByTheNextSetAlarms_withAFreshRow() = runTest {
        scheduler.refuse = true
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))
        run(dayPlanDao)

        scheduler.refuse = false
        val output = run(dayPlanDao)

        assertThat(alarmDao.rows.getValue(1).state).isEqualTo(AlarmState.CANCELLED)
        assertThat(alarmDao.rows.getValue(2).state).isEqualTo(AlarmState.SCHEDULED)
        assertThat(scheduler.armed.keys.toList()).containsExactly(2L)
        assertThat(dayPlanDao.selectedEventsOn(today).single().alarmId).isEqualTo(2)
        assertThat(dayPlanDao.plansFlow.value.single().alarmsSetAt).isEqualTo(now.toEpochMilli())
        assertThat(output.message.text).isEqualTo(R.plurals.day_alarms_set_today)
        // the retry is the first successful arm, so it is the one that RSVPs
        assertThat(output.filterIsInstance<RsvpAccept>()).containsExactly(RsvpAccept(today, standup.key))
    }

    @Test
    fun setAlarms_withEverySelectionRemoved_cancelsTheArmedRows_andLeavesTheDayUnset() = runTest {
        // "Set alarms" armed two events, then the user deselected both: the toggles cleared
        // alarms_set_at and deleted the selection rows, the scheduled_alarm rows stayed armed
        val armedStandup = ScheduledAlarmEntity(
            alarmId = 1, date = today, eventId = standup.eventId, instanceTime = 0, fireAt = at(8, 55).toEpochMilli(),
            title = standup.title, beginMillis = standup.begin.toEpochMilli(), endMillis = standup.end.toEpochMilli(), soundIndex = 5,
        )
        val armedReview = armedStandup.copy(
            alarmId = 2, eventId = designReview.eventId, fireAt = at(9, 55).toEpochMilli(), title = designReview.title,
            beginMillis = designReview.begin.toEpochMilli(), endMillis = designReview.end.toEpochMilli(),
        )
        alarmDao.rows[1] = armedStandup
        alarmDao.rows[2] = armedReview
        scheduler.schedule(armedStandup)
        scheduler.schedule(armedReview)
        val dayPlanDao = FakeDayPlanDao(plans = listOf(DayPlanEntity(today)), selections = emptyList())

        val output = run(dayPlanDao)

        assertThat(alarmDao.rows.values.map { it.state }).containsExactly(AlarmState.CANCELLED, AlarmState.CANCELLED)
        assertThat(scheduler.cancelled).containsExactly(1L, 2L)
        assertThat(scheduler.armed).isEmpty()
        // nothing selected and nothing armed is "nothing picked", not "alarms set": the FAB hides
        assertThat(dayPlanDao.plansFlow.value.single().alarmsSetAt).isNull()
        val message = output.message
        assertThat(message.text).isEqualTo(R.plurals.day_alarms_cleared)
        assertThat(message.quantity).isEqualTo(2)
        assertThat(message.formatArgs).isEqualTo(listOf<Any>(2))
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
        assertThat(alarmsSetMessage(AlarmReconciliation(cancel = listOf(row)), today, today).text).isEqualTo(R.plurals.day_alarms_cleared)
        assertThat(alarmsSetMessage(AlarmReconciliation(notAttending = listOf(skipped)), today, today).text)
            .isEqualTo(R.plurals.day_alarms_not_attending)
        assertThat(alarmsSetMessage(AlarmReconciliation(schedule = listOf(row), notAttending = listOf(skipped)), today, today).text)
            .isEqualTo(R.string.day_alarms_set_some_not_attending)
        assertThat(alarmsSetMessage(AlarmReconciliation(skipped = listOf(skipped), notAttending = listOf(skipped)), today, today).text)
            .isEqualTo(R.plurals.day_alarms_skipped_mixed)
        assertThat(alarmsSetMessage(AlarmReconciliation(schedule = listOf(row), skipped = listOf(skipped), notAttending = listOf(skipped)), today, today).text)
            .isEqualTo(R.string.day_alarms_set_some_skipped_mixed)
    }
}

package com.episode6.meetingminder.alarm

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.episode6.meetingminder.data.calendar.CalendarFilter
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.BusyBlockEntity
import com.episode6.meetingminder.data.db.ChangeSnapshotEntity
import com.episode6.meetingminder.data.db.DayPlanEntity
import com.episode6.meetingminder.data.db.FakeBusyBlockDao
import com.episode6.meetingminder.data.db.FakeChangeSnapshotDao
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.db.SelectedEventEntity
import com.episode6.meetingminder.data.db.encodeChangeSnapshotEvents
import com.episode6.meetingminder.data.settings.BusySync
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.Settings
import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.SelfStatus
import com.episode6.meetingminder.model.TEST_ALARM_EVENT_ID
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.monitor.FakeCalendarPermissionChecker
import com.episode6.meetingminder.share.BusyCalendarSyncer
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** It is 09:00 UTC on [today]; lead time 5 minutes. */
class AlarmMaintainerTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val now = Instant.parse("2026-09-14T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val lead = Duration.ofMinutes(5)

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = today): Instant = date.atTime(hour, minute).toInstant(ZoneOffset.UTC)

    private fun event(id: Long, begin: Instant, end: Instant = begin.plus(Duration.ofMinutes(30))): CalendarEvent =
        testCalendarEvent(id, begin, end, title = "Meeting $id")

    private fun rowFor(event: CalendarEvent, alarmId: Long = event.eventId, state: AlarmState = AlarmState.SCHEDULED) = ScheduledAlarmEntity(
        alarmId = alarmId,
        date = today,
        eventId = event.key.eventId,
        instanceTime = event.key.instanceTime,
        fireAt = alarmTimeFor(event.begin.toEpochMilli(), lead),
        title = event.title,
        beginMillis = event.begin.toEpochMilli(),
        endMillis = event.end.toEpochMilli(),
        soundIndex = 3,
        state = state,
    )

    private fun selectionFor(event: CalendarEvent) =
        SelectedEventEntity(today, event.key.eventId, event.key.instanceTime, event.title, event.begin.toEpochMilli(), event.end.toEpochMilli())

    private fun armedSelection(event: CalendarEvent) = selectionFor(event).copy(alarmId = event.eventId, alarmAt = rowFor(event).fireAt)

    /** A syncer with the busy-calendar sync off: every trim is skipped. */
    private fun noBusySync() = BusyCalendarSyncer(FakeCalendarRepository(), FakeBusyBlockDao(), FakeDayPlanDao(), FakeSettingsRepository(), clock)

    // --- the pure rule ---

    @Test
    fun aDeclinedOrCancelledSelection_isDropped_armedOrNot_butNeverAVanishedOrEndedOne() {
        val declined = event(1, at(10))
        val cancelled = event(2, at(11))
        val vanished = event(3, at(12))
        val over = event(4, at(7), at(8))
        val going = event(5, at(13))

        val plan = maintainAlarms(
            armed = emptyList(),
            fresh = mapOf(
                declined.key to declined.copy(selfStatus = SelfStatus.DECLINED),
                cancelled.key to cancelled.copy(status = EventStatus.CANCELED),
                over.key to over.copy(selfStatus = SelfStatus.DECLINED),
                going.key to going,
            ),
            leadTime = lead,
            now = now,
            selections = listOf(declined, cancelled, vanished, over, going).map { selectionFor(it) },
        )

        assertThat(plan.drop).containsExactly(selectionFor(declined), selectionFor(cancelled))
        assertThat(plan.isEmpty).isFalse()
    }

    @Test
    fun anUnchangedEvent_needsNothing() {
        val standup = event(1, at(10))

        val plan = maintainAlarms(listOf(rowFor(standup)), mapOf(standup.key to standup), lead, now)

        assertThat(plan.isEmpty).isTrue()
    }

    @Test
    fun aMovedEvent_isRetimedToItsNewStart_asAFreshAlarm() {
        val standup = event(1, at(10))
        val row = rowFor(standup, state = AlarmState.SCHEDULED).copy(timedOut = true)
        val moved = standup.copy(begin = at(11), end = at(11, 30))

        val plan = maintainAlarms(listOf(row), mapOf(standup.key to moved), lead, now)

        assertThat(plan.retime).containsExactly(
            row.copy(fireAt = at(10, 55).toEpochMilli(), beginMillis = at(11).toEpochMilli(), endMillis = at(11, 30).toEpochMilli(), timedOut = false),
        )
        assertThat(plan.cancel).isEmpty()
    }

    @Test
    fun aMeetingPulledForwardIntoTheLeadTime_isStillRetimed_soItRingsAtOnce() {
        val standup = event(1, at(10))
        val moved = standup.copy(begin = at(9, 2), end = at(9, 30))

        val plan = maintainAlarms(listOf(rowFor(standup)), mapOf(standup.key to moved), lead, now)

        assertThat(plan.retime.single().fireAt).isEqualTo(at(8, 57).toEpochMilli())
    }

    @Test
    fun aSnoozedAlarmWhoseNewAlarmTimeHasPassed_keepsItsSnooze_withItsCopyRefreshed() {
        val standup = event(1, at(9, 3))
        val snoozed = rowFor(standup, state = AlarmState.SNOOZED).copy(fireAt = at(9, 1).toEpochMilli())
        val moved = standup.copy(begin = at(9, 4), end = at(9, 30))

        val plan = maintainAlarms(listOf(snoozed), mapOf(standup.key to moved), lead, now)

        assertThat(plan.retime).isEmpty()
        assertThat(plan.refresh).containsExactly(snoozed.copy(beginMillis = at(9, 4).toEpochMilli(), endMillis = at(9, 30).toEpochMilli()))
    }

    @Test
    fun aSnoozedAlarmWhoseMeetingMovedLater_becomesAFreshScheduledAlarm() {
        val standup = event(1, at(9, 3))
        val snoozed = rowFor(standup, state = AlarmState.SNOOZED).copy(fireAt = at(9, 1).toEpochMilli())
        val moved = standup.copy(begin = at(12), end = at(12, 30))

        val plan = maintainAlarms(listOf(snoozed), mapOf(standup.key to moved), lead, now)

        assertThat(plan.retime.single().state).isEqualTo(AlarmState.SCHEDULED)
        assertThat(plan.retime.single().fireAt).isEqualTo(at(11, 55).toEpochMilli())
    }

    @Test
    fun declinedByMe_orCancelled_cancelsTheAlarm() {
        val declined = event(1, at(10))
        val cancelled = event(2, at(11))

        val plan = maintainAlarms(
            listOf(rowFor(declined), rowFor(cancelled)),
            mapOf(
                declined.key to declined.copy(selfStatus = SelfStatus.DECLINED),
                cancelled.key to cancelled.copy(status = EventStatus.CANCELED),
            ),
            lead,
            now,
        )

        assertThat(plan.cancel).containsExactly(rowFor(declined), rowFor(cancelled))
    }

    @Test
    fun anEventThatVanished_keepsItsAlarm() {
        val standup = event(1, at(10))

        val plan = maintainAlarms(listOf(rowFor(standup)), emptyMap(), lead, now)

        assertThat(plan.isEmpty).isTrue()
    }

    @Test
    fun aRetitledOrRelocatedEvent_onlyRefreshesTheCopy() {
        val standup = event(1, at(10))

        val plan = maintainAlarms(listOf(rowFor(standup)), mapOf(standup.key to standup.copy(title = "Standup!", location = "Room 2")), lead, now)

        assertThat(plan.refresh).containsExactly(rowFor(standup).copy(title = "Standup!", location = "Room 2"))
        assertThat(plan.retime).isEmpty()
    }

    @Test
    fun theTestAlarm_andRowsNoLongerArmed_areNeverTouched() {
        val test = event(TEST_ALARM_EVENT_ID, at(9, 1))
        val fired = event(2, at(10))

        val plan = maintainAlarms(
            listOf(rowFor(test, alarmId = 9), rowFor(fired, state = AlarmState.FIRED)),
            mapOf(test.key to test.copy(begin = at(12)), fired.key to fired.copy(begin = at(12))),
            lead,
            now,
        )

        assertThat(plan.isEmpty).isTrue()
    }

    // --- applied through the DAOs and the scheduler ---

    private val calendar = CalendarInfo(
        id = 1, accountName = "me", accountType = "LOCAL", displayName = "Work", color = 0, visible = false,
        syncEvents = true, ownerAccount = "me", isPrimary = true, accessLevel = 700, canOrganizerRespond = false,
    )

    @Test
    fun maintain_retimesAMovedMeeting_inRoomAndAlarmManager_readingHiddenCalendarsToo() = runTest {
        val standup = event(1, at(10))
        val row = rowFor(standup)
        val repository = FakeCalendarRepository(calendars = listOf(calendar))
        repository.events[today] = listOf(standup.copy(begin = at(11), end = at(11, 30)))
        val alarms = FakeScheduledAlarmDao(listOf(row))
        val scheduler = FakeAlarmScheduler()
        val selection = SelectedEventEntity(today, 1, 0, standup.title, row.beginMillis, row.endMillis, alarmId = 1, alarmAt = row.fireAt)
        val plans = FakeDayPlanDao(listOf(DayPlanEntity(today, alarmsSetAt = 1)), listOf(selection))

        AlarmMaintainer(repository, alarms, plans, scheduler, FakeSettingsRepository(), FakeCalendarPermissionChecker(), clock, FakeChangeSnapshotDao(), noBusySync()).maintain()

        val retimed = alarms.rows.getValue(1)
        assertThat(retimed.fireAt).isEqualTo(at(10, 55).toEpochMilli())
        assertThat(scheduler.armed.getValue(1)).isEqualTo(retimed)
        assertThat(plans.selectionsFlow.value.single().alarmAt).isEqualTo(at(10, 55).toEpochMilli())
        assertThat(plans.selectionsFlow.value.single().beginMillis).isEqualTo(at(11).toEpochMilli())
        // the day either side too (a zone change can move an occurrence across midnight), from every calendar
        assertThat(repository.eventQueries).containsExactly(
            today.minusDays(1) to CalendarFilter.Only(setOf(1L)),
            today to CalendarFilter.Only(setOf(1L)),
            today.plusDays(1) to CalendarFilter.Only(setOf(1L)),
        )
    }

    @Test
    fun maintain_aDeclinedMeeting_losesItsSelectionWithItsAlarm_andTheRestOfTheDayStaysAlarmsSet() = runTest {
        val standup = event(1, at(10))
        val review = event(2, at(11))
        val repository = FakeCalendarRepository(calendars = listOf(calendar))
        repository.events[today] = listOf(standup, review.copy(selfStatus = SelfStatus.DECLINED))
        val alarms = FakeScheduledAlarmDao(listOf(rowFor(standup), rowFor(review)))
        val scheduler = FakeAlarmScheduler().apply { schedule(rowFor(standup)); schedule(rowFor(review)) }
        val plans = FakeDayPlanDao(listOf(DayPlanEntity(today, alarmsSetAt = 1)), listOf(armedSelection(standup), armedSelection(review)))

        AlarmMaintainer(repository, alarms, plans, scheduler, FakeSettingsRepository(), FakeCalendarPermissionChecker(), clock, FakeChangeSnapshotDao(), noBusySync()).maintain()

        assertThat(alarms.rows.getValue(2).state).isEqualTo(AlarmState.CANCELLED)
        assertThat(scheduler.cancelled).containsExactly(2L)
        assertThat(plans.selectionsFlow.value).containsExactly(armedSelection(standup))
        // what is left still matches its armed set, so the day stays "alarms set"
        assertThat(plans.plansFlow.value.single().alarmsSetAt).isEqualTo(1L)
    }

    @Test
    fun maintain_aDeclinedOnlyPick_isDropped_andTheDayGoesBackToNothingPicked() = runTest {
        val standup = event(1, at(10))
        val row = rowFor(standup)
        val repository = FakeCalendarRepository(calendars = listOf(calendar))
        repository.events[today] = listOf(standup.copy(selfStatus = SelfStatus.DECLINED))
        val alarms = FakeScheduledAlarmDao(listOf(row))
        val plans = FakeDayPlanDao(listOf(DayPlanEntity(today, alarmsSetAt = 1)), listOf(armedSelection(standup)))

        AlarmMaintainer(repository, alarms, plans, FakeAlarmScheduler(), FakeSettingsRepository(), FakeCalendarPermissionChecker(), clock, FakeChangeSnapshotDao(), noBusySync()).maintain()

        assertThat(alarms.rows.getValue(1).state).isEqualTo(AlarmState.CANCELLED)
        assertThat(plans.selectionsFlow.value).isEmpty()
        assertThat(plans.plansFlow.value.single().alarmsSetAt).isNull()
    }

    @Test
    fun maintain_aPickDeclinedBeforeItsAlarmsWereSet_isDroppedToo() = runTest {
        val standup = event(1, at(10))
        val repository = FakeCalendarRepository(calendars = listOf(calendar))
        repository.events[today] = listOf(standup.copy(selfStatus = SelfStatus.DECLINED))
        val plans = FakeDayPlanDao(listOf(DayPlanEntity(today)), listOf(selectionFor(standup)))

        val plan = AlarmMaintainer(
            repository, FakeScheduledAlarmDao(), plans, FakeAlarmScheduler(), FakeSettingsRepository(),
            FakeCalendarPermissionChecker(), clock, FakeChangeSnapshotDao(), noBusySync(),
        ).maintain()

        assertThat(plan.drop).containsExactly(selectionFor(standup))
        assertThat(plans.selectionsFlow.value).isEmpty()
        // no armed row on the day, so no neighbours: the decline check only needs the pick's own day
        assertThat(repository.eventQueries.map { it.first }).containsExactly(today)
    }

    // --- the busy calendar of a shared day ---

    private val family = CalendarInfo(
        id = 7, accountName = "me", accountType = "LOCAL", displayName = "Family", color = 0, visible = true,
        syncEvents = true, ownerAccount = "me", isPrimary = false, accessLevel = 700, canOrganizerRespond = false,
    )
    private val busySettings = FakeSettingsRepository(Settings(busySync = BusySync(enabled = true, calendarId = family.id)))

    private fun block(eventId: Long, begin: Instant, end: Instant) =
        BusyBlockEntity(eventId = eventId, date = today, calendarId = family.id, beginMillis = begin.toEpochMilli(), endMillis = end.toEpochMilli())

    /** Standup 10–10:30, review 10:15–11 and lunch 12–1, all shared: the calendar holds 10–11 and 12–1. */
    private inner class SharedDay(reviewSelectedAtShare: Boolean = true, reviewInBaseline: Boolean = true) {
        val standup = event(1, at(10), at(10, 30))
        val review = event(2, at(10, 15), at(11))
        val lunch = event(3, at(12), at(13))
        val repository = FakeCalendarRepository(calendars = listOf(calendar, family)).apply {
            events[today] = listOf(standup, review.copy(selfStatus = SelfStatus.DECLINED), lunch)
            ownEvents += setOf(901L, 902L)
            nextBusyBlockId = 100
        }
        val plans = FakeDayPlanDao(
            listOf(DayPlanEntity(today, alarmsSetAt = 1, sharedAt = 1)),
            listOf(selectionFor(standup), selectionFor(review), selectionFor(lunch)),
        )
        val snapshots = FakeChangeSnapshotDao(
            listOf(
                ChangeSnapshotEntity(
                    date = today,
                    takenAt = 1,
                    eventsJson = encodeChangeSnapshotEvents(
                        listOfNotNull(standup, review.takeIf { reviewInBaseline }, lunch),
                        setOfNotNull(standup.key, review.key.takeIf { reviewSelectedAtShare }, lunch.key),
                    ),
                ),
            ),
        )
        val blocks = FakeBusyBlockDao(listOf(block(901, at(10), at(11)), block(902, at(12), at(13))))

        suspend fun maintain() = AlarmMaintainer(
            repository, FakeScheduledAlarmDao(), plans, FakeAlarmScheduler(), busySettings, FakeCalendarPermissionChecker(), clock,
            snapshots, BusyCalendarSyncer(repository, blocks, plans, busySettings, clock),
        ).maintain()
    }

    @Test
    fun maintain_takesADeclinedMeetingsTimeOutOfTheBusyCalendar_keepingWhatTheOtherPicksStillCover() = runTest {
        val day = SharedDay()

        day.maintain()

        assertThat(day.repository.deletedEventIds).containsExactly(901L)
        assertThat(day.repository.busyBlockInserts.map { it.range }).containsExactly(BusyRange(at(10), at(10, 30)))
        assertThat(day.blocks.entries).containsExactly(block(100, at(10), at(10, 30)), block(902, at(12), at(13)))
        assertThat(day.plans.selectionsFlow.value).containsExactly(selectionFor(day.standup), selectionFor(day.lunch))
    }

    @Test
    fun maintain_aDeclinedMeetingThatAppearedAfterTheShare_leavesTheBusyCalendarAlone() = runTest {
        // not in the baseline at all: added to the calendar after the share, then picked
        val day = SharedDay(reviewInBaseline = false)

        day.maintain()

        assertThat(day.repository.deletedEventIds).isEmpty()
        assertThat(day.repository.busyBlockInserts).isEmpty()
        assertThat(day.plans.selectionsFlow.value).containsExactly(selectionFor(day.standup), selectionFor(day.lunch))
    }

    @Test
    fun maintain_aDeclinedPickTheShareNeverIncluded_leavesTheBusyCalendarAlone() = runTest {
        val day = SharedDay(reviewSelectedAtShare = false)

        day.maintain()

        assertThat(day.repository.deletedEventIds).isEmpty()
        assertThat(day.repository.busyBlockInserts).isEmpty()
        assertThat(day.plans.selectionsFlow.value).containsExactly(selectionFor(day.standup), selectionFor(day.lunch))
    }

    @Test
    fun maintain_aBusyTrimThatFails_keepsTheDeclinedSelection_forTheNextPassToRetry() = runTest {
        val day = SharedDay()
        day.repository.busyBlockInsertError = { IllegalStateException("provider said no") }

        day.maintain()

        // the leftover piece goes in before the old block comes out, so nothing was taken off the calendar
        assertThat(day.repository.deletedEventIds).isEmpty()
        assertThat(day.plans.selectionsFlow.value).containsExactly(selectionFor(day.standup), selectionFor(day.review), selectionFor(day.lunch))

        day.repository.busyBlockInsertError = { null }
        day.maintain()

        assertThat(day.blocks.entries).containsExactly(block(100, at(10), at(10, 30)), block(902, at(12), at(13)))
        assertThat(day.plans.selectionsFlow.value).containsExactly(selectionFor(day.standup), selectionFor(day.lunch))
    }

    @Test
    fun maintain_aRetimeTheOsRefuses_cancelsTheRow_andPutsTheDayBackToSetAlarms() = runTest {
        val standup = event(1, at(10))
        val repository = FakeCalendarRepository(calendars = listOf(calendar))
        repository.events[today] = listOf(standup.copy(begin = at(11), end = at(11, 30)))
        val alarms = FakeScheduledAlarmDao(listOf(rowFor(standup)))
        val plans = FakeDayPlanDao(listOf(DayPlanEntity(today, alarmsSetAt = 1)))

        AlarmMaintainer(repository, alarms, plans, FakeAlarmScheduler(refuse = true), FakeSettingsRepository(), FakeCalendarPermissionChecker(), clock, FakeChangeSnapshotDao(), noBusySync())
            .maintain()

        assertThat(alarms.rows.getValue(1).state).isEqualTo(AlarmState.CANCELLED)
        assertThat(plans.plansFlow.value.single().alarmsSetAt).isNull()
    }

    @Test
    fun maintain_withoutCalendarAccess_readsAndChangesNothing() = runTest {
        val row = rowFor(event(1, at(10)))
        val repository = FakeCalendarRepository(calendars = listOf(calendar))
        val alarms = FakeScheduledAlarmDao(listOf(row))

        AlarmMaintainer(
            repository, alarms, FakeDayPlanDao(), FakeAlarmScheduler(), FakeSettingsRepository(),
            FakeCalendarPermissionChecker(calendarGranted = false), clock, FakeChangeSnapshotDao(), noBusySync(),
        ).maintain()

        assertThat(repository.eventQueries).isEmpty()
        assertThat(alarms.rows.getValue(1)).isEqualTo(row)
    }

    @Test
    fun maintain_rowsWhoseMeetingHasEnded_areNotEvenRead() = runTest {
        val over = event(1, at(7), at(8))
        val repository = FakeCalendarRepository(calendars = listOf(calendar))

        AlarmMaintainer(
            repository, FakeScheduledAlarmDao(listOf(rowFor(over))), FakeDayPlanDao(), FakeAlarmScheduler(), FakeSettingsRepository(),
            FakeCalendarPermissionChecker(), clock, FakeChangeSnapshotDao(), noBusySync(),
        ).maintain()

        assertThat(repository.eventQueries).isEmpty()
    }

    @Test
    fun maintain_lostCalendarAccessMidway_leavesAlarmsAlone() = runTest {
        val row = rowFor(event(1, at(10)))
        val repository = FakeCalendarRepository(calendars = listOf(calendar)).apply { error = SecurityException("revoked") }
        val alarms = FakeScheduledAlarmDao(listOf(row))

        val plan = AlarmMaintainer(repository, alarms, FakeDayPlanDao(), FakeAlarmScheduler(), FakeSettingsRepository(), FakeCalendarPermissionChecker(), clock, FakeChangeSnapshotDao(), noBusySync())
            .maintain()

        assertThat(plan.isEmpty).isTrue()
        assertThat(alarms.rows.getValue(1)).isEqualTo(row)
    }
}

package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.data.db.ChangeSnapshotEntity
import com.episode6.meetingminder.data.db.DayPlanEntity
import com.episode6.meetingminder.data.db.FakeChangeSnapshotDao
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.SelectedEventEntity
import com.episode6.meetingminder.data.db.decodeBusyRanges
import com.episode6.meetingminder.data.db.decodeChangeSnapshotEvents
import com.episode6.meetingminder.data.db.encodeBusyRanges
import com.episode6.meetingminder.data.db.encodeScheduleChanges
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.monitor.ChangeCheckReason
import com.episode6.meetingminder.monitor.ChangeMonitor
import com.episode6.meetingminder.monitor.FakeCalendarPermissionChecker
import com.episode6.meetingminder.monitor.FakeChangeWorkScheduler
import com.episode6.meetingminder.monitor.FakeScheduleChangeNotifier
import com.episode6.meetingminder.store.MarkNotShared
import com.episode6.meetingminder.store.SetPendingShare
import com.episode6.meetingminder.store.ShareDay
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ShareDaySideEffectsTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val now = Instant.parse("2026-09-14T08:35:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun at(hour: Int, minute: Int = 0): Instant = Instant.parse("2026-09-14T%02d:%02d:00Z".format(hour, minute))

    private val standup = testCalendarEvent(1, at(9), at(9, 30), title = "Standup")
    private val dentist = testCalendarEvent(2, at(7), at(8), title = "Dentist", meeting = false)

    private val repository = FakeCalendarRepository()
    private val notifier = FakeScheduleChangeNotifier()
    private val scheduler = FakeChangeWorkScheduler()

    private fun selection(event: CalendarEvent, date: LocalDate = today) =
        SelectedEventEntity(date, event.key.eventId, event.key.instanceTime, event.title, event.begin.toEpochMilli(), event.end.toEpochMilli())

    private fun loaded(vararg events: CalendarEvent) = CalendarGrantedAppState.copy(eventsByDay = mapOf(today to DayEvents(today, events.toList(), Instant.EPOCH)))

    private fun shareDay(dayPlanDao: FakeDayPlanDao, changeSnapshotDao: FakeChangeSnapshotDao) = object : ShareDaySideEffects {}.shareDay(
        dayPlanDao,
        changeSnapshotDao,
        repository,
        ChangeMonitor(repository, changeSnapshotDao, dayPlanDao, FakeCalendarPermissionChecker(), notifier, scheduler, FakeSettingsRepository(), clock),
        clock,
    )

    private suspend fun FakeDayPlanDao.shareText(snapshots: FakeChangeSnapshotDao = FakeChangeSnapshotDao(), state: com.episode6.meetingminder.store.AppState) =
        (shareDay(this, snapshots).output(ShareDay(today), state = state).toList().single() as SetPendingShare).share.text

    @Test
    fun shareDay_formatsOnlySelectedEvents_andEmitsThePendingShare() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        val output = shareDay(dayPlanDao, FakeChangeSnapshotDao()).output(ShareDay(today), state = loaded(standup, dentist)).toList()

        val share = (output.single() as SetPendingShare).share
        assertThat(share.date).isEqualTo(today)
        assertThat(share.text).isEqualTo("Mon Sep 14 — I'm in meetings:\n• 9:00 – 9:30 AM\nFree the rest of the day.")
    }

    @Test
    fun shareDay_recordsSharedAtAndTheMergedBusyRanges() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        shareDay(dayPlanDao, FakeChangeSnapshotDao()).output(ShareDay(today), state = loaded(standup)).toList()

        val plan = dayPlanDao.plansFlow.value.single()
        assertThat(plan.sharedAt).isEqualTo(now.toEpochMilli())
        assertThat(decodeBusyRanges(plan.sharedSnapshot!!)).isEqualTo(listOf(BusyRange(standup.begin, standup.end)))
    }

    @Test
    fun shareDay_usesTheFreshlyLoadedTimes_whenASelectedEventHasMovedSinceItWasSelected() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))
        val moved = standup.copy(begin = at(10), end = at(10, 30))

        val text = dayPlanDao.shareText(state = loaded(moved))

        assertThat(text).isEqualTo("Mon Sep 14 — I'm in meetings:\n• 10:00 – 10:30 AM\nFree the rest of the day.")
        assertThat(decodeBusyRanges(dayPlanDao.plansFlow.value.single().sharedSnapshot!!)).isEqualTo(listOf(BusyRange(moved.begin, moved.end)))
    }

    @Test
    fun shareDay_recordsAChangeSnapshotOfEveryEventOnTheDay_selectedOrNot() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))
        val changeSnapshotDao = FakeChangeSnapshotDao()

        shareDay(dayPlanDao, changeSnapshotDao).output(ShareDay(today), state = loaded(standup, dentist)).toList()

        val snapshot = changeSnapshotDao.forDate(today)!!
        assertThat(snapshot.takenAt).isEqualTo(now.toEpochMilli())
        assertThat(snapshot.changesJson).isEqualTo("[]")
        val events = decodeChangeSnapshotEvents(snapshot.eventsJson)
        assertThat(events).hasSize(2)
        assertThat(events.single { it.eventId == 1L }.selected).isTrue()
        assertThat(events.single { it.eventId == 2L }.selected).isEqualTo(false)
    }

    @Test
    fun shareDay_withNothingSelected_stillSharesAndRecordsAnEmptySnapshot() = runTest {
        val dayPlanDao = FakeDayPlanDao()

        val text = dayPlanDao.shareText(state = loaded())

        assertThat(text).isEqualTo("Mon Sep 14 — I'm in meetings:\nNo meetings today.")
        assertThat(decodeBusyRanges(dayPlanDao.plansFlow.value.single().sharedSnapshot!!)).isEqualTo(emptyList<BusyRange>())
    }

    @Test
    fun shareDay_fromANotificationBeforeTheStoreHasLoaded_readsTheSelectionFromRoomAndTheDayFromTheProvider() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))
        val changeSnapshotDao = FakeChangeSnapshotDao()
        repository.events[today] = listOf(standup, dentist)

        val text = dayPlanDao.shareText(changeSnapshotDao, state = CalendarGrantedAppState)

        assertThat(text).isEqualTo("Mon Sep 14 — I'm in meetings:\n• 9:00 – 9:30 AM\nFree the rest of the day.")
        assertThat(repository.eventQueries.map { it.first }).containsExactly(today)
        assertThat(decodeChangeSnapshotEvents(changeSnapshotDao.forDate(today)!!.eventsJson)).hasSize(2)
    }

    @Test
    fun shareDay_whenTheDayCantBeRead_sharesTheStoredTimes_butKeepsNoBaseline() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))
        val changeSnapshotDao = FakeChangeSnapshotDao(listOf(ChangeSnapshotEntity(today, 1_000, "[]")))
        repository.error = SecurityException("revoked")

        val text = dayPlanDao.shareText(changeSnapshotDao, state = CalendarGrantedAppState)

        assertThat(text).isEqualTo("Mon Sep 14 — I'm in meetings:\n• 9:00 – 9:30 AM\nFree the rest of the day.")
        assertThat(changeSnapshotDao.forDate(today)).isNull()
    }

    @Test
    fun reShare_afterTheSelectionMoved_sendsAnUpdate() = runTest {
        val dayPlanDao = FakeDayPlanDao(
            plans = listOf(DayPlanEntity(today, sharedAt = 1_000, sharedSnapshot = encodeBusyRanges(listOf(BusyRange(standup.begin, standup.end))))),
            selections = listOf(selection(standup)),
        )

        val text = dayPlanDao.shareText(state = loaded(standup.copy(begin = at(10), end = at(10, 30))))

        assertThat(text).isEqualTo("Update:\n• 10:00 – 10:30 AM")
    }

    @Test
    fun reShare_withRecordedChanges_sendsAnUpdate_evenIfTheBusyRangesMatch() = runTest {
        val dayPlanDao = FakeDayPlanDao(
            plans = listOf(DayPlanEntity(today, sharedAt = 1_000, sharedSnapshot = encodeBusyRanges(listOf(BusyRange(standup.begin, standup.end))))),
            selections = listOf(selection(standup)),
        )
        val recorded = encodeScheduleChanges(listOf(ScheduleChange.New(today, EventKey(5, 0), at(15), at(16))))
        val changeSnapshotDao = FakeChangeSnapshotDao(listOf(ChangeSnapshotEntity(today, 1_000, "[]", changesJson = recorded)))

        val text = dayPlanDao.shareText(changeSnapshotDao, state = loaded(standup))

        assertThat(text).isEqualTo("Update:\n• 9:00 – 9:30 AM")
        assertThat(changeSnapshotDao.forDate(today)!!.changesJson).isEqualTo("[]")
    }

    @Test
    fun reShare_withNothingChanged_sendsTheNormalText() = runTest {
        val dayPlanDao = FakeDayPlanDao(
            plans = listOf(DayPlanEntity(today, sharedAt = 1_000, sharedSnapshot = encodeBusyRanges(listOf(BusyRange(standup.begin, standup.end))))),
            selections = listOf(selection(standup)),
        )

        val text = dayPlanDao.shareText(FakeChangeSnapshotDao(listOf(ChangeSnapshotEntity(today, 1_000, "[]"))), state = loaded(standup))

        assertThat(text).isEqualTo("Mon Sep 14 — I'm in meetings:\n• 9:00 – 9:30 AM\nFree the rest of the day.")
    }

    @Test
    fun shareDay_cancelsTheDaysNotification_andStartsMonitoring() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        dayPlanDao.shareText(state = loaded(standup))

        assertThat(notifier.cancelled).containsExactly(today)
        assertThat(scheduler.updates).containsExactly(setOf(today) to ChangeCheckReason.IN_APP)
    }

    @Test
    fun markNotShared_clearsSharedAtAndTheChangeSnapshot_andStopsMonitoring() = runTest {
        val dayPlanDao = FakeDayPlanDao(plans = listOf(DayPlanEntity(today, sharedAt = now.toEpochMilli())))
        val changeSnapshotDao = FakeChangeSnapshotDao(entities = listOf(ChangeSnapshotEntity(today, now.toEpochMilli(), "[]")))
        val monitor = ChangeMonitor(repository, changeSnapshotDao, dayPlanDao, FakeCalendarPermissionChecker(), notifier, scheduler, FakeSettingsRepository(), clock)
        val effect = object : ShareDaySideEffects {}.markNotShared(dayPlanDao, changeSnapshotDao, monitor)

        effect.output(MarkNotShared(today), state = CalendarGrantedAppState).toList()

        assertThat(dayPlanDao.plansFlow.value.single().sharedAt).isNull()
        assertThat(changeSnapshotDao.forDate(today)).isNull()
        assertThat(notifier.cancelled).containsExactly(today)
        assertThat(scheduler.updates).containsExactly(emptySet<LocalDate>() to ChangeCheckReason.IN_APP)
    }
}

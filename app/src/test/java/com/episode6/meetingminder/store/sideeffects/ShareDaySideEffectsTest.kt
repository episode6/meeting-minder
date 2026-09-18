package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.monitor.MainUiVisibility
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.episode6.meetingminder.data.calendar.CalendarFilter
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.data.db.BusyBlockEntity
import com.episode6.meetingminder.data.db.ChangeSnapshotEntity
import com.episode6.meetingminder.data.db.DayPlanEntity
import com.episode6.meetingminder.data.db.FakeBusyBlockDao
import com.episode6.meetingminder.data.db.FakeChangeSnapshotDao
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.SelectedEventEntity
import com.episode6.meetingminder.data.db.decodeBusyRanges
import com.episode6.meetingminder.data.db.decodeChangeSnapshotEvents
import com.episode6.meetingminder.data.db.encodeBusyRanges
import com.episode6.meetingminder.data.db.encodeScheduleChanges
import com.episode6.meetingminder.data.settings.BusySync
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.Settings
import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.monitor.ChangeCheckReason
import com.episode6.meetingminder.monitor.ChangeMonitor
import com.episode6.meetingminder.monitor.FakeCalendarPermissionChecker
import com.episode6.meetingminder.monitor.FakeChangeWorkScheduler
import com.episode6.meetingminder.monitor.FakeScheduleChangeAlerter
import com.episode6.meetingminder.monitor.FakeScheduleChangeNotifier
import com.episode6.meetingminder.share.BusyCalendarSyncer
import com.episode6.meetingminder.store.MarkNotShared
import com.episode6.meetingminder.store.SetPendingShare
import com.episode6.meetingminder.store.ShareDay
import com.episode6.meetingminder.store.ShareFinished
import com.episode6.meetingminder.store.SyncBusyCalendar
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
    private val busyBlocks = FakeBusyBlockDao()

    private fun selection(event: CalendarEvent, date: LocalDate = today) =
        SelectedEventEntity(date, event.key.eventId, event.key.instanceTime, event.title, event.begin.toEpochMilli(), event.end.toEpochMilli())

    private fun syncer(dayPlanDao: FakeDayPlanDao, settings: FakeSettingsRepository) = BusyCalendarSyncer(repository, busyBlocks, dayPlanDao, settings, clock)

    private fun busySyncOn(calendarId: Long = 1, sendText: Boolean = true) =
        FakeSettingsRepository(Settings(busySync = BusySync(enabled = true, calendarId = calendarId, sendText = sendText)))

    private val familyCalendar = CalendarInfo(
        id = 5, accountName = "me@gmail.com", accountType = "com.google", displayName = "Family",
        color = 0, visible = true, syncEvents = true, ownerAccount = "me@gmail.com",
        isPrimary = false, accessLevel = 700, canOrganizerRespond = false,
    )

    private fun loaded(vararg events: CalendarEvent) = CalendarGrantedAppState.copy(eventsByDay = mapOf(today to DayEvents(today, events.toList(), Instant.EPOCH)))

    private fun shareDay(
        dayPlanDao: FakeDayPlanDao,
        changeSnapshotDao: FakeChangeSnapshotDao,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ) = object : ShareDaySideEffects {}.shareDay(
        dayPlanDao,
        changeSnapshotDao,
        repository,
        ChangeMonitor(repository, changeSnapshotDao, dayPlanDao, busyBlocks, FakeCalendarPermissionChecker(), notifier, scheduler, settings, clock, FakeScheduleChangeAlerter(), MainUiVisibility()),
        busyBlocks,
        settings,
        clock,
    )

    private suspend fun FakeDayPlanDao.shareText(
        snapshots: FakeChangeSnapshotDao = FakeChangeSnapshotDao(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        state: com.episode6.meetingminder.store.AppState,
    ) = (shareDay(this, snapshots, settings).output(ShareDay(today), state = state).toList().single() as SetPendingShare).share.text

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
    fun shareDay_fromANotificationBeforeTheStoreHasLoaded_appliesTheCalendarOverrideAndDeclinedToggle_toTheBaseline() = runTest {
        // a calendar normally hidden by the provider (visible = false), forced in via a Settings override
        val hiddenCalendar = CalendarInfo(
            id = 9, accountName = "family@group.calendar.google.com", accountType = "com.google", displayName = "Family",
            color = 0, visible = false, syncEvents = true, ownerAccount = "family@group.calendar.google.com",
            isPrimary = false, accessLevel = 700, canOrganizerRespond = false,
        )
        val onHiddenCalendar = standup.copy(calendarId = hiddenCalendar.id)
        repository.calendars = listOf(hiddenCalendar)
        repository.events[today] = listOf(onHiddenCalendar)
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(onHiddenCalendar)))
        val changeSnapshotDao = FakeChangeSnapshotDao()
        val settings = FakeSettingsRepository(Settings(calendarOverrides = mapOf(hiddenCalendar.id to true)))

        dayPlanDao.shareText(changeSnapshotDao, settings, state = CalendarGrantedAppState)

        // the fresh read used Only(9), not the default Visible, so the hidden calendar's event
        // is in the baseline instead of missing (which would read as New on the next check)
        assertThat(repository.eventQueries).containsExactly(today to CalendarFilter.Only(setOf(hiddenCalendar.id)))
        val events = decodeChangeSnapshotEvents(changeSnapshotDao.forDate(today)!!.eventsJson)
        assertThat(events).hasSize(1)
        assertThat(events.single().eventId).isEqualTo(onHiddenCalendar.key.eventId)
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
        val settings = FakeSettingsRepository()
        val monitor = ChangeMonitor(repository, changeSnapshotDao, dayPlanDao, busyBlocks, FakeCalendarPermissionChecker(), notifier, scheduler, settings, clock, FakeScheduleChangeAlerter(), MainUiVisibility())
        val effect = object : ShareDaySideEffects {}.markNotShared(dayPlanDao, changeSnapshotDao, monitor, syncer(dayPlanDao, settings))

        effect.output(MarkNotShared(today), state = CalendarGrantedAppState).toList()

        assertThat(dayPlanDao.plansFlow.value.single().sharedAt).isNull()
        assertThat(changeSnapshotDao.forDate(today)).isNull()
        assertThat(notifier.cancelled).containsExactly(today)
        assertThat(scheduler.updates).containsExactly(emptySet<LocalDate>() to ChangeCheckReason.IN_APP)
    }

    @Test
    fun shareDay_withBusySyncOn_fansOutTheSync_rightAfterThePendingShare() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        val output = shareDay(dayPlanDao, FakeChangeSnapshotDao(), busySyncOn()).output(ShareDay(today), state = loaded(standup)).toList()

        // the order is load-bearing (TODO.md §4.7): the baseline is already written and the
        // chooser already has its text before a single provider write is asked for
        assertThat(output).containsExactly(
            SetPendingShare((output.first() as SetPendingShare).share),
            SyncBusyCalendar(today, listOf(BusyRange(standup.begin, standup.end))),
        )
    }

    @Test
    fun shareDay_syncOnly_recordsTheShare_endsItAtOnce_andAnnouncesTheSync_withoutAChooser() = runTest {
        repository.calendars = listOf(familyCalendar)
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))
        val changeSnapshotDao = FakeChangeSnapshotDao()

        val output = shareDay(dayPlanDao, changeSnapshotDao, busySyncOn(familyCalendar.id, sendText = false))
            .output(ShareDay(today), state = loaded(standup)).toList()

        assertThat(output).containsExactly(
            ShareFinished,
            SyncBusyCalendar(today, listOf(BusyRange(standup.begin, standup.end)), announce = true),
        )
        // still a share as far as change detection and "Mark as not shared" are concerned
        assertThat(dayPlanDao.plansFlow.value.single().sharedAt).isEqualTo(now.toEpochMilli())
        assertThat(changeSnapshotDao.forDate(today)).isNotNull()
    }

    @Test
    fun shareDay_withTheTextOff_butNoWritableCalendar_stillOpensTheChooser() = runTest {
        repository.calendars = emptyList()
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        val output = shareDay(dayPlanDao, FakeChangeSnapshotDao(), busySyncOn(familyCalendar.id, sendText = false))
            .output(ShareDay(today), state = loaded(standup)).toList()

        assertThat(output.first()).isInstanceOf(SetPendingShare::class)
        assertThat(output.filterIsInstance<SyncBusyCalendar>().single().announce).isFalse()
    }

    @Test
    fun shareDay_withNothingSelected_syncsAnEmptyDay_whichTakesTheDaysBlocksBackOut() = runTest {
        val dayPlanDao = FakeDayPlanDao()

        val output = shareDay(dayPlanDao, FakeChangeSnapshotDao(), busySyncOn()).output(ShareDay(today), state = loaded()).toList()

        assertThat(output.filterIsInstance<SyncBusyCalendar>()).containsExactly(SyncBusyCalendar(today, emptyList()))
    }

    @Test
    fun shareDay_withBusySyncOff_neverFansOutTheSync() = runTest {
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))

        val output = shareDay(dayPlanDao, FakeChangeSnapshotDao()).output(ShareDay(today), state = loaded(standup)).toList()

        assertThat(output.filterIsInstance<SyncBusyCalendar>()).isEmpty()
    }

    @Test
    fun shareDay_fromANotificationBeforeTheStoreHasLoaded_leavesOurOwnBusyBlocksOutOfTheTextAndTheBaseline() = runTest {
        // one block still carrying the CUSTOM_APP_PACKAGE marker, one whose marker didn't
        // survive the sync round trip and is only known from the busy_block table (§4.7)
        val markedBlock = testCalendarEvent(9, at(11), at(12), title = "busy", meeting = false, ownedByApp = true)
        val tabledBlock = testCalendarEvent(10, at(13), at(14), title = "busy", meeting = false)
        busyBlocks.upsert(
            BusyBlockEntity(
                eventId = tabledBlock.eventId, date = today, calendarId = tabledBlock.calendarId,
                beginMillis = tabledBlock.begin.toEpochMilli(), endMillis = tabledBlock.end.toEpochMilli(),
            ),
        )
        repository.events[today] = listOf(standup, markedBlock, tabledBlock)
        val dayPlanDao = FakeDayPlanDao(selections = listOf(selection(standup)))
        val changeSnapshotDao = FakeChangeSnapshotDao()

        val text = dayPlanDao.shareText(changeSnapshotDao, state = CalendarGrantedAppState)

        // neither block is a busy range of the share ...
        assertThat(text).isEqualTo("Mon Sep 14 — I'm in meetings:\n• 9:00 – 9:30 AM\nFree the rest of the day.")
        // ... nor an event of the baseline, which the next check would otherwise diff against
        assertThat(decodeChangeSnapshotEvents(changeSnapshotDao.forDate(today)!!.eventsJson).map { it.eventId })
            .containsExactly(standup.eventId)
    }

    @Test
    fun markNotShared_alsoDeletesTheDaysBusyBlocks_andLeavesAnotherDaysAlone() = runTest {
        val dayPlanDao = FakeDayPlanDao(plans = listOf(DayPlanEntity(today, sharedAt = now.toEpochMilli())))
        val changeSnapshotDao = FakeChangeSnapshotDao(entities = listOf(ChangeSnapshotEntity(today, now.toEpochMilli(), "[]")))
        val settings = busySyncOn()
        busyBlocks.upsert(BusyBlockEntity(eventId = 900, date = today, calendarId = 1, beginMillis = 0, endMillis = 1))
        busyBlocks.upsert(BusyBlockEntity(eventId = 901, date = today.plusDays(1), calendarId = 1, beginMillis = 0, endMillis = 1))
        repository.ownEvents += setOf(900L, 901L)
        val monitor = ChangeMonitor(repository, changeSnapshotDao, dayPlanDao, busyBlocks, FakeCalendarPermissionChecker(), notifier, scheduler, settings, clock, FakeScheduleChangeAlerter(), MainUiVisibility())
        val effect = object : ShareDaySideEffects {}.markNotShared(dayPlanDao, changeSnapshotDao, monitor, syncer(dayPlanDao, settings))

        effect.output(MarkNotShared(today), state = CalendarGrantedAppState).toList()

        assertThat(repository.deletedEventIds).containsExactly(900L)
        assertThat(busyBlocks.entries.map { it.eventId }).containsExactly(901L)
    }
}

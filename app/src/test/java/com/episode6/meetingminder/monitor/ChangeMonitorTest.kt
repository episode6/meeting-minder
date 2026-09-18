package com.episode6.meetingminder.monitor

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.episode6.meetingminder.data.calendar.CalendarFilter
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.data.db.ChangeSnapshotDao
import com.episode6.meetingminder.data.db.ChangeSnapshotEntity
import com.episode6.meetingminder.data.db.FakeChangeSnapshotDao
import com.episode6.meetingminder.data.db.BusyBlockEntity
import com.episode6.meetingminder.data.db.FakeBusyBlockDao
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.SelectedEventEntity
import com.episode6.meetingminder.data.db.decodeScheduleChanges
import com.episode6.meetingminder.data.db.encodeChangeSnapshotEvents
import com.episode6.meetingminder.data.db.encodeScheduleChanges
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.Settings
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.RsvpState
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.model.SelfStatus
import com.episode6.meetingminder.model.testCalendarEvent
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** [ChangeMonitor] over fakes: what gets recorded, notified, dropped and (re-)armed. It is 12:00 UTC on [today]. */
class ChangeMonitorTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val yesterday = today.minusDays(1)
    private val tomorrow = today.plusDays(1)
    private val clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC)

    private fun LocalDate.at(hour: Int, minute: Int = 0): Instant = atTime(hour, minute).toInstant(ZoneOffset.UTC)

    private val designReview = testCalendarEvent(1, today.at(13), today.at(14), title = "Design review")
    private val invite = testCalendarEvent(2, today.at(15), today.at(15, 30), title = "New invite")

    private val repository = FakeCalendarRepository()
    private val dayPlanDao = FakeDayPlanDao()
    private val busyBlockDao = FakeBusyBlockDao()
    private val permissions = FakeCalendarPermissionChecker()
    private val notifier = FakeScheduleChangeNotifier()
    private val scheduler = FakeChangeWorkScheduler()
    private val alerter = FakeScheduleChangeAlerter()
    private val mainUi = MainUiVisibility()

    private fun sharedSnapshot(date: LocalDate, events: List<CalendarEvent>, selected: List<CalendarEvent> = events, takenAt: Long = 1_000) =
        ChangeSnapshotEntity(date, takenAt, encodeChangeSnapshotEvents(events, selected.mapTo(mutableSetOf()) { it.key }))

    private fun monitor(snapshotDao: ChangeSnapshotDao, settings: FakeSettingsRepository = FakeSettingsRepository()) =
        ChangeMonitor(repository, snapshotDao, dayPlanDao, busyBlockDao, permissions, notifier, scheduler, settings, clock, alerter, mainUi)

    private val moved = ScheduleChange.Moved(today, designReview.key, today.at(13), today.at(14), today.at(13, 30), today.at(14, 30))
    private val new = ScheduleChange.New(today, invite.key, invite.begin, invite.end)

    @Test
    fun runCheck_recordsWhatChanged_notifiesWithAnAlert_andReArmsTheTrigger() = runTest {
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview))))
        repository.events[today] = listOf(designReview, invite)

        monitor(snapshots).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(decodeScheduleChanges(today, snapshots.entries.getValue(today).changesJson)).containsExactly(new)
        assertThat(notifier.shown).containsExactly(FakeScheduleChangeNotifier.Shown(today, listOf(new), alert = true))
        assertThat(scheduler.updates).containsExactly(setOf(today) to ChangeCheckReason.CONTENT_TRIGGER)
    }

    @Test
    fun runCheck_findingTheSameChangesAgain_doesNotNotifyAgain() = runTest {
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview))))
        repository.events[today] = listOf(designReview, invite)
        val monitor = monitor(snapshots)

        monitor.runCheck(ChangeCheckReason.CONTENT_TRIGGER)
        monitor.runCheck(ChangeCheckReason.PERIODIC)

        assertThat(notifier.shown.size).isEqualTo(1)
        assertThat(scheduler.updates.map { it.second }).containsExactly(ChangeCheckReason.CONTENT_TRIGGER, ChangeCheckReason.PERIODIC)
    }

    @Test
    fun runCheck_whenAChangeIsAdded_alertsWithEveryChange() = runTest {
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview)).copy(changesJson = encodeScheduleChanges(listOf(new)))))
        repository.events[today] = listOf(designReview.copy(begin = today.at(13, 30), end = today.at(14, 30)), invite)

        monitor(snapshots).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(notifier.shown).containsExactly(FakeScheduleChangeNotifier.Shown(today, listOf(moved, new), alert = true))
    }

    @Test
    fun runCheck_whenAChangeOnlyDropsOut_updatesTheNotificationWithoutAlerting() = runTest {
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview)).copy(changesJson = encodeScheduleChanges(listOf(moved, new)))))
        repository.events[today] = listOf(designReview.copy(begin = today.at(13, 30), end = today.at(14, 30)))

        monitor(snapshots).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(notifier.shown).containsExactly(FakeScheduleChangeNotifier.Shown(today, listOf(moved), alert = false))
        assertThat(decodeScheduleChanges(today, snapshots.entries.getValue(today).changesJson)).containsExactly(moved)
    }

    @Test
    fun runCheck_whenTheDayIsBackToWhatWasShared_cancelsTheNotification_andClearsTheRecord() = runTest {
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview)).copy(changesJson = encodeScheduleChanges(listOf(new)))))
        repository.events[today] = listOf(designReview)

        monitor(snapshots).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(notifier.shown).isEmpty()
        assertThat(notifier.cancelled).containsExactly(today)
        assertThat(decodeScheduleChanges(today, snapshots.entries.getValue(today).changesJson)).isEmpty()
    }

    @Test
    fun runCheck_monitorsFutureSharedDaysToo_overTheWholeDay() = runTest {
        val early = testCalendarEvent(3, tomorrow.at(7), tomorrow.at(7, 30))
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview)), sharedSnapshot(tomorrow, emptyList())))
        repository.events[today] = listOf(designReview)
        repository.events[tomorrow] = listOf(early)

        monitor(snapshots).runCheck(ChangeCheckReason.PERIODIC)

        assertThat(notifier.shown).containsExactly(
            FakeScheduleChangeNotifier.Shown(tomorrow, listOf(ScheduleChange.New(tomorrow, early.key, early.begin, early.end)), alert = true),
        )
        assertThat(scheduler.updates).containsExactly(setOf(today, tomorrow) to ChangeCheckReason.PERIODIC)
    }

    @Test
    fun runCheck_dropsDaysThatHaveEnded_andDisarmsWhenNoneAreLeft() = runTest {
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(yesterday, emptyList())))

        monitor(snapshots).runCheck(ChangeCheckReason.DAY_ENDED)

        assertThat(snapshots.entries[yesterday]).isNull()
        assertThat(notifier.cancelled).containsExactly(yesterday)
        assertThat(repository.eventQueries).isEmpty()
        assertThat(scheduler.updates).containsExactly(emptySet<LocalDate>() to ChangeCheckReason.DAY_ENDED)
    }

    @Test
    fun runCheck_withNothingShared_disarms() = runTest {
        monitor(FakeChangeSnapshotDao()).runCheck(ChangeCheckReason.IN_APP)

        assertThat(scheduler.updates).containsExactly(emptySet<LocalDate>() to ChangeCheckReason.IN_APP)
    }

    @Test
    fun runCheck_withoutCalendarAccess_readsNothing_butStaysArmed() = runTest {
        permissions.calendarGranted = false
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview))))

        monitor(snapshots).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(repository.eventQueries).isEmpty()
        assertThat(notifier.shown).isEmpty()
        assertThat(scheduler.updates).containsExactly(setOf(today) to ChangeCheckReason.CONTENT_TRIGGER)
    }

    @Test
    fun runCheck_whenADayCantBeRead_skipsIt_andStaysArmed() = runTest {
        repository.error = SecurityException("revoked")
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview))))

        monitor(snapshots).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(notifier.shown).isEmpty()
        assertThat(notifier.cancelled).isEmpty()
        assertThat(scheduler.updates).containsExactly(setOf(today) to ChangeCheckReason.CONTENT_TRIGGER)
    }

    @Test
    fun runCheck_promotesWaitingRsvpsOnASharedDayToSynced() = runTest {
        dayPlanDao.selectionsFlow.value = listOf(
            SelectedEventEntity(today, 1, 0, "Design review", 0, 0, rsvpState = RsvpState.ACCEPTED_LOCALLY, rsvpEventId = 1),
        )
        repository.syncedIds += 1L
        repository.events[today] = listOf(designReview)

        monitor(FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview))))).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(dayPlanDao.selectionsFlow.value.single().rsvpState).isEqualTo(RsvpState.SYNCED)
    }

    @Test
    fun runCheck_whenTheDayIsReSharedMidCheck_recordsAndNotifiesNothing() = runTest {
        val fake = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview))))
        val reSharedDuringTheRead = object : ChangeSnapshotDao by fake {
            override suspend fun all(): List<ChangeSnapshotEntity> = fake.all().also {
                fake.upsert(sharedSnapshot(today, listOf(designReview, invite), takenAt = 2_000))
            }
        }
        repository.events[today] = listOf(designReview, invite)

        monitor(reSharedDuringTheRead).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(notifier.shown).isEmpty()
        assertThat(decodeScheduleChanges(today, fake.entries.getValue(today).changesJson)).isEmpty()
    }

    @Test
    fun runCheck_whenADayIsSharedMidCheck_leavesMonitoringArmedForIt() = runTest {
        val fake = FakeChangeSnapshotDao(emptyList())
        lateinit var checker: ChangeMonitor
        var shared = false
        val sharedDuringTheRead = object : ChangeSnapshotDao by fake {
            override suspend fun all(): List<ChangeSnapshotEntity> = fake.all().also {
                if (shared) return@also
                shared = true
                // ShareDay writes the baseline, then arms, while this check is still running
                launch(start = CoroutineStart.UNDISPATCHED) {
                    fake.upsert(sharedSnapshot(today, listOf(designReview)))
                    checker.onShareChanged(today)
                }
            }
        }
        checker = monitor(sharedDuringTheRead)

        checker.runCheck(ChangeCheckReason.PERIODIC)
        advanceUntilIdle()

        assertThat(scheduler.updates.last()).isEqualTo(setOf(today) to ChangeCheckReason.IN_APP)
    }

    @Test
    fun runCheck_appliesTheCalendarOverrides_toTheFreshRead() = runTest {
        val settings = FakeSettingsRepository(Settings(calendarOverrides = mapOf(2L to false)))
        fun calendar(id: Long, name: String) = CalendarInfo(
            id = id,
            accountName = "me",
            accountType = "com.google",
            displayName = name,
            color = 0,
            visible = true,
            syncEvents = true,
            ownerAccount = "me",
            isPrimary = id == 1L,
            accessLevel = 700,
            canOrganizerRespond = false,
        )
        repository.calendars = listOf(calendar(1, "Work"), calendar(2, "Personal"))
        repository.events[today] = listOf(designReview, invite.copy(calendarId = 2))
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview))))

        monitor(snapshots, settings).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        // the excluded calendar's invite never enters the fresh read, so it can't be seen as New
        assertThat(notifier.shown).isEmpty()
        assertThat(repository.eventQueries).containsExactly(today to CalendarFilter.Only(setOf(1L)))
    }

    @Test
    fun runCheck_showDeclinedOff_neverReportsADeclinedInviteAsNew() = runTest {
        val settings = FakeSettingsRepository(Settings(showDeclined = false))
        repository.events[today] = listOf(designReview, invite.copy(selfStatus = SelfStatus.DECLINED))
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview))))

        monitor(snapshots, settings).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(notifier.shown).isEmpty()
    }

    @Test
    fun onShareChanged_cancelsTheDaysNotification_andArmsForEverySharedDayFromToday() = runTest {
        val snapshots = FakeChangeSnapshotDao(
            listOf(sharedSnapshot(yesterday, emptyList()), sharedSnapshot(today, emptyList()), sharedSnapshot(tomorrow, emptyList())),
        )

        monitor(snapshots).onShareChanged(tomorrow)

        assertThat(notifier.cancelled).containsExactly(tomorrow)
        assertThat(alerter.cancelled).containsExactly(tomorrow)
        assertThat(scheduler.updates).containsExactly(setOf(today, tomorrow) to ChangeCheckReason.IN_APP)
    }

    @Test
    fun runCheck_inTheBackground_aNewChangeToday_ringsTheLoudAlert_andTheNotificationStaysSilent() = runTest {
        alerter.rings = true
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview))))
        repository.events[today] = listOf(designReview, invite)

        monitor(snapshots).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(alerter.alerted).containsExactly(today)
        assertThat(notifier.shown).containsExactly(FakeScheduleChangeNotifier.Shown(today, listOf(new), alert = true, silent = true))
    }

    @Test
    fun runCheck_inTheBackground_whileTheAppIsOnScreen_neverRingsTheLoudAlert() = runTest {
        // the worker and the foreground reload both run for one provider change; the worker can win
        alerter.rings = true
        mainUi.visible = true
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview))))
        repository.events[today] = listOf(designReview, invite)

        monitor(snapshots).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(alerter.alerted).isEmpty()
        assertThat(notifier.shown).containsExactly(FakeScheduleChangeNotifier.Shown(today, listOf(new), alert = true, silent = false))
    }

    @Test
    fun runCheck_whenTheLoudAlertCantRing_theNotificationMakesTheNoise() = runTest {
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview))))
        repository.events[today] = listOf(designReview, invite)

        monitor(snapshots).runCheck(ChangeCheckReason.PERIODIC)

        assertThat(alerter.alerted).containsExactly(today)
        assertThat(notifier.shown).containsExactly(FakeScheduleChangeNotifier.Shown(today, listOf(new), alert = true, silent = false))
    }

    @Test
    fun runCheck_neverRingsTheLoudAlert_fromTheAppsOwnCheck_forAnotherDay_orForOldNews() = runTest {
        alerter.rings = true
        val tomorrowsInvite = testCalendarEvent(3, tomorrow.at(9), tomorrow.at(10), title = "Tomorrow's invite")
        val snapshots = FakeChangeSnapshotDao(
            listOf(sharedSnapshot(today, listOf(designReview)), sharedSnapshot(tomorrow, emptyList())),
        )
        repository.events[today] = listOf(designReview)
        repository.events[tomorrow] = listOf(tomorrowsInvite)
        val monitor = monitor(snapshots)

        // a background check, but the change is on a day shared ahead
        monitor.runCheck(ChangeCheckReason.CONTENT_TRIGGER)
        // the app's own foreground check: the banner is in front of the user
        repository.events[today] = listOf(designReview, invite)
        monitor.runCheck(ChangeCheckReason.IN_APP)
        // a background check finding only what is already recorded
        monitor.runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(alerter.alerted).isEmpty()
        assertThat(notifier.shown.map { it.silent }).containsExactly(false, false)
    }

    @Test
    fun runCheck_whenTheDayIsBackToWhatWasShared_cancelsTheLoudAlertToo() = runTest {
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview)).copy(changesJson = encodeScheduleChanges(listOf(new)))))
        repository.events[today] = listOf(designReview)

        monitor(snapshots).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(alerter.cancelled).containsExactly(today)
    }

    @Test
    fun runCheck_neverReportsTheBusyBlocksTheAppItselfWrote() = runTest {
        // the share that wrote them took its baseline from an already filtered read, so an
        // unfiltered fresh read would report every block as New (TODO.md §4.7)
        val markedBlock = testCalendarEvent(9, today.at(13), today.at(14), title = "busy", meeting = false, ownedByApp = true)
        val tabledBlock = testCalendarEvent(10, today.at(15), today.at(16), title = "busy", meeting = false)
        busyBlockDao.upsert(
            BusyBlockEntity(
                eventId = tabledBlock.eventId, date = today, calendarId = tabledBlock.calendarId,
                beginMillis = tabledBlock.begin.toEpochMilli(), endMillis = tabledBlock.end.toEpochMilli(),
            ),
        )
        val snapshots = FakeChangeSnapshotDao(listOf(sharedSnapshot(today, listOf(designReview))))
        repository.events[today] = listOf(designReview, markedBlock, tabledBlock)

        monitor(snapshots).runCheck(ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(decodeScheduleChanges(today, snapshots.entries.getValue(today).changesJson)).isEmpty()
        assertThat(notifier.shown).isEmpty()
    }
}

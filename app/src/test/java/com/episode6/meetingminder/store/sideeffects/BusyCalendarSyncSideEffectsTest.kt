package com.episode6.meetingminder.store.sideeffects

import android.provider.CalendarContract.Calendars
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.data.db.BusyBlockEntity
import com.episode6.meetingminder.data.db.DayPlanEntity
import com.episode6.meetingminder.data.db.FakeBusyBlockDao
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.settings.BusySync
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.Settings
import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.share.BusyCalendarSyncer
import com.episode6.meetingminder.store.BusySyncSettingChanged
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.SyncBusyCalendar
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The store wiring of TODO.md §4.7: what a sync says back to the UI, and what the Settings
 * cleanup deletes. The syncer itself is the real one over the fakes — these cases are about
 * which days and which calendar it is pointed at, and what comes back out as actions.
 */
class BusyCalendarSyncSideEffectsTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val yesterday = today.minusDays(1)
    private val tomorrow = today.plusDays(1)
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC)

    private val nine = Instant.parse("2026-09-14T09:00:00Z")
    private val ten = Instant.parse("2026-09-14T10:00:00Z")

    private val family = calendar(7, "Family")
    private val work = calendar(8, "Work")

    private val repository = FakeCalendarRepository(calendars = listOf(family, work))
    private val dao = FakeBusyBlockDao()
    private val dayPlanDao = FakeDayPlanDao(plans = listOf(DayPlanEntity(today, sharedAt = 1)))

    private fun calendar(id: Long, name: String) = CalendarInfo(
        id = id, accountName = "me@example.com", accountType = "com.google", displayName = name, color = 0, visible = true,
        syncEvents = true, ownerAccount = "me@example.com", isPrimary = false,
        accessLevel = Calendars.CAL_ACCESS_OWNER, canOrganizerRespond = true,
    )

    private fun settings(enabled: Boolean = true, calendarId: Long? = family.id) =
        FakeSettingsRepository(Settings(busySync = BusySync(enabled = enabled, calendarId = calendarId)))

    private fun syncer(settings: FakeSettingsRepository) = BusyCalendarSyncer(repository, dao, dayPlanDao, settings, clock)

    private fun syncEffect(settings: FakeSettingsRepository = settings()) =
        object : BusyCalendarSyncSideEffects {}.syncBusyCalendar(syncer(settings))

    private fun cleanupEffect(settings: FakeSettingsRepository = settings()) =
        object : BusyCalendarSyncSideEffects {}.busySyncSettingChanged(syncer(settings), clock)

    private fun row(eventId: Long, date: LocalDate, calendarId: Long = family.id) =
        BusyBlockEntity(eventId = eventId, date = date, calendarId = calendarId, beginMillis = 0, endMillis = 1)

    private suspend fun seed(vararg rows: BusyBlockEntity) {
        for (row in rows) {
            dao.upsert(row)
            repository.ownEvents += row.eventId
        }
    }

    @Test
    fun syncBusyCalendar_writesTheRanges_andSaysNothingWhenItWorked() = runTest {
        val output = syncEffect().output(SyncBusyCalendar(today, listOf(BusyRange(nine, ten))), state = TestAppState).toList()

        // no success snackbar: the share itself is the confirmation (TODO.md §4.7)
        assertThat(output).isEmpty()
        assertThat(repository.busyBlockInserts.map { it.calendarId to it.range }).containsExactly(family.id to BusyRange(nine, ten))
        assertThat(dao.entries.map { it.date }).containsExactly(today)
    }

    @Test
    fun syncBusyCalendar_announced_bySyncOnlyShare_saysItWorked_withTheCalendarsName() = runTest {
        val output = syncEffect().output(SyncBusyCalendar(today, listOf(BusyRange(nine, ten)), announce = true), state = TestAppState).toList()

        // a sync-only share opened no chooser, so this snackbar is the only confirmation
        val message = (output.single() as ShowMessage).message
        assertThat(message.text).isEqualTo(R.string.busy_sync_done)
        assertThat(message.formatArgs).containsExactly(family.displayName)
    }

    @Test
    fun syncBusyCalendar_whileTheFeatureIsOff_writesNothing_andSaysNothing() = runTest {
        val output = syncEffect(settings(enabled = false))
            .output(SyncBusyCalendar(today, listOf(BusyRange(nine, ten))), state = TestAppState).toList()

        assertThat(output).isEmpty()
        assertThat(repository.busyBlockInserts).isEmpty()
    }

    @Test
    fun syncBusyCalendar_whenAProviderWriteFails_saysSoWithTheCalendarsName() = runTest {
        repository.busyBlockInsertError = { IllegalStateException("provider said no") }

        val output = syncEffect().output(SyncBusyCalendar(today, listOf(BusyRange(nine, ten))), state = TestAppState).toList()

        val message = (output.single() as ShowMessage).message
        assertThat(message.text).isEqualTo(R.string.busy_sync_failed)
        assertThat(message.formatArgs).containsExactly(family.displayName)
    }

    @Test
    fun syncBusyCalendar_whenCalendarAccessIsRevoked_reChecksPermissions() = runTest {
        repository.busyBlockInsertError = { SecurityException("revoked") }

        val output = syncEffect().output(SyncBusyCalendar(today, listOf(BusyRange(nine, ten))), state = TestAppState).toList()

        assertThat(output).containsExactly(PermissionsMaybeChanged)
    }

    @Test
    fun syncBusyCalendar_whenTheReadBeforeTheWriteThrows_saysSoWithoutACalendarName_ratherThanEndingTheEffect() = runTest {
        // the syncer turns a failed provider *write* into Failed itself; this is what throws
        // before it (here the fresh calendar list), and must not escape the flow — an
        // exception out of flatMapMerge would end this effect for the rest of the process
        repository.error = IllegalStateException("provider hiccup")

        val output = syncEffect().output(SyncBusyCalendar(today, listOf(BusyRange(nine, ten))), state = TestAppState).toList()

        val message = (output.single() as ShowMessage).message
        assertThat(message.text).isEqualTo(R.string.busy_sync_failed_unknown_calendar)
        assertThat(message.formatArgs).isEmpty()
        assertThat(repository.busyBlockInserts).isEmpty()
    }

    @Test
    fun syncBusyCalendar_afterTheDayWasMarkedNotShared_writesNothing() = runTest {
        // "Mark as not shared" cleared shared_at (and the day's blocks) before this queued
        // sync took the syncer's lock: the cleared day must not get the share's inserts
        dayPlanDao.setShared(today, sharedAt = null, sharedSnapshot = null)

        val output = syncEffect().output(SyncBusyCalendar(today, listOf(BusyRange(nine, ten))), state = TestAppState).toList()

        assertThat(output).isEmpty()
        assertThat(repository.busyBlockInserts).isEmpty()
        assertThat(dao.entries).isEmpty()
    }

    @Test
    fun busySyncTurnedOff_deletesTodayAndLater_onEveryCalendar_andKeepsThePast() = runTest {
        seed(row(1, yesterday), row(2, today), row(3, tomorrow, calendarId = work.id))
        val settings = settings(enabled = false)

        cleanupEffect(settings).output(BusySyncSettingChanged(previousCalendarId = family.id, calendarId = family.id, enabledNow = false), state = TestAppState).toList()

        // yesterday's block is history: it described a day that already happened
        assertThat(repository.deletedEventIds).containsExactly(2L, 3L)
        assertThat(dao.entries.map { it.eventId }).containsExactly(1L)
    }

    @Test
    fun busyCalendarSwitched_deletesOnlyTheOldCalendarsBlocks_fromTodayOn() = runTest {
        seed(row(1, yesterday, calendarId = work.id), row(2, today, calendarId = work.id), row(3, today, calendarId = family.id))

        // the setting now points at Family; Work is where the blocks were written
        cleanupEffect().output(BusySyncSettingChanged(previousCalendarId = work.id, calendarId = family.id, enabledNow = true), state = TestAppState).toList()

        assertThat(repository.deletedEventIds).containsExactly(2L)
        assertThat(dao.entries.map { it.eventId }).containsExactly(1L, 3L)
    }

    @Test
    fun busySyncTurnedOn_withNothingWrittenYet_deletesNothing() = runTest {
        seed(row(1, today))

        cleanupEffect().output(BusySyncSettingChanged(previousCalendarId = null, calendarId = family.id, enabledNow = true), state = TestAppState).toList()

        assertThat(repository.deletedEventIds).isEmpty()
        assertThat(dao.entries.map { it.eventId }).containsExactly(1L)
    }

    @Test
    fun reSelectingTheCalendarItAlreadyHad_deletesNothing() = runTest {
        seed(row(1, today))

        cleanupEffect().output(BusySyncSettingChanged(previousCalendarId = family.id, calendarId = family.id, enabledNow = true), state = TestAppState).toList()

        assertThat(repository.deletedEventIds).isEmpty()
        assertThat(dao.entries.map { it.eventId }).containsExactly(1L)
    }

    @Test
    fun cleanup_whenCalendarAccessIsRevoked_reChecksPermissions() = runTest {
        seed(row(1, today))
        repository.deleteOwnEventError = SecurityException("revoked")

        val output = cleanupEffect(settings(enabled = false))
            .output(BusySyncSettingChanged(previousCalendarId = family.id, calendarId = family.id, enabledNow = false), state = TestAppState).toList()

        assertThat(output).containsExactly(PermissionsMaybeChanged)
    }
}

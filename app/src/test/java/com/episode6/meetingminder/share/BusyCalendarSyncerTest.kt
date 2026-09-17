package com.episode6.meetingminder.share

import android.provider.CalendarContract.Calendars
import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasClass
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
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
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [BusyCalendarSyncer] over the fakes (TODO.md §4.7): when it writes nothing, the order it
 * writes in, that the table follows each write at once, and what a failure leaves behind.
 */
class BusyCalendarSyncerTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 9, 14)
    private val tomorrow = today.plusDays(1)
    private val yesterday = today.minusDays(1)
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), zone)

    private val ten = Instant.parse("2026-09-14T14:00:00Z")
    private val eleven = Instant.parse("2026-09-14T15:00:00Z")
    private val noon = Instant.parse("2026-09-14T16:00:00Z")
    private val one = Instant.parse("2026-09-14T17:00:00Z")

    private val family = calendar(7, "Family")
    private val work = calendar(8, "Work")

    private val repository = FakeCalendarRepository(calendars = listOf(family, work))
    private val dao = FakeBusyBlockDao()
    private val settings = FakeSettingsRepository(Settings(busySync = BusySync(enabled = true, calendarId = family.id)))
    private val dayPlanDao = FakeDayPlanDao(plans = listOf(DayPlanEntity(today, sharedAt = 1), DayPlanEntity(tomorrow, sharedAt = 1)))
    private val syncer = BusyCalendarSyncer(repository, dao, dayPlanDao, settings, clock)

    private fun calendar(id: Long, name: String, accessLevel: Int = Calendars.CAL_ACCESS_OWNER) = CalendarInfo(
        id = id, accountName = "me@example.com", accountType = "com.google", displayName = name, color = 0, visible = true,
        syncEvents = true, ownerAccount = "me@example.com", isPrimary = false, accessLevel = accessLevel, canOrganizerRespond = true,
    )

    private fun row(eventId: Long, begin: Instant, end: Instant, date: LocalDate = today, calendarId: Long = family.id) =
        BusyBlockEntity(eventId = eventId, date = date, calendarId = calendarId, beginMillis = begin.toEpochMilli(), endMillis = end.toEpochMilli())

    private suspend fun seed(vararg rows: BusyBlockEntity) {
        for (r in rows) {
            dao.upsert(r)
            repository.ownEvents += r.eventId
        }
    }

    @Test
    fun skipped_whenTheFeatureIsOff_evenWithACalendarChosen() = runTest {
        settings.settings.value = Settings(busySync = BusySync(enabled = false, calendarId = family.id))

        assertThat(syncer.sync(today, listOf(BusyRange(ten, eleven)))).isEqualTo(BusySyncResult.Skipped)
        assertThat(repository.busyBlockInserts).isEmpty()
        assertThat(dao.entries).isEmpty()
    }

    @Test
    fun skipped_whenNoCalendarIsChosen() = runTest {
        settings.settings.value = Settings(busySync = BusySync(enabled = true, calendarId = null))

        assertThat(syncer.sync(today, listOf(BusyRange(ten, eleven)))).isEqualTo(BusySyncResult.Skipped)
        assertThat(repository.busyBlockInserts).isEmpty()
    }

    @Test
    fun skipped_whenTheChosenCalendarIsGoneOrReadOnly_readFreshFromTheProvider() = runTest {
        repository.calendars = listOf(work)
        assertThat(syncer.sync(today, listOf(BusyRange(ten, eleven)))).isEqualTo(BusySyncResult.Skipped)

        repository.calendars = listOf(calendar(family.id, "Family", accessLevel = Calendars.CAL_ACCESS_READ), work)
        assertThat(syncer.sync(today, listOf(BusyRange(ten, eleven)))).isEqualTo(BusySyncResult.Skipped)

        assertThat(repository.busyBlockInserts).isEmpty()
        assertThat(repository.deletedEventIds).isEmpty()
    }

    @Test
    fun skipped_whenTheDayIsNoLongerShared_soAClearThatWonTheLockIsNotUndone() = runTest {
        // "Mark as not shared" cleared shared_at and the day's blocks before this sync ran
        dayPlanDao.setShared(today, sharedAt = null, sharedSnapshot = null)

        assertThat(syncer.sync(today, listOf(BusyRange(ten, eleven)))).isEqualTo(BusySyncResult.Skipped)
        assertThat(repository.busyBlockInserts).isEmpty()
        assertThat(dao.entries).isEmpty()
    }

    @Test
    fun aRangeAcrossMidnight_isClippedToTheDay_soEachOfItsDaysWritesItsOwnPart() = runTest {
        // 11 PM – 1 AM New York, selected on both of its pages and shared from each
        val elevenPm = today.atTime(23, 0).atZone(zone).toInstant()
        val oneAm = tomorrow.atTime(1, 0).atZone(zone).toInstant()
        val midnight = tomorrow.atStartOfDay(zone).toInstant()
        repository.nextBusyBlockId = 100

        syncer.sync(today, listOf(BusyRange(elevenPm, oneAm)))
        syncer.sync(tomorrow, listOf(BusyRange(elevenPm, oneAm)))

        assertThat(repository.busyBlockInserts.map { it.range }).containsExactly(BusyRange(elevenPm, midnight), BusyRange(midnight, oneAm))
        assertThat(dao.entries).containsExactly(row(100, elevenPm, midnight), row(101, midnight, oneAm, date = tomorrow))
    }

    @Test
    fun everySync_forgetsRowsOlderThanTheHistoryWindow_withoutTouchingTheCalendar() = runTest {
        val old = today.minusDays(BUSY_BLOCK_HISTORY_DAYS + 1)
        val edge = today.minusDays(BUSY_BLOCK_HISTORY_DAYS)
        seed(row(1, ten, eleven, date = old), row(2, ten, eleven, date = edge))

        syncer.sync(today, emptyList())

        assertThat(dao.entries).containsExactly(row(2, ten, eleven, date = edge))
        // forgotten, not deleted: the calendar keeps the old block as history
        assertThat(repository.deletedEventIds).isEmpty()
        assertThat(repository.ownEvents).isEqualTo(setOf(1L, 2L))
    }

    @Test
    fun aSkippedSync_leavesExistingRowsAlone() = runTest {
        seed(row(1, ten, eleven))
        settings.settings.value = Settings(busySync = BusySync(enabled = false, calendarId = family.id))

        syncer.sync(today, emptyList())

        assertThat(dao.entries).containsExactly(row(1, ten, eleven))
        assertThat(repository.deletedEventIds).isEmpty()
    }

    @Test
    fun aFreshDay_insertsEveryRangeInOrder_onTheChosenCalendar_andRecordsEachId() = runTest {
        repository.nextBusyBlockId = 100

        val result = syncer.sync(today, listOf(BusyRange(ten, eleven), BusyRange(noon, one)))

        assertThat(result).isEqualTo(BusySyncResult.Synced("Family", inserted = 2, deleted = 0))
        assertThat(repository.busyBlockInserts).containsExactly(
            FakeCalendarRepository.BusyBlockInsert(family.id, BusyRange(ten, eleven)),
            FakeCalendarRepository.BusyBlockInsert(family.id, BusyRange(noon, one)),
        )
        assertThat(dao.entries).containsExactly(row(100, ten, eleven), row(101, noon, one))
    }

    @Test
    fun aReShare_deletesFirstThenInserts_keepingTheUnchangedRow() = runTest {
        seed(row(1, ten, eleven), row(2, noon, one))
        repository.nextBusyBlockId = 100
        val moved = BusyRange(noon.plusSeconds(60), one)

        val result = syncer.sync(today, listOf(BusyRange(ten, eleven), moved))

        assertThat(result).isEqualTo(BusySyncResult.Synced("Family", inserted = 1, deleted = 1))
        assertThat(repository.deletedEventIds).containsExactly(2L)
        assertThat(repository.busyBlockInserts.map { it.range }).containsExactly(moved)
        assertThat(dao.entries).containsExactly(row(1, ten, eleven), row(100, moved.begin, moved.end))
        assertThat(repository.ownEvents).isEqualTo(setOf(1L, 100L))
    }

    @Test
    fun aRowOnAnotherCalendar_isDeletedAndReInsertedOnTheChosenOne_neverReHomed() = runTest {
        // written while Work was the target, before the user switched to Family
        seed(row(1, ten, eleven, calendarId = work.id))
        repository.nextBusyBlockId = 100

        val result = syncer.sync(today, listOf(BusyRange(ten, eleven)))

        assertThat(result).isEqualTo(BusySyncResult.Synced("Family", inserted = 1, deleted = 1))
        assertThat(repository.deletedEventIds).containsExactly(1L)
        assertThat(repository.busyBlockInserts).containsExactly(FakeCalendarRepository.BusyBlockInsert(family.id, BusyRange(ten, eleven)))
        assertThat(dao.entries).containsExactly(row(100, ten, eleven))
    }

    @Test
    fun anEmptyShare_removesEveryBlockOfTheDay_andOnlyThatDay() = runTest {
        seed(row(1, ten, eleven), row(2, noon, one, date = tomorrow))

        val result = syncer.sync(today, emptyList())

        assertThat(result).isEqualTo(BusySyncResult.Synced("Family", inserted = 0, deleted = 1))
        assertThat(repository.deletedEventIds).containsExactly(1L)
        assertThat(dao.entries).containsExactly(row(2, noon, one, date = tomorrow))
    }

    @Test
    fun aDeleteThatFindsTheRowAlreadyGone_stillDropsTheTableRow() = runTest {
        dao.upsert(row(1, ten, eleven))
        // not in repository.ownEvents: the user deleted it by hand, so deleteOwnEvent answers false

        val result = syncer.sync(today, emptyList())

        assertThat(result).isEqualTo(BusySyncResult.Synced("Family", inserted = 0, deleted = 1))
        assertThat(repository.deletedEventIds).containsExactly(1L)
        assertThat(dao.entries).isEmpty()
    }

    @Test
    fun anInsertFailingHalfWay_keepsTheRowsThatSucceeded_andReturnsFailed() = runTest {
        repository.nextBusyBlockId = 100
        val boom = IllegalStateException("provider refused")
        repository.busyBlockInsertError = { range -> boom.takeIf { range.begin == noon } }

        val result = syncer.sync(today, listOf(BusyRange(ten, eleven), BusyRange(noon, one), BusyRange(one, one.plusSeconds(1800))))

        assertThat(result).isInstanceOf(BusySyncResult.Failed::class).all {
            prop(BusySyncResult.Failed::calendarName).isEqualTo("Family")
            prop(BusySyncResult.Failed::cause).isEqualTo(boom)
        }
        // the first insert's row is recorded; the third insert never ran
        assertThat(dao.entries).containsExactly(row(100, ten, eleven))
        assertThat(repository.busyBlockInserts.map { it.range }).containsExactly(BusyRange(ten, eleven), BusyRange(noon, one))
    }

    @Test
    fun aDeleteThatThrows_keepsItsRow_soTheNextShareRetriesIt() = runTest {
        seed(row(1, ten, eleven))
        repository.deleteOwnEventError = IllegalStateException("provider refused")

        val result = syncer.sync(today, emptyList())

        assertThat(result).isInstanceOf(BusySyncResult.Failed::class)
        assertThat(dao.entries).containsExactly(row(1, ten, eleven))
    }

    @Test
    fun aSecurityException_propagates_forTheCallersPermissionReCheck() = runTest {
        repository.busyBlockInsertError = { SecurityException("WRITE_CALENDAR revoked") }

        assertFailure { syncer.sync(today, listOf(BusyRange(ten, eleven))) }.hasClass(SecurityException::class)
        assertThat(dao.entries).isEmpty()
    }

    @Test
    fun clear_deletesThatDaysBlocks_andNothingElse() = runTest {
        seed(row(1, ten, eleven), row(2, noon, one, date = tomorrow))

        assertThat(syncer.clear(today)).isEqualTo(1)

        assertThat(repository.deletedEventIds).containsExactly(1L)
        assertThat(dao.entries).containsExactly(row(2, noon, one, date = tomorrow))
    }

    @Test
    fun clearFrom_deletesTodayAndLater_onEveryCalendar_leavingThePastAsHistory() = runTest {
        seed(row(1, ten, eleven, date = yesterday), row(2, ten, eleven), row(3, noon, one, date = tomorrow, calendarId = work.id))

        assertThat(syncer.clearFrom(today)).isEqualTo(2)

        assertThat(repository.deletedEventIds).containsExactly(2L, 3L)
        assertThat(dao.entries).containsExactly(row(1, ten, eleven, date = yesterday))
    }

    @Test
    fun clearFromWithACalendar_deletesOnlyThatCalendarsBlocks() = runTest {
        seed(row(1, ten, eleven, date = yesterday, calendarId = work.id), row(2, ten, eleven, calendarId = work.id), row(3, noon, one, date = tomorrow))

        assertThat(syncer.clearFrom(today, work.id)).isEqualTo(1)

        assertThat(repository.deletedEventIds).containsExactly(2L)
        assertThat(dao.entries).containsExactly(row(1, ten, eleven, date = yesterday, calendarId = work.id), row(3, noon, one, date = tomorrow))
    }

    @Test
    fun clear_worksWhileTheFeatureIsOff_theRowsAreWhatMatters() = runTest {
        seed(row(1, ten, eleven))
        settings.settings.value = Settings(busySync = BusySync(enabled = false, calendarId = null))

        assertThat(syncer.clearFrom(today)).isEqualTo(1)
        assertThat(dao.entries).isEmpty()
    }

    @Test
    fun aFirstName_goesToTheInsert_andTheTitleItMadeIsRecorded() = runTest {
        settings.setBusySyncFirstName("Geoff")
        repository.nextBusyBlockId = 100

        syncer.sync(today, listOf(BusyRange(ten, eleven)))

        assertThat(repository.busyBlockInserts)
            .containsExactly(FakeCalendarRepository.BusyBlockInsert(family.id, BusyRange(ten, eleven), firstName = "Geoff"))
        assertThat(dao.entries).containsExactly(row(100, ten, eleven).copy(title = "Geoff busy"))
    }

    @Test
    fun aReShareAfterTheNameChanged_replacesTheDaysBlocks_andASecondOneKeepsThem() = runTest {
        seed(row(1, ten, eleven))
        settings.setBusySyncFirstName("Geoff")
        repository.nextBusyBlockId = 100

        val renamed = syncer.sync(today, listOf(BusyRange(ten, eleven)))
        val again = syncer.sync(today, listOf(BusyRange(ten, eleven)))

        assertThat(renamed).isEqualTo(BusySyncResult.Synced("Family", inserted = 1, deleted = 1))
        assertThat(again).isEqualTo(BusySyncResult.Synced("Family", inserted = 0, deleted = 0))
        assertThat(repository.deletedEventIds).containsExactly(1L)
        assertThat(dao.entries).containsExactly(row(100, ten, eleven).copy(title = "Geoff busy"))
    }
}

package com.episode6.meetingminder.store.sideeffects

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.data.db.ChangeSnapshotEntity
import com.episode6.meetingminder.data.db.FakeChangeSnapshotDao
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.encodeScheduleChanges
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.monitor.ChangeCheckReason
import com.episode6.meetingminder.monitor.ChangeMonitor
import com.episode6.meetingminder.monitor.FakeCalendarPermissionChecker
import com.episode6.meetingminder.monitor.FakeChangeWorkScheduler
import com.episode6.meetingminder.monitor.FakeScheduleChangeNotifier
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.LoadDay
import com.episode6.meetingminder.store.SetScheduleChanges
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChangeDetectionSideEffectsTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val tomorrow = today.plusDays(1)
    private val clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC)

    private fun at(date: LocalDate, hour: Int): Instant = date.atTime(hour, 0).toInstant(ZoneOffset.UTC)

    private val newToday = ScheduleChange.New(today, EventKey(1, 0), at(today, 15), at(today, 16))
    private val cancelledTomorrow = ScheduleChange.Cancelled(tomorrow, EventKey(2, 0), at(tomorrow, 9), at(tomorrow, 10))

    @Test
    fun observeScheduleChanges_streamsEverySharedDaysRecordedChanges() = runTest {
        val dao = FakeChangeSnapshotDao(
            listOf(
                ChangeSnapshotEntity(today, 1, "[]", changesJson = encodeScheduleChanges(listOf(newToday))),
                ChangeSnapshotEntity(tomorrow, 1, "[]", changesJson = encodeScheduleChanges(listOf(cancelledTomorrow))),
            ),
        )

        object : ChangeDetectionSideEffects {}.observeScheduleChanges(dao).output().test {
            assertThat(awaitItem()).isEqualTo(SetScheduleChanges(listOf(newToday, cancelledTomorrow)))

            dao.upsert(ChangeSnapshotEntity(today, 2, "[]"))

            assertThat(awaitItem()).isEqualTo(SetScheduleChanges(listOf(cancelledTomorrow)))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun runChangeCheck_onCalendarContentChanged_checksEverySharedDay_andEmitsNothing() = runTest {
        val repository = FakeCalendarRepository(events = mutableMapOf(today to listOf(testCalendarEvent(1, at(today, 15), at(today, 16)))))
        val snapshots = FakeChangeSnapshotDao(listOf(ChangeSnapshotEntity(today, 1, "[]")))
        val scheduler = FakeChangeWorkScheduler()
        val monitor = ChangeMonitor(repository, snapshots, FakeDayPlanDao(), FakeCalendarPermissionChecker(), FakeScheduleChangeNotifier(), scheduler, FakeSettingsRepository(), clock)

        val output = object : ChangeDetectionSideEffects {}.runChangeCheck(monitor).output(LoadDay(today), CalendarContentChanged).toList()

        assertThat(output).isEmpty()
        assertThat(repository.eventQueries.map { it.first }).containsExactly(today)
        assertThat(scheduler.updates).containsExactly(setOf(today) to ChangeCheckReason.IN_APP)
    }
}

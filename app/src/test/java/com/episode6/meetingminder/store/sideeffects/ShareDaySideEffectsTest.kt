package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.episode6.meetingminder.data.db.ChangeSnapshotEntity
import com.episode6.meetingminder.data.db.DayPlanEntity
import com.episode6.meetingminder.data.db.FakeChangeSnapshotDao
import com.episode6.meetingminder.data.db.FakeDayPlanDao
import com.episode6.meetingminder.data.db.decodeBusyRanges
import com.episode6.meetingminder.data.db.decodeChangeSnapshotEvents
import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.DayPlan
import com.episode6.meetingminder.model.SelectedEvent
import com.episode6.meetingminder.model.testCalendarEvent
import com.episode6.meetingminder.store.MarkNotShared
import com.episode6.meetingminder.store.SetPendingShare
import com.episode6.meetingminder.store.ShareDay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ShareDaySideEffectsTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val now = Instant.parse("2026-09-14T08:35:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun at(hour: Int, minute: Int = 0): Instant = Instant.parse("2026-09-14T%02d:%02d:00Z".format(hour, minute))

    private val standup = testCalendarEvent(1, at(9), at(9, 30), title = "Standup")
    private val dentist = testCalendarEvent(2, at(7), at(8), title = "Dentist", meeting = false)

    private fun selected(event: CalendarEvent) = SelectedEvent(
        key = event.key,
        title = event.title,
        begin = event.begin,
        end = event.end,
    )

    @Test
    fun shareDay_formatsOnlySelectedEvents_andEmitsThePendingShare() = runTest {
        val dayPlanDao = FakeDayPlanDao()
        val changeSnapshotDao = FakeChangeSnapshotDao()
        val state = CalendarGrantedAppState.copy(
            eventsByDay = mapOf(today to DayEvents(today, listOf(standup, dentist), Instant.EPOCH)),
            dayPlans = mapOf(today to DayPlan(today, selected = mapOf(standup.key to selected(standup)))),
        )
        val effect = object : ShareDaySideEffects {}.shareDay(dayPlanDao, changeSnapshotDao, clock)

        val output = effect.output(ShareDay(today), state = state).toList()

        val share = (output.single() as SetPendingShare).share
        assertThat(share.date).isEqualTo(today)
        assertThat(share.text).isEqualTo("Mon Sep 14 — I'm in meetings:\n• 9:00 – 9:30 AM\nFree the rest of the day.")
    }

    @Test
    fun shareDay_recordsSharedAtAndTheMergedBusyRanges() = runTest {
        val dayPlanDao = FakeDayPlanDao()
        val changeSnapshotDao = FakeChangeSnapshotDao()
        val state = CalendarGrantedAppState.copy(
            eventsByDay = mapOf(today to DayEvents(today, listOf(standup), Instant.EPOCH)),
            dayPlans = mapOf(today to DayPlan(today, selected = mapOf(standup.key to selected(standup)))),
        )
        val effect = object : ShareDaySideEffects {}.shareDay(dayPlanDao, changeSnapshotDao, clock)

        effect.output(ShareDay(today), state = state).toList()

        val plan = dayPlanDao.plansFlow.value.single()
        assertThat(plan.sharedAt).isEqualTo(now.toEpochMilli())
        assertThat(decodeBusyRanges(plan.sharedSnapshot!!)).isEqualTo(listOf(BusyRange(standup.begin, standup.end)))
    }

    @Test
    fun shareDay_recordsAChangeSnapshotOfEveryEventOnTheDay_selectedOrNot() = runTest {
        val dayPlanDao = FakeDayPlanDao()
        val changeSnapshotDao = FakeChangeSnapshotDao()
        val state = CalendarGrantedAppState.copy(
            eventsByDay = mapOf(today to DayEvents(today, listOf(standup, dentist), Instant.EPOCH)),
            dayPlans = mapOf(today to DayPlan(today, selected = mapOf(standup.key to selected(standup)))),
        )
        val effect = object : ShareDaySideEffects {}.shareDay(dayPlanDao, changeSnapshotDao, clock)

        effect.output(ShareDay(today), state = state).toList()

        val snapshot = changeSnapshotDao.forDate(today)!!
        assertThat(snapshot.takenAt).isEqualTo(now.toEpochMilli())
        val events = decodeChangeSnapshotEvents(snapshot.eventsJson)
        assertThat(events).hasSize(2)
        assertThat(events.single { it.eventId == 1L }.selected).isTrue()
        assertThat(events.single { it.eventId == 2L }.selected).isEqualTo(false)
    }

    @Test
    fun shareDay_withNothingSelected_stillSharesAndRecordsAnEmptySnapshot() = runTest {
        val dayPlanDao = FakeDayPlanDao()
        val changeSnapshotDao = FakeChangeSnapshotDao()

        val output = object : ShareDaySideEffects {}.shareDay(dayPlanDao, changeSnapshotDao, clock)
            .output(ShareDay(today), state = CalendarGrantedAppState).toList()

        val share = (output.single() as SetPendingShare).share
        assertThat(share.text).isEqualTo("Mon Sep 14 — I'm in meetings:\nNo meetings today.")
        assertThat(decodeBusyRanges(dayPlanDao.plansFlow.value.single().sharedSnapshot!!)).isEqualTo(emptyList<BusyRange>())
    }

    @Test
    fun markNotShared_clearsSharedAtAndTheChangeSnapshot() = runTest {
        val dayPlanDao = FakeDayPlanDao(plans = listOf(DayPlanEntity(today, sharedAt = now.toEpochMilli())))
        val changeSnapshotDao = FakeChangeSnapshotDao(entities = listOf(ChangeSnapshotEntity(today, now.toEpochMilli(), "[]")))
        val effect = object : ShareDaySideEffects {}.markNotShared(dayPlanDao, changeSnapshotDao)

        effect.output(MarkNotShared(today), state = CalendarGrantedAppState).toList()

        assertThat(dayPlanDao.plansFlow.value.single().sharedAt).isNull()
        assertThat(changeSnapshotDao.forDate(today)).isNull()
    }
}

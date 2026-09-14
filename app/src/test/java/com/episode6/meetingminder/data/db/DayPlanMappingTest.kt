package com.episode6.meetingminder.data.db

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.DayPlan
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.RsvpState
import com.episode6.meetingminder.model.SelectedEvent
import com.episode6.meetingminder.model.testCalendarEvent
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DayPlanMappingTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val tomorrow = today.plusDays(1)

    private fun selectedEventEntity(
        date: LocalDate = today,
        eventId: Long = 1,
        instanceTime: Long = 0,
        title: String = "Standup",
        beginMillis: Long = 1_000,
        endMillis: Long = 2_000,
        alarmId: Long? = null,
        alarmAt: Long? = null,
        rsvpState: RsvpState = RsvpState.NOT_APPLICABLE,
        rsvpEventId: Long? = null,
    ) = SelectedEventEntity(
        date = date,
        eventId = eventId,
        instanceTime = instanceTime,
        title = title,
        beginMillis = beginMillis,
        endMillis = endMillis,
        alarmId = alarmId,
        alarmAt = alarmAt,
        rsvpState = rsvpState,
        rsvpEventId = rsvpEventId,
    )

    @Test
    fun buildDayPlans_groupsSelectionsByDate_andMergesInThePlanRow() {
        val standup = selectedEventEntity(eventId = 1, title = "Standup")
        val dentist = selectedEventEntity(eventId = 2, title = "Dentist")
        val tomorrowEvent = selectedEventEntity(date = tomorrow, eventId = 3, title = "Later")
        val plan = DayPlanEntity(date = today, alarmsSetAt = 5_000, sharedAt = null)

        val result = buildDayPlans(plans = listOf(plan), selections = listOf(standup, dentist, tomorrowEvent))

        assertThat(result.keys).containsOnly(today, tomorrow)
        assertThat(result.getValue(today)).isEqualTo(
            DayPlan(
                date = today,
                selected = mapOf(
                    EventKey(1, 0) to standup.toSelectedEventForTest(),
                    EventKey(2, 0) to dentist.toSelectedEventForTest(),
                ),
                alarmsSetAt = Instant.ofEpochMilli(5_000),
            ),
        )
        assertThat(result.getValue(tomorrow)).isEqualTo(
            DayPlan(date = tomorrow, selected = mapOf(EventKey(3, 0) to tomorrowEvent.toSelectedEventForTest())),
        )
    }

    @Test
    fun buildDayPlans_keysTheArmedRowsByDay_ignoringOnesNoLongerScheduled() {
        fun alarm(date: LocalDate, eventId: Long, state: AlarmState = AlarmState.SCHEDULED) = ScheduledAlarmEntity(
            alarmId = eventId, date = date, eventId = eventId, instanceTime = 0, fireAt = 700, title = "Event $eventId",
            beginMillis = 1_000, endMillis = 2_000, soundIndex = 0, state = state,
        )
        val standup = selectedEventEntity(eventId = 1, alarmId = 1, alarmAt = 700)

        // event 2's selection was removed after it was armed; event 3 fired; tomorrow has only an armed row
        val result = buildDayPlans(
            plans = emptyList(),
            selections = listOf(standup),
            scheduled = listOf(alarm(today, 1), alarm(today, 2), alarm(today, 3, AlarmState.FIRED), alarm(tomorrow, 4)),
        )

        assertThat(result.getValue(today)).isEqualTo(
            DayPlan(
                date = today,
                selected = mapOf(EventKey(1, 0) to standup.toSelectedEventForTest()),
                armedKeys = setOf(EventKey(1, 0), EventKey(2, 0)),
            ),
        )
        assertThat(result.getValue(tomorrow)).isEqualTo(DayPlan(date = tomorrow, armedKeys = setOf(EventKey(4, 0))))
    }

    @Test
    fun buildDayPlans_aSnoozedAlarmIsStillArmed() {
        val snoozed = ScheduledAlarmEntity(
            alarmId = 1, date = today, eventId = 1, instanceTime = 0, fireAt = 700, title = "Standup",
            beginMillis = 1_000, endMillis = 2_000, soundIndex = 0, state = AlarmState.SNOOZED,
        )

        val result = buildDayPlans(plans = emptyList(), selections = emptyList(), scheduled = listOf(snoozed))

        assertThat(result).isEqualTo(mapOf(today to DayPlan(date = today, armedKeys = setOf(EventKey(1, 0)))))
    }

    @Test
    fun buildDayPlans_carriesTheRsvpColumnsThrough() {
        val accepted = selectedEventEntity(eventId = 1, alarmId = 1, alarmAt = 700, rsvpState = RsvpState.ACCEPTED_LOCALLY, rsvpEventId = 1_000)

        val result = buildDayPlans(plans = emptyList(), selections = listOf(accepted))

        assertThat(result.getValue(today).selected.getValue(EventKey(1, 0))).isEqualTo(
            SelectedEvent(
                key = EventKey(1, 0), title = "Standup", begin = Instant.ofEpochMilli(1_000), end = Instant.ofEpochMilli(2_000),
                alarmId = 1, alarmAt = Instant.ofEpochMilli(700), rsvpState = RsvpState.ACCEPTED_LOCALLY, rsvpEventId = 1_000,
            ),
        )
    }

    @Test
    fun buildDayPlans_aDayPlanRowWithNoSelections_stillAppears() {
        val plan = DayPlanEntity(date = today, alarmsSetAt = null, sharedAt = 9_000)

        val result = buildDayPlans(plans = listOf(plan), selections = emptyList())

        assertThat(result).isEqualTo(mapOf(today to DayPlan(date = today, sharedAt = Instant.ofEpochMilli(9_000))))
    }

    @Test
    fun buildDayPlans_nothingAtAll_isEmpty() {
        assertThat(buildDayPlans(emptyList(), emptyList())).isEmpty()
    }

    @Test
    fun toSelectedEventEntity_copiesTheKeyTitleAndTimes_unarmedAndNotRsvpd() {
        val event: CalendarEvent = testCalendarEvent(
            id = 7,
            begin = Instant.ofEpochMilli(10_000),
            end = Instant.ofEpochMilli(20_000),
            title = "Design review",
        )

        val entity = event.toSelectedEventEntity(today)

        assertThat(entity).isEqualTo(
            SelectedEventEntity(
                date = today,
                eventId = event.key.eventId,
                instanceTime = event.key.instanceTime,
                title = "Design review",
                beginMillis = 10_000,
                endMillis = 20_000,
            ),
        )
        assertThat(entity.alarmId).isNull()
        assertThat(entity.rsvpState).isEqualTo(RsvpState.NOT_APPLICABLE)
    }

    @Test
    fun buildDayPlans_decodesTheSharedSnapshotJson() {
        val busyRanges = listOf(BusyRange(Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000)))
        val plan = DayPlanEntity(date = today, sharedAt = 5_000, sharedSnapshot = encodeBusyRanges(busyRanges))

        val result = buildDayPlans(plans = listOf(plan), selections = emptyList())

        assertThat(result.getValue(today).sharedSnapshot).isEqualTo(busyRanges)
    }

    @Test
    fun encodeThenDecodeBusyRanges_roundTrips() {
        val ranges = listOf(
            BusyRange(Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000)),
            BusyRange(Instant.ofEpochMilli(3_000), Instant.ofEpochMilli(4_000)),
        )

        assertThat(decodeBusyRanges(encodeBusyRanges(ranges))).isEqualTo(ranges)
    }

    @Test
    fun encodeBusyRanges_ofAnEmptyList_decodesBackToEmpty() {
        assertThat(decodeBusyRanges(encodeBusyRanges(emptyList()))).isEmpty()
    }
}

private fun SelectedEventEntity.toSelectedEventForTest(): SelectedEvent = SelectedEvent(
    key = key,
    title = title,
    begin = Instant.ofEpochMilli(beginMillis),
    end = Instant.ofEpochMilli(endMillis),
    alarmId = alarmId,
    alarmAt = alarmAt?.let(Instant::ofEpochMilli),
)

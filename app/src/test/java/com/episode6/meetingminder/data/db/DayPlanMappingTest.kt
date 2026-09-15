package com.episode6.meetingminder.data.db

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.DayPlan
import com.episode6.meetingminder.model.EventKey
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
    ) = SelectedEventEntity(
        date = date,
        eventId = eventId,
        instanceTime = instanceTime,
        title = title,
        beginMillis = beginMillis,
        endMillis = endMillis,
        alarmId = alarmId,
        alarmAt = alarmAt,
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
        assertThat(entity.rsvpState).isEqualTo("NOT_APPLICABLE")
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

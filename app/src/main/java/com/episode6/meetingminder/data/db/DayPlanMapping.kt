package com.episode6.meetingminder.data.db

import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.DayPlan
import com.episode6.meetingminder.model.SelectedEvent
import com.episode6.meetingminder.model.TEST_ALARM_EVENT_ID
import com.episode6.meetingminder.model.isSyntheticAlarmEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/**
 * [DayPlanEntity]/[SelectedEventEntity] rows plus the armed (`SCHEDULED`/`SNOOZED`)
 * [ScheduledAlarmEntity] rows into the `AppState.dayPlans` map (`ObserveDayPlansSideEffects`).
 *
 * The Settings "Test alarm" row ([TEST_ALARM_EVENT_ID], `TestAlarmSideEffects`) is armed
 * independently of any selection, so it is excluded from [DayPlan.armedKeys] here — leaving
 * it in would make `DayViewModel.toFabState()` show today's "Clear alarms" FAB with
 * nothing selected while the test alarm is scheduled or snoozed.
 */
internal fun buildDayPlans(
    plans: List<DayPlanEntity>,
    selections: List<SelectedEventEntity>,
    scheduled: List<ScheduledAlarmEntity> = emptyList(),
): Map<LocalDate, DayPlan> {
    val plansByDate = plans.associateBy { it.date }
    val selectionsByDate = selections.groupBy { it.date }
    val armedByDate = scheduled
        .filter { it.state.armed && !isSyntheticAlarmEvent(it.eventId) }
        .groupBy({ it.date }, { it.key })
    val dates = plansByDate.keys + selectionsByDate.keys + armedByDate.keys
    return dates.associateWith { date ->
        val plan = plansByDate[date]
        DayPlan(
            date = date,
            selected = selectionsByDate[date].orEmpty().associate { it.key to it.toSelectedEvent() },
            armedKeys = armedByDate[date].orEmpty().toSet(),
            alarmsSetAt = plan?.alarmsSetAt?.let(Instant::ofEpochMilli),
            sharedAt = plan?.sharedAt?.let(Instant::ofEpochMilli),
            sharedSnapshot = plan?.sharedSnapshot?.let(::decodeBusyRanges),
        )
    }
}

@Serializable
private data class BusyRangeDto(val beginMillis: Long, val endMillis: Long)

private val BusyRangeJson = Json { ignoreUnknownKeys = true }

/** [DayPlanEntity.sharedSnapshot]: the merged busy ranges a share sent, as JSON. */
internal fun encodeBusyRanges(ranges: List<BusyRange>): String =
    BusyRangeJson.encodeToString(ranges.map { BusyRangeDto(it.begin.toEpochMilli(), it.end.toEpochMilli()) })

internal fun decodeBusyRanges(json: String): List<BusyRange> =
    BusyRangeJson.decodeFromString<List<BusyRangeDto>>(json).map { BusyRange(Instant.ofEpochMilli(it.beginMillis), Instant.ofEpochMilli(it.endMillis)) }

private fun SelectedEventEntity.toSelectedEvent(): SelectedEvent = SelectedEvent(
    key = key,
    title = title,
    begin = Instant.ofEpochMilli(beginMillis),
    end = Instant.ofEpochMilli(endMillis),
    alarmId = alarmId,
    alarmAt = alarmAt?.let(Instant::ofEpochMilli),
    rsvpState = rsvpState,
    rsvpEventId = rsvpEventId,
)

/** [CalendarEvent] copied into a fresh, unarmed, un-RSVP'd [SelectedEventEntity] for [date] (`ToggleEvent`). */
internal fun CalendarEvent.toSelectedEventEntity(date: LocalDate): SelectedEventEntity = SelectedEventEntity(
    date = date,
    eventId = key.eventId,
    instanceTime = key.instanceTime,
    title = title,
    beginMillis = begin.toEpochMilli(),
    endMillis = end.toEpochMilli(),
)

package com.episode6.meetingminder.data.db

import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.DayPlan
import com.episode6.meetingminder.model.SelectedEvent
import java.time.Instant
import java.time.LocalDate

/** [DayPlanEntity]/[SelectedEventEntity] rows into the `AppState.dayPlans` map (`ObserveDayPlansSideEffects`). */
internal fun buildDayPlans(plans: List<DayPlanEntity>, selections: List<SelectedEventEntity>): Map<LocalDate, DayPlan> {
    val plansByDate = plans.associateBy { it.date }
    val selectionsByDate = selections.groupBy { it.date }
    val dates = plansByDate.keys + selectionsByDate.keys
    return dates.associateWith { date ->
        val plan = plansByDate[date]
        DayPlan(
            date = date,
            selected = selectionsByDate[date].orEmpty().associate { it.key to it.toSelectedEvent() },
            alarmsSetAt = plan?.alarmsSetAt?.let(Instant::ofEpochMilli),
            sharedAt = plan?.sharedAt?.let(Instant::ofEpochMilli),
        )
    }
}

private fun SelectedEventEntity.toSelectedEvent(): SelectedEvent = SelectedEvent(
    key = key,
    title = title,
    begin = Instant.ofEpochMilli(beginMillis),
    end = Instant.ofEpochMilli(endMillis),
    alarmId = alarmId,
    alarmAt = alarmAt?.let(Instant::ofEpochMilli),
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

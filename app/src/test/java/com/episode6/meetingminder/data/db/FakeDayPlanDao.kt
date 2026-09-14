package com.episode6.meetingminder.data.db

import com.episode6.meetingminder.model.RsvpState
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

/** In-memory [DayPlanDao] for side-effect tests: no Room, just two observable lists. */
internal class FakeDayPlanDao(
    plans: List<DayPlanEntity> = emptyList(),
    selections: List<SelectedEventEntity> = emptyList(),
) : DayPlanDao {
    val plansFlow = MutableStateFlow(plans)
    val selectionsFlow = MutableStateFlow(selections)

    override fun observeDayPlans() = plansFlow
    override fun observeSelectedEvents() = selectionsFlow

    override suspend fun selectedEventsOn(date: LocalDate): List<SelectedEventEntity> =
        selectionsFlow.value.filter { it.date == date }

    override suspend fun upsertDayPlan(entity: DayPlanEntity) {
        plansFlow.value = plansFlow.value.filterNot { it.date == entity.date } + entity
    }

    override suspend fun ensureDayPlan(date: LocalDate) {
        if (plansFlow.value.none { it.date == date }) plansFlow.value += DayPlanEntity(date)
    }

    override suspend fun setAlarmsSetAt(date: LocalDate, alarmsSetAt: Long?) {
        plansFlow.value = plansFlow.value.map { if (it.date == date) it.copy(alarmsSetAt = alarmsSetAt) else it }
    }

    override suspend fun upsertSelectedEvent(entity: SelectedEventEntity) {
        selectionsFlow.value = selectionsFlow.value.filterNot { it.date == entity.date && it.key == entity.key } + entity
    }

    override suspend fun deleteSelectedEvent(date: LocalDate, eventId: Long, instanceTime: Long): Int {
        val before = selectionsFlow.value
        val after = before.filterNot { it.date == date && it.eventId == eventId && it.instanceTime == instanceTime }
        selectionsFlow.value = after
        return before.size - after.size
    }

    override suspend fun armSelectedEvent(
        date: LocalDate,
        eventId: Long,
        instanceTime: Long,
        alarmId: Long?,
        alarmAt: Long?,
        title: String,
        beginMillis: Long,
        endMillis: Long,
    ) {
        selectionsFlow.value = selectionsFlow.value.map {
            if (it.date == date && it.eventId == eventId && it.instanceTime == instanceTime) {
                it.copy(alarmId = alarmId, alarmAt = alarmAt, title = title, beginMillis = beginMillis, endMillis = endMillis)
            } else {
                it
            }
        }
    }

    override suspend fun setRsvp(date: LocalDate, eventId: Long, instanceTime: Long, state: RsvpState, rsvpEventId: Long?) {
        selectionsFlow.value = selectionsFlow.value.map {
            if (it.date == date && it.eventId == eventId && it.instanceTime == instanceTime) {
                it.copy(rsvpState = state, rsvpEventId = rsvpEventId)
            } else {
                it
            }
        }
    }
}

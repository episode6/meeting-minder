package com.episode6.meetingminder.data.db

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

    override suspend fun upsertSelectedEvent(entity: SelectedEventEntity) {
        selectionsFlow.value = selectionsFlow.value.filterNot { it.date == entity.date && it.key == entity.key } + entity
    }

    override suspend fun deleteSelectedEvent(date: LocalDate, eventId: Long, instanceTime: Long) {
        selectionsFlow.value = selectionsFlow.value.filterNot {
            it.date == date && it.eventId == eventId && it.instanceTime == instanceTime
        }
    }
}

package com.episode6.meetingminder.model

import java.time.Instant
import java.time.LocalDate

/**
 * One day's plan (TODO.md §3.2/§3.4), read from Room's `day_plan` + `selected_event`
 * tables (the `ObserveDayPlans` side effect): which events are selected, and (from PR-8/9
 * on) whether alarms have been set and the day shared. A date absent from
 * [com.episode6.meetingminder.store.AppState.dayPlans] has no selections and no plan row —
 * equivalent to `DayPlan(date, selected = emptyMap())`.
 */
data class DayPlan(
    val date: LocalDate,
    val selected: Map<EventKey, SelectedEvent> = emptyMap(),
    /** Non-null once alarms have been set for [date] (PR-8); flips the FAB to "Share schedule". */
    val alarmsSetAt: Instant? = null,
    /** Non-null once the day's busy ranges have been shared (PR-9). */
    val sharedAt: Instant? = null,
)

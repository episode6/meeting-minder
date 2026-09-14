package com.episode6.meetingminder.model

import java.time.Instant
import java.time.LocalDate

/**
 * One day's plan (TODO.md §3.2/§3.4), read from Room's `day_plan` + `selected_event`
 * tables plus `scheduled_alarm` (the `ObserveDayPlans` side effect): which events are
 * selected, which have an alarm armed, and (from PR-8/9 on) whether alarms have been set
 * and the day shared. A date absent from
 * [com.episode6.meetingminder.store.AppState.dayPlans] has no selections and no plan row —
 * equivalent to `DayPlan(date, selected = emptyMap())`.
 */
data class DayPlan(
    val date: LocalDate,
    val selected: Map<EventKey, SelectedEvent> = emptyMap(),
    /**
     * Keys of the day's `SCHEDULED` alarm rows (PR-8). Normally a subset of [selected]'s
     * keys with an `alarmId`; the difference is an event deselected since its alarm was
     * armed, whose alarm rings until the next "Set alarms" tap cancels it — so the FAB
     * must stay reachable while this is non-empty even when [selected] is empty.
     */
    val armedKeys: Set<EventKey> = emptySet(),
    /** Non-null once alarms have been set for [date] (PR-8); flips the FAB to "Share schedule". */
    val alarmsSetAt: Instant? = null,
    /** Non-null once the day's busy ranges have been shared (PR-9); "Mark as not shared" clears it. */
    val sharedAt: Instant? = null,
    /** What went into the last share message (TODO.md §4.2), for PR-11's "Update:" re-share text. */
    val sharedSnapshot: List<BusyRange>? = null,
)

package com.episode6.meetingminder.model

import java.time.Instant

/**
 * One event the user has picked for [DayPlan.date] (TODO.md §3.4): "I'm going to this".
 * Denormalised on purpose, like the `selected_event` table it is read from — [title],
 * [begin] and [end] are copied from [CalendarEvent] at selection time rather than joined
 * against the provider, so the ringing screen and boot-reschedule path (PR-8) work without
 * touching it, and so a later reload can tell a selected event was *moved* (same [key],
 * different times) from [begin]/[end] alone.
 */
data class SelectedEvent(
    val key: EventKey,
    val title: String,
    val begin: Instant,
    val end: Instant,
    /** Pointer into `scheduled_alarm`; null until PR-8 schedules one. */
    val alarmId: Long? = null,
    val alarmAt: Instant? = null,
)

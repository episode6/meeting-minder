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
    /** Pointer into `scheduled_alarm`; null until "Set alarms" schedules one. */
    val alarmId: Long? = null,
    val alarmAt: Instant? = null,
    /** Where the RSVP stands (TODO.md §4.6); decided when the event is armed. */
    val rsvpState: RsvpState = RsvpState.NOT_APPLICABLE,
    /**
     * The event id the RSVP was written to — the new exception's id for a recurring
     * occurrence, the event's own id otherwise — so a later reload can check that row's
     * `DIRTY` flag. Null until the write has gone through.
     */
    val rsvpEventId: Long? = null,
)

package com.episode6.meetingminder.model

import java.time.Instant
import java.time.LocalDate

/**
 * One way a **shared** day has changed since it was shared (TODO.md §4.3), found by
 * `monitor/ChangeDetector` comparing the day's `change_snapshot` baseline with a fresh read.
 * Tagged with the [date] it belongs to, since several shared days can be monitored at once.
 * Only times are carried, never titles: the notification and the banner describe changes
 * the way the share text does.
 */
sealed interface ScheduleChange {
    val date: LocalDate
    val key: EventKey

    /** A meeting ([CalendarEvent.isMeeting]) that wasn't on the day when it was shared, selected or not. */
    data class New(override val date: LocalDate, override val key: EventKey, val begin: Instant, val end: Instant) : ScheduleChange

    /** An event that was selected at share time now has different times. */
    data class Moved(
        override val date: LocalDate,
        override val key: EventKey,
        val oldBegin: Instant,
        val oldEnd: Instant,
        val newBegin: Instant,
        val newEnd: Instant,
    ) : ScheduleChange

    /** An event that was selected at share time is gone from the day (or cancelled) before it started. */
    data class Cancelled(override val date: LocalDate, override val key: EventKey, val begin: Instant, val end: Instant) : ScheduleChange

    /** An event that was selected at share time has since been declined by the user in their calendar. */
    data class Declined(override val date: LocalDate, override val key: EventKey, val begin: Instant, val end: Instant) : ScheduleChange
}

/**
 * One event of a day as it was when the day was shared: `change_snapshot`'s baseline row
 * (TODO.md §3.4), `EventKey → (begin, end, cancelled, declinedByMe, selected, isMeeting)`.
 * [allDay] arrived with PR-11 (all-day events are never a change); rows written before
 * it read as timed.
 */
data class SnapshotEvent(
    val key: EventKey,
    val begin: Instant,
    val end: Instant,
    val cancelled: Boolean,
    val declinedByMe: Boolean,
    val selected: Boolean,
    val isMeeting: Boolean,
    val allDay: Boolean = false,
)

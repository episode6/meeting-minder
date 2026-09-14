package com.episode6.meetingminder.model

import java.time.Instant

/** The user's own response to an event (`Instances.SELF_ATTENDEE_STATUS`). */
enum class SelfStatus { ACCEPTED, TENTATIVE, DECLINED, NEEDS_ACTION, NONE }

/** `Instances.STATUS`; the repository never returns [CANCELED] rows, but the model keeps the case. */
enum class EventStatus { CONFIRMED, TENTATIVE, CANCELED }

/** `Instances.AVAILABILITY`; the provider's tentative availability counts as [BUSY]. */
enum class Availability { BUSY, FREE }

/**
 * One occurrence of an event on one day, as read from `CalendarContract.Instances`
 * (TODO.md §3.4). Never persisted whole: `selected_event` and `scheduled_alarm` copy the
 * fields they need (key, title, times) so the alarm paths work without the provider.
 */
data class CalendarEvent(
    /** Identity that survives moves (see [EventKey]). */
    val key: EventKey,
    /**
     * `Instances.EVENT_ID`: this occurrence's own `Events._ID`. Differs from [EventKey.eventId]
     * for an exception event (the key holds the series id). Every provider WRITE (the RSVP,
     * TODO.md §4.6) and the `DIRTY` check use this one.
     */
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val location: String?,
    val begin: Instant,
    val end: Instant,
    val allDay: Boolean,
    /** `Instances.DISPLAY_COLOR`: the event colour, falling back to the calendar's colour. */
    val color: Int,
    val selfStatus: SelfStatus,
    val status: EventStatus,
    val isOrganizer: Boolean,
    /** `Instances.HAS_ATTENDEE_DATA`; false means the calendar only syncs self-only data (Exchange, shared calendars). */
    val hasAttendeeData: Boolean,
    /** `Attendees` rows excluding `TYPE_RESOURCE`; always 0 when [hasAttendeeData] is false. */
    val humanAttendees: Int,
    val availability: Availability,
    /** Our own `Attendees` row (email == the calendar's `OWNER_ACCOUNT`), null if there is none. */
    val selfAttendeeId: Long?,
    /** `RRULE`/`RDATE` set and not already an exception; an exception is a plain event for RSVP purposes. */
    val isRecurringInstance: Boolean,
    /** `Calendars.CALENDAR_ACCESS_LEVEL`; `CAL_ACCESS_RESPOND` (300) is what an RSVP needs. */
    val calendarAccessLevel: Int,
) {
    /**
     * THE definition of "meeting" (TODO.md §3.4): a timed, un-cancelled, busy block that you
     * haven't declined and that involves someone else. "Someone else" is decided by the
     * attendee table when the provider has full attendee data (you plus at least one other
     * human), and by "was I invited at all" when the calendar only syncs self-only data.
     * Every count, share line and change-detection rule uses this; never restate it.
     */
    val isMeeting: Boolean
        get() = !allDay &&
            status != EventStatus.CANCELED &&
            selfStatus != SelfStatus.DECLINED &&
            availability == Availability.BUSY &&
            if (hasAttendeeData) humanAttendees >= 2 else selfStatus != SelfStatus.NONE
}

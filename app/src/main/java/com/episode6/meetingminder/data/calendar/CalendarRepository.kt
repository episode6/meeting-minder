package com.episode6.meetingminder.data.calendar

import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.CalendarInfo
import java.time.LocalDate

/** Which calendars an [CalendarRepository.eventsOn] query draws from. */
sealed interface CalendarFilter {
    /** Every calendar with `VISIBLE = 1`: what the user sees in Google Calendar (the default). */
    data object Visible : CalendarFilter

    /** Exactly these calendar ids, whether or not they are visible (the Settings override, PR-12). */
    data class Only(val calendarIds: Set<Long>) : CalendarFilter
}

/**
 * Read access to every calendar on every account (TODO.md §4.1), plus the one write the
 * app ever makes: the RSVP of [acceptInstance] (§4.6).
 *
 * The reads need `READ_CALENDAR` and the write `WRITE_CALENDAR`; without them the
 * provider throws `SecurityException`, so callers gate on the permission state first.
 */
interface CalendarRepository {

    /** Every calendar row, hidden and non-syncing ones included. */
    suspend fun calendars(): List<CalendarInfo>

    /**
     * Every un-deleted, un-cancelled instance that falls on [date] in the device's zone,
     * declined ones included, ordered all-day first and then by start time. Multi-day
     * events are returned with their true times; clamping to the day is the layout's job.
     */
    suspend fun eventsOn(date: LocalDate, filter: CalendarFilter = CalendarFilter.Visible): List<CalendarEvent>

    /**
     * Marks this one occurrence of [event] "Yes, going" on the calendar (TODO.md §4.6) —
     * never a whole series, never a decline. A recurring occurrence is answered by
     * inserting an exception for it; a plain event (or an occurrence that already is an
     * exception) by updating our own `Attendees` row. Only call it for an event whose
     * [rsvpDecision][com.episode6.meetingminder.model.rsvpDecision] is `PENDING`: the
     * exception insert crashes the provider for an event without a self-attendee row.
     *
     * Returns the id of the event whose attendee row now says accepted: the new
     * exception's id, or [CalendarEvent.eventId]. Throws when the provider refuses the
     * write (missing `WRITE_CALENDAR`, no row updated, insert returned nothing).
     */
    suspend fun acceptInstance(event: CalendarEvent): Long

    /**
     * Which of [eventIds] exist in `Events` with `DIRTY = 0`: their last local write (our
     * RSVP) has been uploaded by the account's sync adapter, so the selection that was
     * written to that id can be promoted to `SYNCED` (TODO.md §4.6). A deleted event is
     * not "synced", and on a `LOCAL` calendar nothing ever is. Queried on `Events`
     * directly because the `Instances` view does not expose `DIRTY`.
     */
    suspend fun syncedEventIds(eventIds: Collection<Long>): Set<Long>
}

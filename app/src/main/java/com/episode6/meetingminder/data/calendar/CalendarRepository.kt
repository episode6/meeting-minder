package com.episode6.meetingminder.data.calendar

import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.EventResponse
import java.time.LocalDate

/** Which calendars an [CalendarRepository.eventsOn] query draws from. */
sealed interface CalendarFilter {
    /** Every calendar with `VISIBLE = 1`: what the user sees in Google Calendar (the default). */
    data object Visible : CalendarFilter

    /** Exactly these calendar ids, whether or not they are visible (the Settings override, PR-12). */
    data class Only(val calendarIds: Set<Long>) : CalendarFilter
}

/**
 * Read access to every calendar on every account (TODO.md §4.1), plus the three kinds of
 * write the app ever makes: the RSVP of [respondToInstance] (§4.6) — "Yes, going" when
 * alarms are set, or the answer picked from a chip's long-press menu — and the busy-calendar
 * sync's [insertBusyBlock] and [deleteOwnEvent] (§4.7), which only ever touch events the
 * app itself inserted.
 *
 * The reads need `READ_CALENDAR` and the writes `WRITE_CALENDAR`; without them the
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
     * Answers this one occurrence of [event] with [response] on the calendar (TODO.md §4.6)
     * — never a whole series. A recurring occurrence is answered by inserting an exception
     * for it; a plain event (or an occurrence that already is an exception) by updating our
     * own `Attendees` row. Only call it for an event that has a self-attendee row
     * ([CalendarEvent.selfAttendeeId]; the automatic path checks
     * [rsvpDecision][com.episode6.meetingminder.model.rsvpDecision] and the menu
     * [canRespond][com.episode6.meetingminder.model.canRespond]): the exception insert
     * crashes the provider for an event without one.
     *
     * Returns the id of the event whose attendee row now carries the response: the new
     * exception's id, or [CalendarEvent.eventId]. Throws when the provider refuses the
     * write (missing `WRITE_CALENDAR`, no row updated, insert returned nothing).
     */
    suspend fun respondToInstance(event: CalendarEvent, response: EventResponse): Long

    /**
     * Which of [eventIds] exist in `Events` with `DIRTY = 0`: their last local write (our
     * RSVP) has been uploaded by the account's sync adapter, so the selection that was
     * written to that id can be promoted to `SYNCED` (TODO.md §4.6). A deleted event is
     * not "synced", and on a `LOCAL` calendar nothing ever is. Queried on `Events`
     * directly because the `Instances` view does not expose `DIRTY`.
     */
    suspend fun syncedEventIds(eventIds: Collection<Long>): Set<Long>

    /**
     * Inserts a bare busy block (TODO.md §4.7) on [calendarId] — title [busyBlockTitle] of
     * [firstName] (`busy`, or "Geoff busy"; the user's own name from Settings is the only
     * free text a block ever carries, which is why this takes the name and not a title),
     * [range]'s begin/end, availability busy, the device zone (the same one [eventsOn] reads in) as
     * the event time zone, and the app's package as the `CUSTOM_APP_PACKAGE` ownership
     * marker; never a description, location, colour, organizer, attendees, reminders or
     * recurrence — and returns the new `Events._ID`. A plain (non-sync-adapter) insert, so
     * the row is `DIRTY = 1` and the account's own sync adapter uploads it; the app never
     * talks to the network. Throws when the provider refuses the write (missing
     * `WRITE_CALENDAR`, insert returned nothing).
     */
    suspend fun insertBusyBlock(calendarId: Long, range: BusyRange, firstName: String): Long

    /**
     * Deletes an event the app itself inserted: an [insertBusyBlock] id recorded in
     * `busy_block`, and nothing else is ever passed here. A plain (non-sync-adapter) delete,
     * so the provider marks the row deleted and the account's sync adapter removes it
     * upstream. Returns false when the provider no longer had a row for the id, which
     * callers treat as done rather than as a failure. That is all `false` means: on a
     * synced calendar a row the user deleted in Google Calendar stays (as `DELETED = 1`)
     * until the adapter uploads that deletion, and a delete of it in that window still
     * answers true; only on a `LOCAL` calendar, where a delete removes the row outright, is
     * `false` the same thing as "the user deleted it by hand". Don't build on the
     * distinction.
     */
    suspend fun deleteOwnEvent(eventId: Long): Boolean
}

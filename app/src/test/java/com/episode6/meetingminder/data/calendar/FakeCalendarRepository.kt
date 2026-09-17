package com.episode6.meetingminder.data.calendar

import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.EventResponse
import java.time.LocalDate

/**
 * In-memory [CalendarRepository] for store and side-effect tests: seed [calendars] and
 * [events] per day, then assert on [eventQueries] to see what was loaded. Set [error] to
 * make every call throw it (e.g. a `SecurityException` for revoked calendar access).
 * [respondToInstance] records each event and answer in [responses] (the "Yes" ones also
 * in [accepted]) and answers with [rsvpEventIdFor] (the event's own id by default, as for
 * a plain event), or throws [acceptError]; [syncedEventIds] answers from [syncedIds].
 * [insertBusyBlock] records each call in [busyBlockInserts], hands out ids from
 * [nextBusyBlockId] and remembers them in [ownEvents] (or throws what [busyBlockInsertError]
 * returns for the range); [deleteOwnEvent] records the id in [deletedEventIds] and answers
 * whether it was in [ownEvents], removing it (or throws [deleteOwnEventError]).
 * [enableCalendarSync] records the id in [syncEnabledCalendarIds] and flips the matching
 * [calendars] entry's `syncEvents`, answering whether there was one.
 */
class FakeCalendarRepository(
    var calendars: List<CalendarInfo> = emptyList(),
    val events: MutableMap<LocalDate, List<CalendarEvent>> = mutableMapOf(),
) : CalendarRepository {

    /** Every `eventsOn` call, in order. */
    val eventQueries = mutableListOf<Pair<LocalDate, CalendarFilter>>()

    /** Every `respondToInstance` call, in order. */
    val responses = mutableListOf<Pair<CalendarEvent, EventResponse>>()

    /** The events answered [EventResponse.YES], in order (the automatic "Yes, going" writes). */
    val accepted: List<CalendarEvent> get() = responses.filter { it.second == EventResponse.YES }.map { it.first }

    /** Thrown from every call while non-null. */
    var error: Exception? = null

    /** Thrown from [respondToInstance] while non-null (the provider refusing the write). */
    var acceptError: Exception? = null

    /** The id [respondToInstance] reports the write went to; override to simulate a new exception's id. */
    var rsvpEventIdFor: (CalendarEvent) -> Long = { it.eventId }

    /** Event ids whose last local write the (pretend) sync adapter has uploaded. */
    val syncedIds = mutableSetOf<Long>()

    /** Every `syncedEventIds` call's argument, in order. */
    val syncQueries = mutableListOf<Set<Long>>()

    override suspend fun calendars(): List<CalendarInfo> {
        error?.let { throw it }
        return calendars
    }

    override suspend fun eventsOn(date: LocalDate, filter: CalendarFilter): List<CalendarEvent> {
        eventQueries += date to filter
        error?.let { throw it }
        val hidden = calendars.filterNot { it.visible }.map { it.id }.toSet()
        return events[date].orEmpty().filter { event ->
            when (filter) {
                CalendarFilter.Visible -> event.calendarId !in hidden
                is CalendarFilter.Only -> event.calendarId in filter.calendarIds
            }
        }
    }

    override suspend fun respondToInstance(event: CalendarEvent, response: EventResponse): Long {
        responses += event to response
        error?.let { throw it }
        acceptError?.let { throw it }
        return rsvpEventIdFor(event)
    }

    override suspend fun syncedEventIds(eventIds: Collection<Long>): Set<Long> {
        syncQueries += eventIds.toSet()
        error?.let { throw it }
        return eventIds.filter { it in syncedIds }.toSet()
    }

    /** One recorded `insertBusyBlock` call. */
    data class BusyBlockInsert(val calendarId: Long, val range: BusyRange, val firstName: String = "")

    /** Every `insertBusyBlock` call, in order, whether or not it threw. */
    val busyBlockInserts = mutableListOf<BusyBlockInsert>()

    /** The id the next successful `insertBusyBlock` returns; incremented per insert. */
    var nextBusyBlockId: Long = 5_000

    /** Thrown from `insertBusyBlock` for a range while non-null; lets a test fail the second insert of three. */
    var busyBlockInsertError: (BusyRange) -> Exception? = { null }

    /** The event ids the (pretend) calendar currently holds of what the app inserted; `deleteOwnEvent` answers from it. */
    val ownEvents = mutableSetOf<Long>()

    /** Every `deleteOwnEvent` call's id, in order, whether or not it threw. */
    val deletedEventIds = mutableListOf<Long>()

    /** Thrown from `deleteOwnEvent` while non-null. */
    var deleteOwnEventError: Exception? = null

    override suspend fun insertBusyBlock(calendarId: Long, range: BusyRange, firstName: String): Long {
        busyBlockInserts += BusyBlockInsert(calendarId, range, firstName)
        error?.let { throw it }
        busyBlockInsertError(range)?.let { throw it }
        return nextBusyBlockId++.also { ownEvents += it }
    }

    override suspend fun deleteOwnEvent(eventId: Long): Boolean {
        deletedEventIds += eventId
        error?.let { throw it }
        deleteOwnEventError?.let { throw it }
        return ownEvents.remove(eventId)
    }

    /** Every `enableCalendarSync` call's id, in order, whether or not it threw. */
    val syncEnabledCalendarIds = mutableListOf<Long>()

    override suspend fun enableCalendarSync(calendarId: Long): Boolean {
        syncEnabledCalendarIds += calendarId
        error?.let { throw it }
        if (calendars.none { it.id == calendarId }) return false
        calendars = calendars.map { if (it.id == calendarId) it.copy(syncEvents = true) else it }
        return true
    }
}

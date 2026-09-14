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
 * Read access to every calendar on every account (TODO.md §4.1). The one write, the
 * RSVP `acceptInstance(event)` of §4.6, joins this interface in PR-8b.
 *
 * Both calls need `READ_CALENDAR`; without it the provider throws `SecurityException`,
 * so callers gate on the permission state first (PR-4).
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
}

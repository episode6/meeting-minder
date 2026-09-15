package com.episode6.meetingminder.data.calendar

import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.CalendarInfo
import java.time.LocalDate

/**
 * In-memory [CalendarRepository] for store and side-effect tests: seed [calendars] and
 * [events] per day, then assert on [eventQueries] to see what was loaded.
 */
class FakeCalendarRepository(
    var calendars: List<CalendarInfo> = emptyList(),
    val events: MutableMap<LocalDate, List<CalendarEvent>> = mutableMapOf(),
) : CalendarRepository {

    /** Every `eventsOn` call, in order. */
    val eventQueries = mutableListOf<Pair<LocalDate, CalendarFilter>>()

    override suspend fun calendars(): List<CalendarInfo> = calendars

    override suspend fun eventsOn(date: LocalDate, filter: CalendarFilter): List<CalendarEvent> {
        eventQueries += date to filter
        val hidden = calendars.filterNot { it.visible }.map { it.id }.toSet()
        return events[date].orEmpty().filter { event ->
            when (filter) {
                CalendarFilter.Visible -> event.calendarId !in hidden
                is CalendarFilter.Only -> event.calendarId in filter.calendarIds
            }
        }
    }
}

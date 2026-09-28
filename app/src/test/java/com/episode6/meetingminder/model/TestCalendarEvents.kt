package com.episode6.meetingminder.model

import java.time.Instant

/**
 * A provider event for store / ViewModel tests. [withGuests] picks between an event with
 * guests (me plus another human, with my own attendee row) and one with no attendee rows at
 * all, which matters to the RSVP rules only: both are meetings, per
 * [CalendarEvent.isMeeting]. For an event that is *not* a meeting, copy it with
 * `availability = FREE`, a declined `selfStatus` or a cancelled `status`, or pass [allDay].
 */
internal fun testCalendarEvent(
    id: Long,
    begin: Instant,
    end: Instant,
    title: String = "Event $id",
    withGuests: Boolean = true,
    allDay: Boolean = false,
    calendarId: Long = 1,
    ownedByApp: Boolean = false,
) = CalendarEvent(
    key = EventKey(id, 0),
    eventId = id,
    calendarId = calendarId,
    title = title,
    location = null,
    begin = begin,
    end = end,
    allDay = allDay,
    color = 0xFFE65C00.toInt(),
    selfStatus = if (withGuests) SelfStatus.NEEDS_ACTION else SelfStatus.NONE,
    status = EventStatus.CONFIRMED,
    isOrganizer = false,
    hasAttendeeData = true,
    humanAttendees = if (withGuests) 2 else 0,
    availability = Availability.BUSY,
    selfAttendeeId = if (withGuests) id * 10 else null,
    isRecurringInstance = false,
    calendarAccessLevel = 700,
    ownedByApp = ownedByApp,
)

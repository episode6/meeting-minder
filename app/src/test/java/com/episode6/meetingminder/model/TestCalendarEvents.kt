package com.episode6.meetingminder.model

import java.time.Instant

/**
 * A provider event for store / ViewModel tests. [meeting] picks between a meeting (me plus
 * another human, per [CalendarEvent.isMeeting]) and a solo block with no attendees.
 */
internal fun testCalendarEvent(
    id: Long,
    begin: Instant,
    end: Instant,
    title: String = "Event $id",
    meeting: Boolean = true,
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
    selfStatus = if (meeting) SelfStatus.NEEDS_ACTION else SelfStatus.NONE,
    status = EventStatus.CONFIRMED,
    isOrganizer = false,
    hasAttendeeData = true,
    humanAttendees = if (meeting) 2 else 0,
    availability = Availability.BUSY,
    selfAttendeeId = if (meeting) id * 10 else null,
    isRecurringInstance = false,
    calendarAccessLevel = 700,
    ownedByApp = ownedByApp,
)

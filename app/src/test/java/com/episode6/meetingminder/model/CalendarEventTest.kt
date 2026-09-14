package com.episode6.meetingminder.model

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import java.time.Instant

/** One case per clause of the canonical `isMeeting` rule (TODO.md §3.4). */
class CalendarEventTest {

    private val meeting = CalendarEvent(
        key = EventKey(1, 0),
        eventId = 1,
        calendarId = 1,
        title = "Design review",
        location = null,
        begin = Instant.parse("2026-09-14T14:00:00Z"),
        end = Instant.parse("2026-09-14T15:00:00Z"),
        allDay = false,
        color = 0,
        selfStatus = SelfStatus.NEEDS_ACTION,
        status = EventStatus.CONFIRMED,
        isOrganizer = false,
        hasAttendeeData = true,
        humanAttendees = 2,
        availability = Availability.BUSY,
        selfAttendeeId = 7,
        isRecurringInstance = false,
        calendarAccessLevel = 700,
    )

    @Test
    fun meeting_withMeAndOneOtherHuman() {
        assertThat(meeting.isMeeting).isTrue()
    }

    @Test
    fun soloBlock_withFullAttendeeData_isNotAMeeting() {
        assertThat(meeting.copy(humanAttendees = 0, selfAttendeeId = null).isMeeting).isFalse()
        assertThat(meeting.copy(humanAttendees = 1).isMeeting).isFalse()
    }

    @Test
    fun selfOnlyData_isAMeetingWhenIWasInvited() {
        val selfOnly = meeting.copy(hasAttendeeData = false, humanAttendees = 0)
        assertThat(selfOnly.copy(selfStatus = SelfStatus.NEEDS_ACTION).isMeeting).isTrue()
        assertThat(selfOnly.copy(selfStatus = SelfStatus.ACCEPTED).isMeeting).isTrue()
        assertThat(selfOnly.copy(selfStatus = SelfStatus.TENTATIVE).isMeeting).isTrue()
        assertThat(selfOnly.copy(selfStatus = SelfStatus.NONE).isMeeting).isFalse()
    }

    @Test
    fun allDay_isNeverAMeeting() {
        assertThat(meeting.copy(allDay = true).isMeeting).isFalse()
    }

    @Test
    fun cancelled_isNotAMeeting() {
        assertThat(meeting.copy(status = EventStatus.CANCELED).isMeeting).isFalse()
        assertThat(meeting.copy(status = EventStatus.TENTATIVE).isMeeting).isTrue()
    }

    @Test
    fun declinedByMe_isNotAMeeting() {
        assertThat(meeting.copy(selfStatus = SelfStatus.DECLINED).isMeeting).isFalse()
    }

    @Test
    fun free_isNotAMeeting() {
        assertThat(meeting.copy(availability = Availability.FREE).isMeeting).isFalse()
    }
}

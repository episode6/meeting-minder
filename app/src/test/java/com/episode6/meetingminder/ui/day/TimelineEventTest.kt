package com.episode6.meetingminder.ui.day

import androidx.compose.ui.graphics.Color
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.prop
import com.episode6.meetingminder.model.Availability
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.SelfStatus
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class TimelineEventTest {

    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun timed_event_isReadInTheDeviceZone() {
        val chip = event().toTimelineEvent(newYork, selected = true, alarmAt = LocalTime.of(8, 55))

        assertThat(chip).isEqualTo(
            TimelineEvent(
                key = EventKey(42, 0),
                title = "Standup",
                location = "Meet",
                begin = LocalDateTime.of(2026, 9, 14, 9, 0),
                end = LocalDateTime.of(2026, 9, 14, 9, 30),
                color = Color(0xFFE65C00.toInt()),
                status = ChipStatus.Normal,
                selected = true,
                alarmAt = LocalTime.of(8, 55),
            ),
        )
        assertThat(chip.armed).isTrue()
        assertThat(chip.toggleable).isTrue()
    }

    @Test
    fun all_day_event_keepsItsDateInANegativeOffsetZone() {
        // all-day BEGIN is UTC midnight: 8 PM the evening before in New York
        val chip = event(
            allDay = true,
            begin = Instant.parse("2026-09-14T00:00:00Z"),
            end = Instant.parse("2026-09-15T00:00:00Z"),
        ).toTimelineEvent(newYork)

        assertThat(chip).prop(TimelineEvent::begin).isEqualTo(LocalDateTime.of(2026, 9, 14, 0, 0))
        assertThat(chip).prop(TimelineEvent::end).isEqualTo(LocalDateTime.of(2026, 9, 15, 0, 0))
    }

    @Test
    fun declined_by_me_isDeclinedAndNotToggleable() {
        val chip = event(selfStatus = SelfStatus.DECLINED).toTimelineEvent(newYork)

        assertThat(chip).prop(TimelineEvent::status).isEqualTo(ChipStatus.Declined)
        assertThat(chip.toggleable).isFalse()
    }

    @Test
    fun cancelled_rendersAsDeclined() {
        assertThat(event(status = EventStatus.CANCELED).toTimelineEvent(newYork))
            .prop(TimelineEvent::status).isEqualTo(ChipStatus.Declined)
    }

    @Test
    fun tentative_answerOrTentativeEvent() {
        assertThat(event(selfStatus = SelfStatus.TENTATIVE).toTimelineEvent(newYork))
            .prop(TimelineEvent::status).isEqualTo(ChipStatus.Tentative)
        assertThat(event(status = EventStatus.TENTATIVE).toTimelineEvent(newYork))
            .prop(TimelineEvent::status).isEqualTo(ChipStatus.Tentative)
    }

    @Test
    fun armed_needsSelectionAndAnAlarmTime() {
        assertThat(event().toTimelineEvent(newYork, selected = true).armed).isFalse()
        assertThat(event().toTimelineEvent(newYork, alarmAt = LocalTime.NOON).armed).isFalse()
    }

    private fun event(
        allDay: Boolean = false,
        begin: Instant = Instant.parse("2026-09-14T13:00:00Z"),
        end: Instant = Instant.parse("2026-09-14T13:30:00Z"),
        selfStatus: SelfStatus = SelfStatus.ACCEPTED,
        status: EventStatus = EventStatus.CONFIRMED,
    ) = CalendarEvent(
        key = EventKey(42, 0),
        eventId = 42,
        calendarId = 1,
        title = "Standup",
        location = "Meet",
        begin = begin,
        end = end,
        allDay = allDay,
        color = 0xFFE65C00.toInt(),
        selfStatus = selfStatus,
        status = status,
        isOrganizer = false,
        hasAttendeeData = true,
        humanAttendees = 3,
        availability = Availability.BUSY,
        selfAttendeeId = 7,
        isRecurringInstance = false,
        calendarAccessLevel = 700,
    )
}

package com.episode6.meetingminder.ui.day

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.EventResponse
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.RsvpState
import com.episode6.meetingminder.model.SelfStatus
import com.episode6.meetingminder.model.canRespond
import com.episode6.meetingminder.model.response
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** How an event's own calendar state (not our selection) changes its chip (TODO.md §3.5). */
enum class ChipStatus {
    Normal,

    /** You answered "maybe", or the event itself is tentative: 40% fill while unselected. */
    Tentative,

    /** Declined by you or cancelled: dashed outline, strikethrough, not selectable. */
    Declined,
}

/** What the chip says about the RSVP that "Set alarms" sent for the event (TODO.md §4.6). */
enum class ChipRsvp {
    /** Nothing to show: not decided yet, still being written, or nothing to answer. */
    None,

    /** Our "Yes, going" reached the calendar: a small tick after the alarm time. */
    Sent,

    /** We couldn't answer (read-only calendar, invite to an alias, or the write failed): a subtle hint. */
    Failed,
}

/** [ChipRsvp] for a selection's stored [RsvpState]. */
fun RsvpState.toChipRsvp(): ChipRsvp = when (this) {
    RsvpState.ACCEPTED_LOCALLY, RsvpState.SYNCED -> ChipRsvp.Sent
    RsvpState.UNRESPONDABLE, RsvpState.FAILED -> ChipRsvp.Failed
    RsvpState.NOT_APPLICABLE, RsvpState.PENDING -> ChipRsvp.None
}

/** One chip on the timeline or in the all-day row: everything [EventChip] draws. */
@Immutable
data class TimelineEvent(
    val key: EventKey,
    val title: String,
    val location: String?,
    /** Wall-clock times in the device zone; the timeline clamps them to its day. */
    val begin: LocalDateTime,
    val end: LocalDateTime,
    /** An all-day event (read in UTC, [end] the exclusive midnight after its last day). */
    val allDay: Boolean = false,
    /** The calendar's colour (`Instances.DISPLAY_COLOR`); chips never use the theme colour. */
    val color: Color,
    val status: ChipStatus = ChipStatus.Normal,
    /** "I'm going to this" (PR-7 feeds it from the day plan). */
    val selected: Boolean = false,
    /** When this event's alarm rings, once alarms are set; null = not armed. */
    val alarmAt: LocalTime? = null,
    /** The outcome of the RSVP sent when the alarm was set. */
    val rsvp: ChipRsvp = ChipRsvp.None,
    /** Whether the long-press sheet offers "Yes / No / Maybe" ([canRespond]). */
    val respondable: Boolean = false,
    /** Your answer on the calendar as it stands, marked in the long-press sheet; null while unanswered. */
    val response: EventResponse? = null,
) {
    /** Declined and cancelled chips ignore taps; long-press (the sheet) still works. */
    val toggleable: Boolean get() = status != ChipStatus.Declined

    val armed: Boolean get() = selected && alarmAt != null
}

/** What one day's page of the timeline renders. */
@Immutable
data class DayTimelineState(
    val date: LocalDate,
    val allDayEvents: List<TimelineEvent> = emptyList(),
    /**
     * Must only hold events that overlap [date]: each is clamped to the day, so one that
     * doesn't intersect it would collapse to a floor-height chip at midnight or at the bottom.
     */
    val timedEvents: List<TimelineEvent> = emptyList(),
    /** The current time when [date] is today (draws the now-line and dims ended events), else null. */
    val now: LocalTime? = null,
)

/**
 * Maps a provider event onto its chip. All-day events store `BEGIN` as UTC midnight
 * (TODO.md §4.1), so they are read in UTC to keep their date; timed events use [zone].
 */
fun CalendarEvent.toTimelineEvent(
    zone: ZoneId,
    selected: Boolean = false,
    alarmAt: LocalTime? = null,
    rsvp: ChipRsvp = ChipRsvp.None,
): TimelineEvent {
    val readZone = if (allDay) ZoneOffset.UTC else zone
    return TimelineEvent(
        key = key,
        title = title,
        location = location,
        begin = LocalDateTime.ofInstant(begin, readZone),
        end = LocalDateTime.ofInstant(end, readZone),
        allDay = allDay,
        color = Color(color),
        status = when {
            status == EventStatus.CANCELED || selfStatus == SelfStatus.DECLINED -> ChipStatus.Declined
            status == EventStatus.TENTATIVE || selfStatus == SelfStatus.TENTATIVE -> ChipStatus.Tentative
            else -> ChipStatus.Normal
        },
        selected = selected,
        alarmAt = alarmAt,
        rsvp = rsvp,
        respondable = canRespond(this),
        response = selfStatus.response,
    )
}

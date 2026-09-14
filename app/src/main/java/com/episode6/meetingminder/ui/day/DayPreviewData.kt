package com.episode6.meetingminder.ui.day

import androidx.compose.ui.graphics.Color
import com.episode6.meetingminder.model.EventKey
import java.time.LocalDate
import java.time.LocalTime

/** A fixed date so previews (and the screenshots generated from them) never change. */
internal val PreviewDate: LocalDate = LocalDate.of(2026, 9, 14)

/** The day from renders 2 and 3, plus a packing stress test, for previews only. */
internal object PreviewEvents {
    const val FIRST_VISIBLE_HOUR = 7.75f

    private val work = Color(0xFFE65C00)
    private val personal = Color(0xFF6E5B7E)
    private val family = Color(0xFF7B5EA7)
    private val team = Color(0xFF8D6E63)
    private val focus = Color(0xFF0B8043)

    private fun at(hour: Int, minute: Int = 0) = PreviewDate.atTime(hour, minute)

    private fun event(
        id: Long,
        title: String,
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        color: Color = work,
        location: String? = null,
        status: ChipStatus = ChipStatus.Normal,
    ) = TimelineEvent(
        key = EventKey(eventId = id, instanceTime = 0),
        title = title,
        location = location,
        begin = at(from.first, from.second),
        end = at(to.first, to.second),
        color = color,
        status = status,
    )

    val planningWeek = TimelineEvent(
        key = EventKey(eventId = 1, instanceTime = 0),
        title = "Q3 planning week",
        location = null,
        begin = PreviewDate.minusDays(1).atStartOfDay(),
        end = PreviewDate.plusDays(4).atStartOfDay(),
        color = team,
    )
    val standup = event(2, "Daily standup", 9 to 0, 9 to 30)
    val designReview = event(3, "Design review: alarms flow", 10 to 0, 11 to 0, location = "Meet")
    val oneOnOne = event(4, "1:1 with Sam", 11 to 30, 12 to 30)
    val dentist = event(5, "Dentist", 12 to 0, 13 to 0, color = personal)
    val vendorSync = event(6, "Vendor sync", 14 to 0, 14 to 45, status = ChipStatus.Declined)
    val schoolPickup = event(7, "School pickup", 16 to 0, 17 to 0, color = family, location = "Family")

    private val now: LocalTime = LocalTime.of(8, 35)

    val busyDay = DayTimelineState(
        date = PreviewDate,
        allDayEvents = listOf(planningWeek),
        timedEvents = listOf(standup, designReview, oneOnOne, dentist, vendorSync, schoolPickup),
        now = now,
    )

    val selectingDay = busyDay.copy(
        timedEvents = listOf(
            standup.copy(selected = true),
            designReview,
            oneOnOne,
            dentist.copy(selected = true),
            vendorSync,
            schoolPickup,
        ),
    )

    // the two meetings got their "Yes, going" tick; the dentist is a solo block, nothing to answer
    val alarmsSetDay = busyDay.copy(
        timedEvents = listOf(
            standup.copy(selected = true, alarmAt = LocalTime.of(8, 55), rsvp = ChipRsvp.Sent),
            designReview.copy(selected = true, alarmAt = LocalTime.of(9, 55), rsvp = ChipRsvp.Sent),
            oneOnOne,
            dentist.copy(selected = true, alarmAt = LocalTime.of(11, 55)),
            vendorSync,
            schoolPickup,
        ),
    )

    val overlapsDay = DayTimelineState(
        date = PreviewDate,
        timedEvents = listOf(
            // expansion: Workshop reuses Sync A's column and spans the free one beside it
            event(10, "Offsite prep", 9 to 0, 12 to 0, color = focus),
            event(11, "Sync A", 9 to 0, 10 to 0),
            event(12, "Sync B", 9 to 0, 9 to 45, color = personal),
            event(13, "Workshop", 10 to 0, 12 to 0, color = family, location = "Room 4").copy(selected = true),
            // a chain of overlaps that only needs two columns
            event(14, "Chain 1", 13 to 0, 14 to 0),
            event(15, "Chain 2", 13 to 30, 14 to 30, color = personal),
            event(16, "Chain 3", 14 to 0, 15 to 0, color = focus),
            // a 5-minute chip keeps its 24dp floor and pushes its neighbour aside
            event(17, "Quick check", 15 to 30, 15 to 35),
            event(18, "Tentative sync", 15 to 40, 16 to 10, color = personal, status = ChipStatus.Tentative),
        ),
        now = LocalTime.of(11, 10),
    )
}

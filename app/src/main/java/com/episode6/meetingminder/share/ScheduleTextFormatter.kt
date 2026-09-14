package com.episode6.meetingminder.share

import com.episode6.meetingminder.model.BusyRange
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the share text (TODO.md §4.2): a plain, title-free list of busy ranges for one
 * day. Deliberately plain JVM (no Android import) so it stays unit-testable without
 * Robolectric, and deliberately **not locale-aware** — like
 * `com.episode6.meetingminder.ui.day.TimelineTimeFormat` elsewhere in the codebase, a
 * fixed 12-hour `en`-style clock was chosen over threading a device locale through pure
 * logic (declined in PR-5/6 for the same reason). The literal English phrases below are
 * the message itself, not UI chrome, so they live here rather than as `strings.xml`
 * resources.
 */
object ScheduleTextFormatter {

    private val HeaderDateFormatter = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
    private val TimeFormatter = DateTimeFormatter.ofPattern("h:mm", Locale.US)

    /**
     * Merges adjacent or overlapping [ranges] into the fewest contiguous spans (TODO.md
     * §4.2: "9:00–9:30 + 9:30–10:00 → 9:00–10:00"). Order of the input doesn't matter;
     * the result is sorted by [BusyRange.begin].
     */
    fun merge(ranges: List<BusyRange>): List<BusyRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.begin }
        val merged = mutableListOf(sorted.first())
        for (range in sorted.drop(1)) {
            val last = merged.last()
            if (range.begin <= last.end) {
                if (range.end > last.end) merged[merged.lastIndex] = last.copy(end = range.end)
            } else {
                merged += range
            }
        }
        return merged
    }

    /**
     * The message for [date]: merges [busyRanges] (idempotent, so raw selected times and
     * an already merged list — what [selectedBusyRanges] hands the share — both work),
     * then either the normal share —
     * ```
     * Mon Sep 14 — I'm in meetings:
     * • 9:00 – 9:30 AM
     * Free the rest of the day.
     * ```
     * ("No meetings today." replaces the bullets when [busyRanges] is empty) — or, when
     * [isUpdate] is set (the "changed since you shared" re-share, PR-11), just —
     * ```
     * Update:
     * • 9:00 – 9:30 AM
     * ```
     * with no header date or closing line. Times are in [zone], 12-hour with AM/PM shown
     * only where it changes within a range (an overnight range shows both). Ranges are
     * **not** clipped to [date]: a midnight-spanning event can be selected on either of its
     * days, and whichever page it was picked on shares its whole span ("11:00 PM – 1:00 AM"
     * under Tuesday's header when it was picked on Tuesday). Readers of `shared_snapshot`
     * (the change banner, `monitor/ChangeDetector`) compare the same unclipped ranges.
     */
    fun format(date: LocalDate, busyRanges: List<BusyRange>, zone: ZoneId, isUpdate: Boolean = false): String {
        val merged = merge(busyRanges)
        return buildString {
            if (isUpdate) {
                append("Update:")
                for (range in merged) append("\n• ").append(formatRange(range, zone))
            } else {
                append(date.format(HeaderDateFormatter)).append(" — I'm in meetings:")
                if (merged.isEmpty()) {
                    append("\nNo meetings today.")
                } else {
                    for (range in merged) append("\n• ").append(formatRange(range, zone))
                    append("\nFree the rest of the day.")
                }
            }
        }
    }

    /** One range as the share text writes it ("9:00 – 9:30 AM"); the schedule-changed notification and banner use it too. */
    fun formatRange(range: BusyRange, zone: ZoneId): String {
        val begin = LocalDateTime.ofInstant(range.begin, zone)
        val end = LocalDateTime.ofInstant(range.end, zone)
        val beginPeriod = begin.toLocalTime().period()
        val endPeriod = end.toLocalTime().period()
        val beginText = begin.toLocalTime().format(TimeFormatter)
        val endText = end.toLocalTime().format(TimeFormatter)
        return if (beginPeriod == endPeriod) {
            "$beginText – $endText $endPeriod"
        } else {
            "$beginText $beginPeriod – $endText $endPeriod"
        }
    }

    private fun LocalTime.period(): String = if (hour < 12) "AM" else "PM"
}

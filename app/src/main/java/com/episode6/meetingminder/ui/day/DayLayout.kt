package com.episode6.meetingminder.ui.day

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.max

/** Minutes in one timeline day; an event running past midnight is clamped to this. */
const val MINUTES_PER_DAY: Int = 24 * 60

/**
 * The part of one day an event covers, in minutes from local midnight: `[start, end)`,
 * `0 <= start <= end <= MINUTES_PER_DAY`.
 */
data class MinuteSpan(val start: Int, val end: Int) {
    init {
        require(start in 0..end && end <= MINUTES_PER_DAY) { "invalid span [$start, $end)" }
    }

    val duration: Int get() = end - start

    /** Strict overlap: back-to-back spans (one ends exactly when the other starts) don't overlap. */
    fun overlaps(other: MinuteSpan): Boolean = start < other.end && other.start < end
}

/**
 * Where one timed event sits horizontally: it starts in column [col] (0-based) of the
 * [colCount] columns its overlap cluster needs, and is [colSpan] columns wide.
 */
data class PositionedEvent(val col: Int, val colSpan: Int, val colCount: Int)

/**
 * Clamps an event to [date] for layout (TODO.md §4.1): a multi-day or midnight-spanning
 * event starts at 00:00 if it began on an earlier day and ends at 24:00 if it ends on a
 * later one. The times are wall-clock times in the device zone, so a DST day still lays
 * out on a 24-hour grid.
 */
fun minuteSpanOn(date: LocalDate, begin: LocalDateTime, end: LocalDateTime): MinuteSpan {
    val start = when {
        begin.toLocalDate() < date -> 0
        begin.toLocalDate() > date -> MINUTES_PER_DAY
        else -> begin.toLocalTime().toSecondOfDay() / 60
    }
    val finish = when {
        end.toLocalDate() > date -> MINUTES_PER_DAY
        end.toLocalDate() < date -> 0
        else -> end.toLocalTime().toSecondOfDay() / 60
    }
    return MinuteSpan(start, max(start, finish))
}

/**
 * Packs a day's timed events into side-by-side columns, Google-Calendar style
 * (TODO.md §3.5). Returns one [PositionedEvent] per input span, **in input order**.
 *
 * 1. Sort by start ascending, then end descending (a longer event claims the left column).
 * 2. Cluster connected overlaps: a cluster ends at the first event starting at or after
 *    the latest end seen so far. Each cluster is laid out independently, so an event
 *    only gets narrow when something actually overlaps it (directly or through a chain).
 * 3. Greedy first-fit: each event takes the leftmost column whose last event has ended
 *    by its start (so back-to-back events share a column); otherwise a new column opens.
 * 4. Expand each event rightward through every neighbouring column that has nothing
 *    overlapping it.
 *
 * Overlap is decided on the *visual* extent: every span is treated as at least
 * [minDurationMinutes] long (the chip's minimum height expressed in minutes), so a
 * 5-minute event whose 24dp chip would cover the next event's top gets its own column.
 * Zero-length events are treated as one minute long for the same reason.
 */
fun layoutDay(spans: List<MinuteSpan>, minDurationMinutes: Int = 0): List<PositionedEvent> {
    val visual = spans.map { it.start to max(it.end, it.start + max(minDurationMinutes, 1)) }
    fun overlaps(a: Int, b: Int) = visual[a].first < visual[b].second && visual[b].first < visual[a].second

    val result = arrayOfNulls<PositionedEvent>(spans.size)
    val order = spans.indices.sortedWith(compareBy<Int> { visual[it].first }.thenByDescending { visual[it].second })

    fun packCluster(cluster: List<Int>) {
        // each column holds indices in start order and never overlapping, so its last
        // entry always has the latest end
        val columns = mutableListOf<MutableList<Int>>()
        val columnOf = HashMap<Int, Int>(cluster.size)
        for (event in cluster) {
            val free = columns.indexOfFirst { visual[it.last()].second <= visual[event].first }
            val col = if (free >= 0) free else columns.size.also { columns += mutableListOf<Int>() }
            columns[col] += event
            columnOf[event] = col
        }
        for (event in cluster) {
            val col = columnOf.getValue(event)
            var span = 1
            while (col + span < columns.size && columns[col + span].none { overlaps(it, event) }) span++
            result[event] = PositionedEvent(col = col, colSpan = span, colCount = columns.size)
        }
    }

    val cluster = mutableListOf<Int>()
    var clusterEnd = Int.MIN_VALUE
    for (event in order) {
        if (cluster.isNotEmpty() && visual[event].first >= clusterEnd) {
            packCluster(cluster)
            cluster.clear()
            clusterEnd = Int.MIN_VALUE
        }
        cluster += event
        clusterEnd = max(clusterEnd, visual[event].second)
    }
    if (cluster.isNotEmpty()) packCluster(cluster)

    return result.map { checkNotNull(it) }
}

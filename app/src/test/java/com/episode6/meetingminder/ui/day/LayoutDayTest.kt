package com.episode6.meetingminder.ui.day

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class LayoutDayTest {

    @Test
    fun empty_day() {
        assertThat(layoutDay(emptyList())).isEmpty()
    }

    @Test
    fun no_overlap_everyEventIsFullWidth() {
        val positions = layoutDay(listOf(span("9:00", "9:30"), span("10:00", "11:00"), span("14:00", "14:45")))

        assertThat(positions).containsExactly(FULL, FULL, FULL)
    }

    @Test
    fun back_to_back_withoutAnythingElse_areSeparateFullWidthEvents() {
        val positions = layoutDay(listOf(span("9:00", "10:00"), span("10:00", "11:00")))

        assertThat(positions).containsExactly(FULL, FULL)
    }

    @Test
    fun two_way_overlap_splitsInHalf_longerOrEarlierOnTheLeft() {
        // render 2: "1:1 with Sam" 11:30–12:30 beside "Dentist" 12:00–1:00
        val positions = layoutDay(listOf(span("12:00", "13:00"), span("11:30", "12:30")))

        assertThat(positions).containsExactly(
            PositionedEvent(col = 1, colSpan = 1, colCount = 2),
            PositionedEvent(col = 0, colSpan = 1, colCount = 2),
        )
    }

    @Test
    fun same_start_theLongerEventTakesTheLeftColumn() {
        val positions = layoutDay(listOf(span("9:00", "9:30"), span("9:00", "11:00")))

        assertThat(positions).containsExactly(
            PositionedEvent(col = 1, colSpan = 1, colCount = 2),
            PositionedEvent(col = 0, colSpan = 1, colCount = 2),
        )
    }

    @Test
    fun chain_of_overlaps_isOneClusterButOnlyNeedsTwoColumns() {
        // A overlaps B, B overlaps C, A and C don't touch: C reuses A's column
        val positions = layoutDay(listOf(span("9:00", "10:00"), span("9:30", "10:30"), span("10:00", "11:00")))

        assertThat(positions).containsExactly(
            PositionedEvent(col = 0, colSpan = 1, colCount = 2),
            PositionedEvent(col = 1, colSpan = 1, colCount = 2),
            PositionedEvent(col = 0, colSpan = 1, colCount = 2),
        )
    }

    @Test
    fun three_way_overlap_threeColumns() {
        val positions = layoutDay(listOf(span("9:00", "11:00"), span("9:30", "10:30"), span("10:00", "10:15")))

        assertThat(positions).containsExactly(
            PositionedEvent(col = 0, colSpan = 1, colCount = 3),
            PositionedEvent(col = 1, colSpan = 1, colCount = 3),
            PositionedEvent(col = 2, colSpan = 1, colCount = 3),
        )
    }

    @Test
    fun back_to_back_insideACluster_shareAColumn() {
        // the long event forces two columns; the two halves of its hour stack in the second
        val positions = layoutDay(listOf(span("9:00", "11:00"), span("9:00", "10:00"), span("10:00", "11:00")))

        assertThat(positions).containsExactly(
            PositionedEvent(col = 0, colSpan = 1, colCount = 2),
            PositionedEvent(col = 1, colSpan = 1, colCount = 2),
            PositionedEvent(col = 1, colSpan = 1, colCount = 2),
        )
    }

    @Test
    fun expansion_intoFreeColumnsToTheRight() {
        // 9–10 needs three columns; 10–12 lands in column 1 and column 2 is free after 10, so it spans two
        val positions = layoutDay(
            listOf(span("9:00", "12:00"), span("9:00", "10:00"), span("9:00", "9:45"), span("10:00", "12:00")),
        )

        assertThat(positions).containsExactly(
            PositionedEvent(col = 0, colSpan = 1, colCount = 3),
            PositionedEvent(col = 1, colSpan = 1, colCount = 3),
            PositionedEvent(col = 2, colSpan = 1, colCount = 3),
            PositionedEvent(col = 1, colSpan = 2, colCount = 3),
        )
    }

    @Test
    fun expansion_stopsAtTheFirstBlockedColumn() {
        // 10:00–12:00 reuses column 1; column 2 (9:15–11:00) still overlaps it, so it stays one
        // column wide even though column 3 (9:30–9:45) is free beyond the blocked one
        val positions = layoutDay(
            listOf(
                span("9:00", "12:00"),
                span("9:00", "10:00"),
                span("9:15", "11:00"),
                span("9:30", "9:45"),
                span("10:00", "12:00"),
            ),
        )

        assertThat(positions).containsExactly(
            PositionedEvent(col = 0, colSpan = 1, colCount = 4),
            PositionedEvent(col = 1, colSpan = 1, colCount = 4),
            PositionedEvent(col = 2, colSpan = 1, colCount = 4),
            PositionedEvent(col = 3, colSpan = 1, colCount = 4),
            PositionedEvent(col = 1, colSpan = 1, colCount = 4),
        )
    }

    @Test
    fun separate_clusters_areIndependent() {
        val positions = layoutDay(listOf(span("9:00", "10:00"), span("9:30", "10:30"), span("13:00", "14:00")))

        assertThat(positions).containsExactly(
            PositionedEvent(col = 0, colSpan = 1, colCount = 2),
            PositionedEvent(col = 1, colSpan = 1, colCount = 2),
            FULL,
        )
    }

    @Test
    fun min_duration_packsShortEventsByTheirVisualHeight() {
        // a 5-minute event's chip would cover the 9:10 event's top at a 23-minute floor
        val spans = listOf(span("9:00", "9:05"), span("9:10", "9:40"))

        assertThat(layoutDay(spans)).containsExactly(FULL, FULL)
        assertThat(layoutDay(spans, minDurationMinutes = 23)).containsExactly(
            PositionedEvent(col = 0, colSpan = 1, colCount = 2),
            PositionedEvent(col = 1, colSpan = 1, colCount = 2),
        )
    }

    @Test
    fun zero_length_events_atTheSameTime_stillGetTheirOwnColumns() {
        assertThat(layoutDay(listOf(span("9:00", "9:00"), span("9:00", "9:00")))).containsExactly(
            PositionedEvent(col = 0, colSpan = 1, colCount = 2),
            PositionedEvent(col = 1, colSpan = 1, colCount = 2),
        )
    }

    @Test
    fun minute_span_overlap_isStrict() {
        assertThat(span("9:00", "10:00").overlaps(span("10:00", "11:00"))).isFalse()
        assertThat(span("9:00", "10:01").overlaps(span("10:00", "11:00"))).isTrue()
    }

    @Test
    fun minute_span_rejectsInvertedOrOutOfDayRanges() {
        assertFailure { MinuteSpan(60, 30) }.isInstanceOf<IllegalArgumentException>()
        assertFailure { MinuteSpan(0, MINUTES_PER_DAY + 1) }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun minute_span_on_sameDay() {
        assertThat(minuteSpanOn(DAY, DAY.at("9:30"), DAY.at("10:15"))).isEqualTo(MinuteSpan(570, 615))
    }

    @Test
    fun minute_span_on_clampsEventsSpanningMidnight() {
        val yesterday = DAY.minusDays(1)
        val tomorrow = DAY.plusDays(1)

        assertThat(minuteSpanOn(DAY, yesterday.at("22:00"), DAY.at("1:00"))).isEqualTo(MinuteSpan(0, 60))
        assertThat(minuteSpanOn(DAY, DAY.at("23:00"), tomorrow.at("2:00"))).isEqualTo(MinuteSpan(1380, MINUTES_PER_DAY))
        assertThat(minuteSpanOn(DAY, yesterday.at("9:00"), tomorrow.at("9:00"))).isEqualTo(MinuteSpan(0, MINUTES_PER_DAY))
    }

    @Test
    fun minute_span_on_endingExactlyAtMidnight_runsToTheBottomOfTheDay() {
        assertThat(minuteSpanOn(DAY, DAY.at("23:00"), DAY.plusDays(1).at("0:00")))
            .isEqualTo(MinuteSpan(1380, MINUTES_PER_DAY))
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 9, 14)
        val FULL = PositionedEvent(col = 0, colSpan = 1, colCount = 1)

        fun LocalDate.at(time: String): LocalDateTime = atTime(parse(time))

        fun parse(time: String): LocalTime = time.split(":").let { (h, m) -> LocalTime.of(h.toInt(), m.toInt()) }

        fun span(start: String, end: String) = parse(start).let { s ->
            MinuteSpan(s.toSecondOfDay() / 60, parse(end).toSecondOfDay() / 60)
        }
    }
}

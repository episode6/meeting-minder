package com.episode6.meetingminder.data.db

import java.time.LocalDate
import com.episode6.meetingminder.model.SnapshotEvent
import com.episode6.meetingminder.model.ScheduleChange
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.testCalendarEvent
import org.junit.Test
import java.time.Instant

class ChangeSnapshotMappingTest {

    private val standup = testCalendarEvent(1, Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000), title = "Standup")
    private val dentist = testCalendarEvent(2, Instant.ofEpochMilli(3_000), Instant.ofEpochMilli(4_000), title = "Dentist", meeting = false)

    @Test
    fun encodeThenDecode_roundTripsEveryFieldAndSelection() {
        val json = encodeChangeSnapshotEvents(listOf(standup, dentist), selectedKeys = setOf(standup.key))

        val decoded = decodeChangeSnapshotEvents(json)

        assertThat(decoded).containsExactly(
            ChangeSnapshotEventDto(
                eventId = 1, instanceTime = 0, beginMillis = 1_000, endMillis = 2_000,
                cancelled = false, declinedByMe = false, selected = true, isMeeting = true,
            ),
            ChangeSnapshotEventDto(
                eventId = 2, instanceTime = 0, beginMillis = 3_000, endMillis = 4_000,
                cancelled = false, declinedByMe = false, selected = false, isMeeting = false,
            ),
        )
        assertThat(decoded.first().key).isEqualTo(EventKey(1, 0))
    }

    @Test
    fun encodeChangeSnapshotEvents_ofAnEmptyList_decodesBackToEmpty() {
        assertThat(decodeChangeSnapshotEvents(encodeChangeSnapshotEvents(emptyList(), emptySet()))).isEmpty()
    }

    @Test
    fun decode_aRowWrittenBeforeAllDayWasStored_readsAsTimed() {
        val json = """[{"eventId":1,"instanceTime":0,"beginMillis":1000,"endMillis":2000,"cancelled":false,"declinedByMe":false,"selected":true,"isMeeting":true}]"""

        assertThat(decodeChangeSnapshotEvents(json).single().allDay).isEqualTo(false)
    }

    @Test
    fun baseline_decodesIntoTheDiffersSnapshotEvents() {
        val entity = ChangeSnapshotEntity(LocalDate.of(2026, 9, 14), 1, encodeChangeSnapshotEvents(listOf(standup), setOf(standup.key)))

        assertThat(entity.baseline()).containsExactly(
            SnapshotEvent(standup.key, standup.begin, standup.end, cancelled = false, declinedByMe = false, selected = true, isMeeting = true, allDay = false),
        )
    }

    @Test
    fun scheduleChanges_roundTripEveryKind() {
        val date = LocalDate.of(2026, 9, 14)
        val changes = listOf(
            ScheduleChange.New(date, EventKey(1, 0), Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000)),
            ScheduleChange.Moved(date, EventKey(2, 5), Instant.ofEpochMilli(3_000), Instant.ofEpochMilli(4_000), Instant.ofEpochMilli(5_000), Instant.ofEpochMilli(6_000)),
            ScheduleChange.Cancelled(date, EventKey(3, 0), Instant.ofEpochMilli(7_000), Instant.ofEpochMilli(8_000)),
            ScheduleChange.Declined(date, EventKey(4, 0), Instant.ofEpochMilli(9_000), Instant.ofEpochMilli(10_000)),
        )

        assertThat(decodeScheduleChanges(date, encodeScheduleChanges(changes))).isEqualTo(changes)
    }

    @Test
    fun decodeScheduleChanges_ofUnreadableJson_isEmpty() {
        assertThat(decodeScheduleChanges(LocalDate.of(2026, 9, 14), "not json")).isEmpty()
    }
}

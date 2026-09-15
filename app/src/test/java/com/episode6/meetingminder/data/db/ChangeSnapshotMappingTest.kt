package com.episode6.meetingminder.data.db

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
}

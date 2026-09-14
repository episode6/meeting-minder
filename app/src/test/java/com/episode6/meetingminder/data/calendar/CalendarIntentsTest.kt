package com.episode6.meetingminder.data.calendar

import android.content.Intent
import android.provider.CalendarContract
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.testCalendarEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarIntentsTest {

    // an exception occurrence: the key holds the series id, the event id is the occurrence's own
    private val occurrence = testCalendarEvent(
        id = 42,
        begin = Instant.parse("2026-09-14T13:00:00Z"),
        end = Instant.parse("2026-09-14T13:30:00Z"),
    ).copy(key = EventKey(7, 1_789_000_000_000))

    @Test
    fun viewEvent_addressesTheOccurrencesOwnEventId_withItsInstanceTimes() {
        val intent = CalendarIntents.viewEvent(occurrence)

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data.toString()).isEqualTo("content://com.android.calendar/events/42")
        assertThat(intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0)).isEqualTo(occurrence.begin.toEpochMilli())
        assertThat(intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0)).isEqualTo(occurrence.end.toEpochMilli())
    }

    @Test
    fun viewTime_opensTheCalendarAtTheEventsBegin() {
        val intent = CalendarIntents.viewTime(occurrence)

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data.toString()).isEqualTo("content://com.android.calendar/time/${occurrence.begin.toEpochMilli()}")
    }
}

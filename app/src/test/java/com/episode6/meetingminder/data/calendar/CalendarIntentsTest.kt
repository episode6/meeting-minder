package com.episode6.meetingminder.data.calendar

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.testCalendarEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarIntentsTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()

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

    @Test
    fun openInCalendar_withNoCalendarApp_reportsFailure() {
        assertThat(application.openInCalendar(occurrence)).isFalse()
        assertThat(shadowOf(application).nextStartedActivity).isNull()
    }

    @Test
    fun openInCalendar_prefersTheEvent() {
        registerHandler(CalendarIntents.viewEvent(occurrence))
        registerHandler(CalendarIntents.viewTime(occurrence))

        assertThat(application.openInCalendar(occurrence)).isTrue()
        assertThat(shadowOf(application).nextStartedActivity.data).isEqualTo(CalendarIntents.viewEvent(occurrence).data)
    }

    @Test
    fun openInCalendar_fallsBackToTheTime_whenNothingViewsEvents() {
        registerHandler(CalendarIntents.viewTime(occurrence))

        assertThat(application.openInCalendar(occurrence)).isTrue()
        assertThat(shadowOf(application).nextStartedActivity.data).isEqualTo(CalendarIntents.viewTime(occurrence).data)
    }

    private fun registerHandler(intent: Intent) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.calendar"
                name = "com.example.calendar.ViewActivity"
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(application.packageManager).addResolveInfoForIntent(intent, info)
    }
}

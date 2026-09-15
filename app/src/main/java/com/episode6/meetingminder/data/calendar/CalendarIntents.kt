package com.episode6.meetingminder.data.calendar

import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import com.episode6.meetingminder.model.CalendarEvent

/**
 * The intents behind "open in calendar" for a long-pressed chip (TODO.md §4.1); the ui
 * layer's `Context.openInCalendar` launches them. The manifest's `<queries>` entry for
 * `content://com.android.calendar` is what lets it see which calendar app (if any)
 * handles these on API 30+.
 */
object CalendarIntents {

    /**
     * `ACTION_VIEW` on `content://com.android.calendar/events/{eventId}`, addressed by the
     * occurrence's own [CalendarEvent.eventId] and carrying the *instance* begin/end so
     * Google Calendar opens this occurrence rather than the first of the series.
     */
    fun viewEvent(event: CalendarEvent): Intent =
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin.toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end.toEpochMilli())

    /** The fallback: `content://com.android.calendar/time/{begin}`, the calendar app's view of that moment. */
    fun viewTime(event: CalendarEvent): Intent =
        Intent(
            Intent.ACTION_VIEW,
            ContentUris.appendId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time"), event.begin.toEpochMilli()).build(),
        )
}

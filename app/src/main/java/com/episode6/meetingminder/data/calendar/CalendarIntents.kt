package com.episode6.meetingminder.data.calendar

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.ui.util.findActivity

/**
 * "Open in calendar" for a long-pressed chip (TODO.md §4.1). The manifest's `<queries>`
 * entry for `content://com.android.calendar` is what lets [Context.openInCalendar] see
 * which calendar app (if any) handles these on API 30+.
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

/**
 * Opens [event] in the calendar app: the event itself if some app handles that, else the
 * calendar at the event's time. Returns false when no app handles either. From a context
 * with no activity behind it the calendar opens in a new task.
 */
fun Context.openInCalendar(event: CalendarEvent): Boolean =
    listOf(CalendarIntents.viewEvent(event), CalendarIntents.viewTime(event))
        .onEach { if (findActivity() == null) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .filter { it.resolveActivity(packageManager) != null }
        .any { intent ->
            try {
                startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }

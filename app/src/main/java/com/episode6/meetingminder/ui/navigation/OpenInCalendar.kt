package com.episode6.meetingminder.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.episode6.meetingminder.data.calendar.CalendarIntents
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.ui.util.findActivity
import java.time.Instant

/**
 * Opens [event] in the calendar app: the event itself if some app handles that, else the
 * calendar at the event's time. Returns false when no app handles either. From a context
 * with no activity behind it the calendar opens in a new task.
 */
fun Context.openInCalendar(event: CalendarEvent): Boolean = openInCalendar(event.eventId, event.begin, event.end)

/** [openInCalendar] from stored ids and times (the ringing screen's "Open meeting"). */
fun Context.openInCalendar(eventId: Long, begin: Instant, end: Instant): Boolean =
    listOf(CalendarIntents.viewEvent(eventId, begin, end), CalendarIntents.viewTime(begin))
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

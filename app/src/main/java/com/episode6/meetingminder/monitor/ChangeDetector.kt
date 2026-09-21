package com.episode6.meetingminder.monitor

import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.model.SelfStatus
import com.episode6.meetingminder.model.SnapshotEvent
import java.time.Instant
import java.time.LocalDate

/**
 * The differ (TODO.md §4.3): what has changed on one shared [date][detect] since it was
 * shared. Pure, so every row of the §4.3 table is a plain JVM test (`ChangeDetectorTest`).
 *
 * The scope rule, so it never nags about something that doesn't change what was shared:
 * **New** applies to any [CalendarEvent.isMeeting] event, selected or not (a new invite is
 * worth knowing about); **Moved / Cancelled / Declined** apply only to keys that were
 * **selected** at share time, meeting or not (a selected solo block that moves changes the
 * busy ranges; an unselected meeting that moves doesn't).
 *
 * | Case | Rule |
 * |---|---|
 * | New | key absent from the baseline, begins at or after now, `isMeeting` |
 * | Moved | selected key, begin/end differ from the baseline, the new slot ends after now |
 * | Cancelled | selected key gone from the day (or `CANCELED`), and it hadn't started yet |
 * | Declined | selected key, now declined by me (and wasn't at share time), not over yet |
 * | Ignored | title/colour/attendee edits, rewrites with identical times, an event replaced by another at identical times, events already over, all-day events |
 *
 * Only times and the RSVP/cancel flags are compared, which is what makes title, colour,
 * description, reminder and attendee-list edits (and sync rewrites of identical values)
 * invisible. "The rest of the day" (`[max(now, start of day), end of day]`) is expressed
 * through the "not over yet / hasn't started" conditions against [now] rather than by
 * trimming [fresh], so a selected event that is still running is never read as gone.
 *
 * **Replaced at identical times**: an edit can change an occurrence's [EventKey] without
 * touching its times — the organizer edits "this and following events" (every later
 * occurrence moves to a new series id), or deletes and recreates the meeting. By key alone
 * that reads as Cancelled + New for a slot that never changed, so a key gone from the day is
 * paired one-to-one with a key new to the day at exactly the same begin and end, and the pair
 * is no change at all. Only like pairs with like (both [CalendarEvent.isMeeting] or both
 * not), so a solo block deleted to make room for a new invite still reports the invite, and
 * a selected row gets first pick of an arrival. The alarm agrees: `maintainAlarms` keeps the alarm of a key that
 * vanished, and it is still set for the right time.
 */
object ChangeDetector {

    /**
     * [baseline] is the day's `change_snapshot` (every event at share time); [fresh] is a
     * new read of the **whole** day (`CalendarRepository.eventsOn`). Changes are ordered by
     * the time they're about (the new time for New/Moved), then by key.
     */
    fun detect(date: LocalDate, baseline: List<SnapshotEvent>, fresh: List<CalendarEvent>, now: Instant): List<ScheduleChange> {
        val before = baseline.associateBy { it.key }
        val after = fresh.associateBy { it.key }
        val changes = mutableListOf<ScheduleChange>()

        // a baseline key gone from the day → the new key that took over its exact slot
        val arrivals = fresh.filterTo(mutableListOf()) {
            it.key !in before && !it.allDay && it.status != EventStatus.CANCELED && it.selfStatus != SelfStatus.DECLINED
        }
        val replacedBy = mutableMapOf<EventKey, CalendarEvent>()
        // selected rows pick first: when two vanished events shared the slot, the arrival
        // stands in for the one that was shared
        for (was in baseline.sortedByDescending { it.selected }) {
            if (was.allDay || was.cancelled || was.declinedByMe) continue
            val current = after[was.key]
            if (current != null && current.status != EventStatus.CANCELED) continue
            // like with like: a vanished solo block never swallows a new invite in its slot
            val arrival = arrivals.firstOrNull { it.begin == was.begin && it.end == was.end && it.isMeeting == was.isMeeting } ?: continue
            arrivals -= arrival
            replacedBy[was.key] = arrival
        }
        val replacements = replacedBy.values.mapTo(mutableSetOf()) { it.key }

        for (event in fresh) {
            if (event.key !in before && event.key !in replacements && event.isMeeting && event.begin >= now) {
                changes += ScheduleChange.New(date, event.key, event.begin, event.end)
            }
        }

        for (was in baseline) {
            if (!was.selected || was.allDay) continue
            val event = after[was.key]
            when {
                was.key in replacedBy -> Unit
                event == null || event.status == EventStatus.CANCELED -> if (!was.cancelled && was.begin > now) {
                    changes += ScheduleChange.Cancelled(date, was.key, was.begin, was.end)
                }
                event.allDay -> Unit
                event.selfStatus == SelfStatus.DECLINED -> if (!was.declinedByMe && event.end > now) {
                    changes += ScheduleChange.Declined(date, was.key, event.begin, event.end)
                }
                (event.begin != was.begin || event.end != was.end) && event.end > now -> {
                    changes += ScheduleChange.Moved(date, was.key, was.begin, was.end, event.begin, event.end)
                }
            }
        }

        return changes.sortedWith(compareBy<ScheduleChange>({ it.sortTime }, { it.key.eventId }, { it.key.instanceTime }))
    }

    private val ScheduleChange.sortTime: Instant
        get() = when (this) {
            is ScheduleChange.New -> begin
            is ScheduleChange.Moved -> newBegin
            is ScheduleChange.Cancelled -> begin
            is ScheduleChange.Declined -> begin
        }
}

package com.episode6.meetingminder.share

import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventKey

/**
 * The merged busy ranges a share of a day's [selections] says (TODO.md §4.2): each selected
 * event at its freshly loaded time when [events] still has it — same re-timing rule as the
 * alarm reconcile, so a meeting moved after it was selected shares where it is now — and at
 * its stored time (the map's value) otherwise. Used for the share itself and to tell
 * whether the selection still matches what was shared.
 */
fun selectedBusyRanges(selections: Map<EventKey, BusyRange>, events: List<CalendarEvent>): List<BusyRange> {
    val fresh = events.associateBy { it.key }
    return ScheduleTextFormatter.merge(
        selections.map { (key, stored) -> fresh[key]?.let { BusyRange(it.begin, it.end) } ?: stored },
    )
}

package com.episode6.meetingminder.data.calendar

import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.SelfStatus

/**
 * Which calendars currently feed the itinerary (TODO.md §4.1, §5 PR-12): every calendar
 * whose Settings → Calendars [overrides] entry says include, or, absent an override,
 * whose `VISIBLE` flag does. An empty [overrides] map (nobody has touched a calendar
 * toggle) is answered with [CalendarFilter.Visible] itself rather than a computed
 * [CalendarFilter.Only] of the same ids, so the common case never needs [calendars] to
 * have been read yet — see `LoadDayEventsSideEffects` and `monitor.ChangeMonitor`, which
 * both apply this to keep a shared day's baseline and its background re-checks looking at
 * the same calendars.
 */
fun effectiveCalendarFilter(calendars: List<CalendarInfo>, overrides: Map<Long, Boolean>): CalendarFilter =
    if (overrides.isEmpty()) {
        CalendarFilter.Visible
    } else {
        CalendarFilter.Only(calendars.filter { overrides[it.id] ?: it.visible }.mapTo(mutableSetOf()) { it.id })
    }

/**
 * Hides events declined by the user entirely when Settings' "show declined" toggle
 * (TODO.md §4.1, on by default) is off; otherwise a no-op. Declined events are never
 * "meetings" ([CalendarEvent.isMeeting]) so this never changes a count, only what renders.
 */
fun List<CalendarEvent>.excludeDeclined(showDeclined: Boolean): List<CalendarEvent> =
    if (showDeclined) this else filterNot { it.selfStatus == SelfStatus.DECLINED }

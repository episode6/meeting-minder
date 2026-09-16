package com.episode6.meetingminder.data.calendar

import com.episode6.meetingminder.data.settings.BusySync
import com.episode6.meetingminder.model.CalendarInfo

/**
 * `Calendars.CALENDAR_ACCESS_LEVEL` at which the calendar lets us insert events
 * (`CAL_ACCESS_CONTRIBUTOR`); duplicated here (as `CALENDAR_ACCESS_RESPOND` is in
 * `model/Rsvp.kt`) so this file stays free of Android imports. `CAL_ACCESS_RESPOND` (300),
 * what the RSVP write needs, is below this floor and cannot insert.
 */
const val CALENDAR_ACCESS_CONTRIBUTOR = 500

/** The display name a "Family" calendar match compares against, case-insensitive and trimmed. */
private const val FAMILY_CALENDAR_NAME = "family"

/**
 * Calendars the app may write a busy block to (TODO.md §4.7): `SYNC_EVENTS` on (a
 * sync-disabled calendar holds no events, and an insert into one is pointless) and
 * [CalendarInfo.accessLevel] at [CALENDAR_ACCESS_CONTRIBUTOR] or better.
 */
fun List<CalendarInfo>.writable(): List<CalendarInfo> =
    filter { it.syncEvents && it.accessLevel >= CALENDAR_ACCESS_CONTRIBUTOR }

/**
 * The default sync target: the first writable calendar (in provider order) whose
 * [CalendarInfo.displayName] is "Family" case-insensitively once trimmed, or null if there
 * isn't one.
 */
fun defaultBusyCalendar(calendars: List<CalendarInfo>): CalendarInfo? =
    calendars.writable().firstOrNull { it.displayName.trim().equals(FAMILY_CALENDAR_NAME, ignoreCase = true) }

/**
 * The calendar the sync will write to right now, or null when the feature is off, unset, or
 * [BusySync.calendarId] points at a calendar that is gone or no longer writable.
 */
fun effectiveBusyCalendar(settings: BusySync, calendars: List<CalendarInfo>): CalendarInfo? {
    if (!settings.enabled) return null
    val calendarId = settings.calendarId ?: return null
    return calendars.writable().firstOrNull { it.id == calendarId }
}

package com.episode6.meetingminder.model

/**
 * `Calendars.CALENDAR_ACCESS_LEVEL` at which the calendar lets us answer an invite
 * (`CAL_ACCESS_RESPOND`, what the RSVP write needs); duplicated here so `model/` stays
 * free of Android imports.
 */
const val CALENDAR_ACCESS_RESPOND = 300

/**
 * `Calendars.CALENDAR_ACCESS_LEVEL` at which the calendar lets us insert events
 * (`CAL_ACCESS_CONTRIBUTOR`, what a busy block needs — TODO.md §4.7); [CALENDAR_ACCESS_RESPOND]
 * is below this floor and cannot insert.
 */
const val CALENDAR_ACCESS_CONTRIBUTOR = 500

/**
 * One row of `CalendarContract.Calendars`: one calendar on one account (TODO.md §4.1).
 * Every calendar is returned, hidden and non-syncing ones included; the itinerary
 * respects [visible] by default and Settings (PR-12) can override it per calendar.
 */
data class CalendarInfo(
    val id: Long,
    val accountName: String,
    val accountType: String,
    val displayName: String,
    val color: Int,
    /** `VISIBLE`: shown in Google Calendar's "Show" list. Hidden calendars still hold events. */
    val visible: Boolean,
    /** `SYNC_EVENTS`: false means the user turned sync off, so the calendar holds no events. */
    val syncEvents: Boolean,
    /** `OWNER_ACCOUNT`: the email whose `Attendees` row is "me" on this calendar. */
    val ownerAccount: String?,
    val isPrimary: Boolean,
    /** `CALENDAR_ACCESS_LEVEL`, e.g. [CALENDAR_ACCESS_RESPOND] (300), [CALENDAR_ACCESS_CONTRIBUTOR] (500) or `CAL_ACCESS_OWNER` (700). */
    val accessLevel: Int,
    /** `CAN_ORGANIZER_RESPOND`; exposed for §4.6 even though organizers are skipped unconditionally. */
    val canOrganizerRespond: Boolean,
)

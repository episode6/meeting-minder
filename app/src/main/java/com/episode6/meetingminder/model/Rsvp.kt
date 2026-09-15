package com.episode6.meetingminder.model

/**
 * `Calendars.CALENDAR_ACCESS_LEVEL` at which the calendar lets us answer an invite
 * (`CAL_ACCESS_RESPOND`); duplicated here so this file stays free of Android imports.
 */
const val CALENDAR_ACCESS_RESPOND = 300

/**
 * Where one selection's RSVP stands (`selected_event.rsvp_state`, TODO.md §4.6). The
 * first three are decided by [rsvpDecision] when the event is armed; the rest by the
 * write itself and by later reloads of the day.
 */
enum class RsvpState {
    /**
     * Nothing to answer: no attendee data, a solo block, you organised it, you already
     * accepted or declined, or the organizer cancelled it. Silent.
     */
    NOT_APPLICABLE,

    /** There is an invite but we can't answer it (read-only calendar, or it went to an alias); the chip says so. */
    UNRESPONDABLE,

    /** The write is on its way (`RsvpAccept` dispatched). */
    PENDING,

    /** Our attendee row says accepted; the provider has marked the event dirty for Google's sync adapter. */
    ACCEPTED_LOCALLY,

    /** A later reload of the day saw the event with `DIRTY = 0`: the response reached the server. */
    SYNCED,

    /** The provider write threw; the chip says so. Never retried automatically. */
    FAILED,
}

/**
 * The §4.6 skip table: the [RsvpState] a newly armed [event]'s selection starts in.
 * [RsvpState.PENDING] means "go ahead and write"; anything else is a skip. Evaluated in
 * table order, first match wins. Called only on the explicit "Set alarms" tap — the
 * user's commitment moment — never from a background reconcile.
 */
fun rsvpDecision(event: CalendarEvent): RsvpState = when {
    // self-only attendee data (Exchange, some shared calendars): there is no attendee row to answer through
    !event.hasAttendeeData -> RsvpState.NOT_APPLICABLE
    // a solo block: no attendee rows at all. The exception insert would crash the provider
    // ("Status update WTF") without a self row, so this check is mandatory, not cosmetic
    event.humanAttendees == 0 -> RsvpState.NOT_APPLICABLE
    // Google already has the organizer as accepted
    event.isOrganizer -> RsvpState.NOT_APPLICABLE
    event.selfStatus == SelfStatus.ACCEPTED -> RsvpState.NOT_APPLICABLE
    // a decline is the user's answer, given in Google Calendar; we never un-respond on their
    // behalf (§4.6), so a selection they declined after selecting it keeps its alarm and nothing else
    event.selfStatus == SelfStatus.DECLINED -> RsvpState.NOT_APPLICABLE
    // the repository filters cancelled occurrences out, but a cancelled one must never be
    // answered (the exception insert would also write STATUS = CONFIRMED for it)
    event.status == EventStatus.CANCELED -> RsvpState.NOT_APPLICABLE
    // the provider would take the local write and the server would reject it on sync, leaving a stuck dirty row
    event.calendarAccessLevel < CALENDAR_ACCESS_RESPOND -> RsvpState.UNRESPONDABLE
    // invited through an alias: there are attendees but none matches OWNER_ACCOUNT, and aliases
    // aren't discoverable from the provider
    event.humanAttendees >= 1 && event.selfAttendeeId == null -> RsvpState.UNRESPONDABLE
    else -> RsvpState.PENDING
}

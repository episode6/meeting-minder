package com.episode6.meetingminder.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import java.time.Instant

/** One case per row of the TODO.md §4.6 skip table, in table order, plus the accept case. */
class RsvpDecisionTest {

    /** An invite from someone else on a writable Google calendar: the row every skip case deviates from. */
    private val invite = testCalendarEvent(1, Instant.parse("2026-09-14T13:00:00Z"), Instant.parse("2026-09-14T13:30:00Z")).copy(
        hasAttendeeData = true,
        humanAttendees = 2,
        isOrganizer = false,
        selfStatus = SelfStatus.NEEDS_ACTION,
        calendarAccessLevel = 700,
        selfAttendeeId = 10,
    )

    @Test
    fun anInviteFromSomeoneElse_isAnswered() {
        assertThat(rsvpDecision(invite)).isEqualTo(RsvpState.PENDING)
    }

    @Test
    fun aTentativeAnswer_isStillAnswered() {
        assertThat(rsvpDecision(invite.copy(selfStatus = SelfStatus.TENTATIVE))).isEqualTo(RsvpState.PENDING)
    }

    @Test
    fun selfOnlyAttendeeData_isNotApplicable() {
        // Exchange / shared calendars: humanAttendees is always 0 there too, but the flag alone decides
        assertThat(rsvpDecision(invite.copy(hasAttendeeData = false, humanAttendees = 0, selfAttendeeId = null)))
            .isEqualTo(RsvpState.NOT_APPLICABLE)
    }

    @Test
    fun aSoloBlockWithNoAttendeeRows_isNotApplicable_notUnrespondable() {
        // the solo-vs-alias distinction: no rows at all is "nothing to answer", not "can't answer"
        assertThat(rsvpDecision(invite.copy(humanAttendees = 0, selfAttendeeId = null))).isEqualTo(RsvpState.NOT_APPLICABLE)
    }

    @Test
    fun beingTheOrganizer_isNotApplicable() {
        assertThat(rsvpDecision(invite.copy(isOrganizer = true))).isEqualTo(RsvpState.NOT_APPLICABLE)
    }

    @Test
    fun alreadyAccepted_isNotApplicable() {
        assertThat(rsvpDecision(invite.copy(selfStatus = SelfStatus.ACCEPTED))).isEqualTo(RsvpState.NOT_APPLICABLE)
    }

    @Test
    fun aCalendarBelowRespondAccess_isUnrespondable() {
        assertThat(rsvpDecision(invite.copy(calendarAccessLevel = CALENDAR_ACCESS_RESPOND - 1))).isEqualTo(RsvpState.UNRESPONDABLE)
        assertThat(rsvpDecision(invite.copy(calendarAccessLevel = CALENDAR_ACCESS_RESPOND))).isEqualTo(RsvpState.PENDING)
    }

    @Test
    fun anInviteToAnAlias_isUnrespondable() {
        // attendees exist but none matches OWNER_ACCOUNT
        assertThat(rsvpDecision(invite.copy(selfAttendeeId = null))).isEqualTo(RsvpState.UNRESPONDABLE)
    }

    @Test
    fun rowsAreEvaluatedInTableOrder_soNotApplicableWinsOverUnrespondable() {
        // already accepted on a read-only calendar: silent skip, no "couldn't RSVP" hint
        assertThat(rsvpDecision(invite.copy(selfStatus = SelfStatus.ACCEPTED, calendarAccessLevel = 200)))
            .isEqualTo(RsvpState.NOT_APPLICABLE)
        // organizer with no self row: still silent
        assertThat(rsvpDecision(invite.copy(isOrganizer = true, selfAttendeeId = null))).isEqualTo(RsvpState.NOT_APPLICABLE)
    }
}

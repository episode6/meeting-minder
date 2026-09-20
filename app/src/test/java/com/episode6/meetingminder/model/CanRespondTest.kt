package com.episode6.meetingminder.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import java.time.Instant

/**
 * Which events the long-press sheet offers "Yes / No / Maybe" for: everything the
 * provider can take an answer for, whether or not the automatic path would skip it.
 */
class CanRespondTest {

    /** An invite from someone else on a writable Google calendar, as in [RsvpDecisionTest]. */
    private val invite = testCalendarEvent(1, Instant.parse("2026-09-14T13:00:00Z"), Instant.parse("2026-09-14T13:30:00Z")).copy(
        hasAttendeeData = true,
        humanAttendees = 2,
        isOrganizer = false,
        selfStatus = SelfStatus.NEEDS_ACTION,
        calendarAccessLevel = 700,
        selfAttendeeId = 10,
    )

    @Test
    fun anInviteFromSomeoneElse_canBeAnswered() {
        assertThat(canRespond(invite)).isTrue()
    }

    @Test
    fun anAnsweredInvite_canBeAnsweredAgain_unlikeTheAutomaticPath() {
        assertThat(canRespond(invite.copy(selfStatus = SelfStatus.ACCEPTED))).isTrue()
        assertThat(canRespond(invite.copy(selfStatus = SelfStatus.DECLINED))).isTrue()
        assertThat(canRespond(invite.copy(selfStatus = SelfStatus.TENTATIVE))).isTrue()
        assertThat(rsvpDecision(invite.copy(selfStatus = SelfStatus.DECLINED))).isEqualTo(RsvpState.NOT_APPLICABLE)
    }

    @Test
    fun selfOnlyAttendeeData_cannot() {
        assertThat(canRespond(invite.copy(hasAttendeeData = false, humanAttendees = 0, selfAttendeeId = null))).isFalse()
    }

    @Test
    fun aSoloBlockWithNoSelfRow_cannot_theExceptionInsertWouldCrashTheProvider() {
        assertThat(canRespond(invite.copy(humanAttendees = 0, selfAttendeeId = null))).isFalse()
    }

    @Test
    fun anInviteToAnAlias_cannot() {
        assertThat(canRespond(invite.copy(selfAttendeeId = null))).isFalse()
    }

    @Test
    fun theOrganizer_cannot() {
        assertThat(canRespond(invite.copy(isOrganizer = true))).isFalse()
    }

    @Test
    fun aCancelledOccurrence_cannot() {
        assertThat(canRespond(invite.copy(status = EventStatus.CANCELED))).isFalse()
    }

    @Test
    fun aCalendarBelowRespondAccess_cannot() {
        assertThat(canRespond(invite.copy(calendarAccessLevel = CALENDAR_ACCESS_RESPOND - 1))).isFalse()
        assertThat(canRespond(invite.copy(calendarAccessLevel = CALENDAR_ACCESS_RESPOND))).isTrue()
    }
}

package com.episode6.meetingminder.monitor

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.model.SelfStatus
import com.episode6.meetingminder.model.SnapshotEvent
import com.episode6.meetingminder.model.testCalendarEvent
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** One test (or more) per row of the TODO.md §4.3 table, plus the scope rule. It is 12:00 on the shared day. */
class ChangeDetectorTest {

    private val date = LocalDate.of(2026, 9, 14)
    private val now = at(12)

    private fun at(hour: Int, minute: Int = 0): Instant = Instant.parse("2026-09-14T%02d:%02d:00Z".format(hour, minute))

    private val designReview = testCalendarEvent(1, at(13), at(14), title = "Design review")
    private val dentist = testCalendarEvent(2, at(15), at(16), title = "Dentist", meeting = false)
    private val oneOnOne = testCalendarEvent(3, at(16), at(16, 30), title = "1:1")

    private fun CalendarEvent.snapshot(selected: Boolean) = SnapshotEvent(
        key = key,
        begin = begin,
        end = end,
        cancelled = status == EventStatus.CANCELED,
        declinedByMe = selfStatus == SelfStatus.DECLINED,
        selected = selected,
        isMeeting = isMeeting,
        allDay = allDay,
    )

    private fun detect(baseline: List<SnapshotEvent>, fresh: List<CalendarEvent>) = ChangeDetector.detect(date, baseline, fresh, now)

    // New

    @Test
    fun new_aMeetingAddedSinceTheShare_isNew() {
        val invite = testCalendarEvent(9, at(15), at(15, 30))

        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(designReview, invite)))
            .containsExactly(ScheduleChange.New(date, invite.key, invite.begin, invite.end))
    }

    @Test
    fun new_whenNothingWasSelected_isStillNew() {
        val invite = testCalendarEvent(9, at(15), at(15, 30))

        assertThat(detect(emptyList(), listOf(invite))).containsExactly(ScheduleChange.New(date, invite.key, invite.begin, invite.end))
    }

    @Test
    fun new_aSoloBlock_isIgnored() {
        assertThat(detect(emptyList(), listOf(dentist))).isEmpty()
    }

    @Test
    fun new_aMeetingThatAlreadyStarted_isIgnored() {
        assertThat(detect(emptyList(), listOf(testCalendarEvent(9, at(11, 30), at(12, 30))))).isEmpty()
    }

    @Test
    fun new_aMeetingStartingRightNow_isNew() {
        val invite = testCalendarEvent(9, at(12), at(12, 30))

        assertThat(detect(emptyList(), listOf(invite))).containsExactly(ScheduleChange.New(date, invite.key, invite.begin, invite.end))
    }

    @Test
    fun new_aDeclinedInvite_isIgnored() {
        assertThat(detect(emptyList(), listOf(testCalendarEvent(9, at(15), at(16)).copy(selfStatus = SelfStatus.DECLINED)))).isEmpty()
    }

    @Test
    fun new_anAllDayEvent_isIgnored() {
        assertThat(detect(emptyList(), listOf(testCalendarEvent(9, at(0), at(0).plusSeconds(86_400), allDay = true)))).isEmpty()
    }

    // Moved

    @Test
    fun moved_aSelectedMeeting_isMoved() {
        val moved = designReview.copy(begin = at(13, 30), end = at(14, 30))

        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(moved)))
            .containsExactly(ScheduleChange.Moved(date, designReview.key, at(13), at(14), at(13, 30), at(14, 30)))
    }

    @Test
    fun moved_aSelectedSoloBlock_isMovedToo() {
        val moved = dentist.copy(end = at(16, 30))

        assertThat(detect(listOf(dentist.snapshot(selected = true)), listOf(moved)))
            .containsExactly(ScheduleChange.Moved(date, dentist.key, at(15), at(16), at(15), at(16, 30)))
    }

    @Test
    fun moved_anUnselectedMeeting_isIgnored() {
        val moved = designReview.copy(begin = at(13, 30), end = at(14, 30))

        assertThat(detect(listOf(designReview.snapshot(selected = false)), listOf(moved))).isEmpty()
    }

    @Test
    fun moved_intoThePast_isIgnored() {
        val moved = designReview.copy(begin = at(10), end = at(11))

        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(moved))).isEmpty()
    }

    @Test
    fun moved_toASlotStillRunning_isMoved() {
        val moved = designReview.copy(begin = at(11, 30), end = at(12, 30))

        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(moved)))
            .containsExactly(ScheduleChange.Moved(date, designReview.key, at(13), at(14), at(11, 30), at(12, 30)))
    }

    // Cancelled

    @Test
    fun cancelled_aSelectedEventGoneBeforeItStarted_isCancelled() {
        assertThat(detect(listOf(designReview.snapshot(selected = true), oneOnOne.snapshot(selected = false)), listOf(oneOnOne)))
            .containsExactly(ScheduleChange.Cancelled(date, designReview.key, at(13), at(14)))
    }

    @Test
    fun cancelled_aSelectedEventNowStatusCanceled_isCancelled() {
        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(designReview.copy(status = EventStatus.CANCELED))))
            .containsExactly(ScheduleChange.Cancelled(date, designReview.key, at(13), at(14)))
    }

    @Test
    fun cancelled_aSelectedEventThatHadAlreadyStarted_isIgnored() {
        val started = testCalendarEvent(5, at(11), at(13))

        assertThat(detect(listOf(started.snapshot(selected = true)), emptyList())).isEmpty()
    }

    @Test
    fun cancelled_anUnselectedMeetingGone_isIgnored() {
        assertThat(detect(listOf(designReview.snapshot(selected = false)), emptyList())).isEmpty()
    }

    @Test
    fun cancelled_aSelectedSoloBlockGone_isCancelled() {
        assertThat(detect(listOf(dentist.snapshot(selected = true)), emptyList()))
            .containsExactly(ScheduleChange.Cancelled(date, dentist.key, at(15), at(16)))
    }

    // Declined by me

    @Test
    fun declined_aSelectedMeetingNowDeclined_isDeclined() {
        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(designReview.copy(selfStatus = SelfStatus.DECLINED))))
            .containsExactly(ScheduleChange.Declined(date, designReview.key, at(13), at(14)))
    }

    @Test
    fun declined_andMoved_isReportedOnlyAsDeclined() {
        val declinedAndMoved = designReview.copy(selfStatus = SelfStatus.DECLINED, begin = at(17), end = at(18))

        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(declinedAndMoved)))
            .containsExactly(ScheduleChange.Declined(date, designReview.key, at(17), at(18)))
    }

    @Test
    fun declined_alreadyAtShareTime_isIgnored() {
        val declined = designReview.copy(selfStatus = SelfStatus.DECLINED)

        assertThat(detect(listOf(declined.snapshot(selected = true)), listOf(declined))).isEmpty()
    }

    @Test
    fun declined_anUnselectedMeeting_isIgnored() {
        assertThat(detect(listOf(designReview.snapshot(selected = false)), listOf(designReview.copy(selfStatus = SelfStatus.DECLINED)))).isEmpty()
    }

    @Test
    fun declined_aMeetingAlreadyOver_isIgnored() {
        val over = testCalendarEvent(5, at(9), at(10))

        assertThat(detect(listOf(over.snapshot(selected = true)), listOf(over.copy(selfStatus = SelfStatus.DECLINED)))).isEmpty()
    }

    // Ignored

    @Test
    fun ignored_titleColourAndAttendeeEdits() {
        val edited = designReview.copy(title = "Design review (v2)", color = 0xFF0B8043.toInt(), location = "Room 4", humanAttendees = 5, selfStatus = SelfStatus.ACCEPTED)

        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(edited))).isEmpty()
    }

    @Test
    fun ignored_aSyncRewriteWithIdenticalValues() {
        val baseline = listOf(designReview.snapshot(selected = true), dentist.snapshot(selected = false), oneOnOne.snapshot(selected = true))

        assertThat(detect(baseline, listOf(designReview.copy(eventId = 42), dentist, oneOnOne))).isEmpty()
    }

    @Test
    fun ignored_eventsAlreadyOver() {
        val over = testCalendarEvent(5, at(9), at(10))

        assertThat(detect(listOf(over.snapshot(selected = true)), emptyList())).isEmpty()
        assertThat(detect(listOf(over.snapshot(selected = true)), listOf(over.copy(begin = at(8), end = at(9))))).isEmpty()
    }

    @Test
    fun ignored_allDayEvents_selectedOrNot() {
        val holiday = testCalendarEvent(6, at(0), at(0).plusSeconds(86_400), allDay = true, meeting = false)
        val laterAllDay = holiday.copy(key = holiday.key.copy(eventId = 7), eventId = 7)

        assertThat(detect(listOf(holiday.snapshot(selected = true)), emptyList())).isEmpty()
        assertThat(detect(listOf(holiday.snapshot(selected = true)), listOf(holiday.copy(end = at(0).plusSeconds(172_800))))).isEmpty()
        assertThat(detect(emptyList(), listOf(laterAllDay))).isEmpty()
    }

    // Replaced at identical times: the key changed ("this and following events", delete + recreate), the slot didn't

    @Test
    fun ignored_aSelectedEventReplacedAtTheSameTimes_isNeitherCancelledNorNew() {
        val recreated = testCalendarEvent(9, designReview.begin, designReview.end, title = "Design review (weekly)")

        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(recreated))).isEmpty()
    }

    @Test
    fun ignored_anUnselectedMeetingReplacedAtTheSameTimes_isNotNew() {
        val recreated = testCalendarEvent(9, oneOnOne.begin, oneOnOne.end)

        assertThat(detect(listOf(oneOnOne.snapshot(selected = false)), listOf(recreated))).isEmpty()
    }

    @Test
    fun replaced_atDifferentTimes_isStillCancelledAndNew() {
        val recreated = testCalendarEvent(9, at(13, 30), at(14, 30))

        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(recreated))).containsExactly(
            ScheduleChange.Cancelled(date, designReview.key, at(13), at(14)),
            ScheduleChange.New(date, recreated.key, recreated.begin, recreated.end),
        )
    }

    @Test
    fun replaced_pairsOneToOne_soASecondMeetingInTheSlotIsNew() {
        val recreated = testCalendarEvent(9, designReview.begin, designReview.end)
        val doubleBooked = testCalendarEvent(10, designReview.begin, designReview.end)

        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(recreated, doubleBooked)))
            .containsExactly(ScheduleChange.New(date, doubleBooked.key, doubleBooked.begin, doubleBooked.end))
    }

    @Test
    fun replaced_whileTheOriginalIsStillThere_isNew() {
        val doubleBooked = testCalendarEvent(9, designReview.begin, designReview.end)

        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(designReview, doubleBooked)))
            .containsExactly(ScheduleChange.New(date, doubleBooked.key, doubleBooked.begin, doubleBooked.end))
    }

    @Test
    fun replaced_aSoloBlockGoneWithAnInviteInItsSlot_isStillNew() {
        val hold = testCalendarEvent(4, at(14), at(15), meeting = false)
        val invite = testCalendarEvent(9, at(14), at(15))

        assertThat(detect(listOf(hold.snapshot(selected = false)), listOf(invite)))
            .containsExactly(ScheduleChange.New(date, invite.key, invite.begin, invite.end))
    }

    @Test
    fun ignored_aSelectedSoloBlockRecreatedAtTheSameTimes_isSilent() {
        val recreated = testCalendarEvent(9, dentist.begin, dentist.end, meeting = false)

        assertThat(detect(listOf(dentist.snapshot(selected = true)), listOf(recreated))).isEmpty()
    }

    @Test
    fun replaced_twoGoneFromOneSlot_theSelectedOneTakesTheArrival() {
        val duplicate = testCalendarEvent(4, designReview.begin, designReview.end)
        val recreated = testCalendarEvent(9, designReview.begin, designReview.end)

        assertThat(detect(listOf(duplicate.snapshot(selected = false), designReview.snapshot(selected = true)), listOf(recreated))).isEmpty()
    }

    @Test
    fun replaced_byACancelledEvent_isStillCancelled() {
        val cancelledCopy = testCalendarEvent(9, designReview.begin, designReview.end).copy(status = EventStatus.CANCELED)

        assertThat(detect(listOf(designReview.snapshot(selected = true)), listOf(cancelledCopy)))
            .containsExactly(ScheduleChange.Cancelled(date, designReview.key, at(13), at(14)))
    }

    @Test
    fun changes_areOrderedByTheTimeTheyAreAbout() {
        val invite = testCalendarEvent(9, at(15), at(15, 30))
        val moved = oneOnOne.copy(begin = at(12, 30), end = at(13))
        val baseline = listOf(designReview.snapshot(selected = true), oneOnOne.snapshot(selected = true))

        assertThat(detect(baseline, listOf(invite, moved))).containsExactly(
            ScheduleChange.Moved(date, oneOnOne.key, at(16), at(16, 30), at(12, 30), at(13)),
            ScheduleChange.Cancelled(date, designReview.key, at(13), at(14)),
            ScheduleChange.New(date, invite.key, invite.begin, invite.end),
        )
    }
}

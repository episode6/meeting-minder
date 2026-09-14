package com.episode6.meetingminder.alarm

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.db.SelectedEventEntity
import com.episode6.meetingminder.data.db.toSelectedEventEntity
import com.episode6.meetingminder.model.testCalendarEvent
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The pure reconcile and alarm-time math behind "Set alarms" (TODO.md §4.4). */
class AlarmReconcilerTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val now = Instant.parse("2026-09-14T08:35:00Z")
    private val leadTime = Duration.ofMinutes(5)
    private var nextSound = 100
    private val soundIndex = { nextSound++ }

    private fun at(hour: Int, minute: Int = 0): Instant = Instant.parse("2026-09-14T%02d:%02d:00Z".format(hour, minute))

    private val standup = testCalendarEvent(1, at(9), at(9, 30), title = "Daily standup")
    private val designReview = testCalendarEvent(2, at(10), at(11), title = "Design review")
    private val earlyBird = testCalendarEvent(3, at(8), at(8, 30), title = "Early bird")

    private fun selection(event: com.episode6.meetingminder.model.CalendarEvent) = event.toSelectedEventEntity(today)

    private fun scheduledRow(event: com.episode6.meetingminder.model.CalendarEvent, alarmId: Long, sound: Int = 7) = ScheduledAlarmEntity(
        alarmId = alarmId,
        date = today,
        eventId = event.key.eventId,
        instanceTime = event.key.instanceTime,
        fireAt = alarmTimeFor(event.begin.toEpochMilli(), leadTime),
        title = event.title,
        beginMillis = event.begin.toEpochMilli(),
        endMillis = event.end.toEpochMilli(),
        soundIndex = sound,
        location = event.location,
    )

    private fun reconcile(
        selected: List<SelectedEventEntity>,
        fresh: List<com.episode6.meetingminder.model.CalendarEvent> = listOf(standup, designReview, earlyBird),
        scheduled: List<ScheduledAlarmEntity> = emptyList(),
        at: Instant = now,
    ) = reconcileAlarms(today, selected, fresh, scheduled, leadTime, at, soundIndex)

    @Test
    fun alarmTime_isBeginMinusLeadTime() {
        assertThat(alarmTimeFor(at(9).toEpochMilli(), leadTime)).isEqualTo(at(8, 55).toEpochMilli())
        assertThat(alarmTimeFor(at(9).toEpochMilli(), Duration.ofMinutes(15))).isEqualTo(at(8, 45).toEpochMilli())
    }

    @Test
    fun newSelections_areScheduledWithFreshSoundIndexes() {
        val result = reconcile(selected = listOf(selection(standup), selection(designReview)))

        assertThat(result).isEqualTo(
            AlarmReconciliation(
                schedule = listOf(
                    scheduledRow(standup, alarmId = 0, sound = 100),
                    scheduledRow(designReview, alarmId = 0, sound = 101),
                ),
            ),
        )
        assertThat(result.armedCount).isEqualTo(2)
    }

    @Test
    fun selectionWhoseAlarmTimeHasPassed_isSkippedAndCounted() {
        // 8:00 − 5 min = 7:55, before 8:35 now; standup's 8:55 is still ahead
        val result = reconcile(selected = listOf(selection(earlyBird), selection(standup)))

        assertThat(result.schedule).containsExactly(scheduledRow(standup, alarmId = 0, sound = 100))
        assertThat(result.skipped).containsExactly(selection(earlyBird))
        assertThat(result.armedCount).isEqualTo(1)
    }

    @Test
    fun alarmTimeExactlyNow_countsAsPast() {
        val result = reconcile(selected = listOf(selection(standup)), at = at(8, 55))

        assertThat(result.schedule).isEmpty()
        assertThat(result.skipped).containsExactly(selection(standup))
    }

    @Test
    fun unchangedScheduledRow_isKept() {
        val row = scheduledRow(standup, alarmId = 5)

        val result = reconcile(selected = listOf(selection(standup)), scheduled = listOf(row))

        assertThat(result).isEqualTo(AlarmReconciliation(keep = listOf(row)))
    }

    @Test
    fun deselectedEvent_hasItsRowCancelled() {
        val row = scheduledRow(standup, alarmId = 5)

        val result = reconcile(selected = emptyList(), scheduled = listOf(row))

        assertThat(result).isEqualTo(AlarmReconciliation(cancel = listOf(row)))
        assertThat(result.armedCount).isEqualTo(0)
    }

    @Test
    fun movedEvent_isRetimedInPlace_keepingItsAlarmIdAndSound() {
        val row = scheduledRow(standup, alarmId = 5, sound = 42)
        val moved = standup.copy(begin = at(9, 30), end = at(10))

        val result = reconcile(selected = listOf(selection(standup)), fresh = listOf(moved), scheduled = listOf(row))

        assertThat(result).isEqualTo(
            AlarmReconciliation(
                retime = listOf(
                    row.copy(fireAt = at(9, 25).toEpochMilli(), beginMillis = at(9, 30).toEpochMilli(), endMillis = at(10).toEpochMilli()),
                ),
            ),
        )
    }

    @Test
    fun movedEvent_usesTheStoredTimesWhenTheProviderNoLongerHasIt() {
        val row = scheduledRow(standup, alarmId = 5)

        val result = reconcile(selected = listOf(selection(standup)), fresh = emptyList(), scheduled = listOf(row))

        assertThat(result).isEqualTo(AlarmReconciliation(keep = listOf(row)))
    }

    @Test
    fun eventMovedIntoThePast_isCancelledAndCountedAsSkipped() {
        val row = scheduledRow(standup, alarmId = 5)
        val movedEarlier = standup.copy(begin = at(8, 30), end = at(9))

        val result = reconcile(selected = listOf(selection(standup)), fresh = listOf(movedEarlier), scheduled = listOf(row))

        assertThat(result.cancel).containsExactly(row)
        assertThat(result.skipped).containsExactly(selection(movedEarlier))
        assertThat(result.retime).isEmpty()
    }

    @Test
    fun retitledEvent_isRetimedSoTheStoredTitleFollows() {
        val row = scheduledRow(standup, alarmId = 5)
        val retitled = standup.copy(title = "Standup (moved to room 4)")

        val result = reconcile(selected = listOf(selection(standup)), fresh = listOf(retitled), scheduled = listOf(row))

        assertThat(result.retime).containsExactly(row.copy(title = "Standup (moved to room 4)"))
    }

    @Test
    fun onlyScheduledRowsCount_aCancelledRowForASelectionMeansScheduleANewOne() {
        // the DAO never hands the reconcile non-SCHEDULED rows, but a caller passing one
        // must not get its stale row "kept"
        val cancelled = scheduledRow(standup, alarmId = 5).copy(state = AlarmState.CANCELLED)

        val result = reconcile(selected = listOf(selection(standup)), scheduled = emptyList())

        assertThat(result.schedule).containsExactly(scheduledRow(standup, alarmId = 0, sound = 100))
        assertThat(result.keep).isEmpty()
        assertThat(cancelled.state).isEqualTo(AlarmState.CANCELLED)
    }

    @Test
    fun snoozedRow_stillSelected_isKept_ratherThanReadAsMovedIntoThePast() {
        // it rang at 8:55 and was snoozed to 9:01; "Set alarms" is tapped again at 8:58
        val snoozed = scheduledRow(standup, alarmId = 5).copy(state = AlarmState.SNOOZED, fireAt = at(9, 1).toEpochMilli())

        val result = reconcile(selected = listOf(selection(standup)), scheduled = listOf(snoozed), at = at(8, 58))

        assertThat(result).isEqualTo(AlarmReconciliation(keep = listOf(snoozed)))
    }

    @Test
    fun snoozedRow_deselected_isCancelled() {
        val snoozed = scheduledRow(standup, alarmId = 5).copy(state = AlarmState.SNOOZED, fireAt = at(9, 1).toEpochMilli())

        val result = reconcile(selected = emptyList(), scheduled = listOf(snoozed), at = at(8, 58))

        assertThat(result).isEqualTo(AlarmReconciliation(cancel = listOf(snoozed)))
    }

    @Test
    fun snoozedRow_whoseEventMovedLater_isRetimedToAFreshAlarm() {
        val snoozed = scheduledRow(standup, alarmId = 5, sound = 42)
            .copy(state = AlarmState.SNOOZED, fireAt = at(9, 1).toEpochMilli(), timedOut = true)
        val moved = standup.copy(begin = at(10), end = at(10, 30))

        val result = reconcile(selected = listOf(selection(standup)), fresh = listOf(moved), scheduled = listOf(snoozed), at = at(8, 58))

        assertThat(result).isEqualTo(
            AlarmReconciliation(
                retime = listOf(
                    snoozed.copy(
                        state = AlarmState.SCHEDULED,
                        timedOut = false,
                        fireAt = at(9, 55).toEpochMilli(),
                        beginMillis = at(10).toEpochMilli(),
                        endMillis = at(10, 30).toEpochMilli(),
                    ),
                ),
            ),
        )
    }

    @Test
    fun newAlarm_carriesTheEventsLocation_forTheRingingScreen() {
        val inRoom = standup.copy(location = "Room 4")

        val result = reconcile(selected = listOf(selection(inRoom)), fresh = listOf(inRoom))

        assertThat(result.schedule.single().location).isEqualTo("Room 4")
    }

    @Test
    fun relocatedEvent_isRetimedSoTheStoredLocationFollows() {
        val inRoom = standup.copy(location = "Room 4")
        val row = scheduledRow(inRoom, alarmId = 5)

        val result = reconcile(selected = listOf(selection(inRoom)), fresh = listOf(inRoom.copy(location = "Room 9")), scheduled = listOf(row))

        assertThat(result).isEqualTo(AlarmReconciliation(retime = listOf(row.copy(location = "Room 9"))))
    }

    @Test
    fun longerLeadTime_movesTheAlarmEarlier() {
        val result = reconcileAlarms(today, listOf(selection(designReview)), listOf(designReview), emptyList(), Duration.ofMinutes(30), now, soundIndex)

        assertThat(result.schedule.single().fireAt).isEqualTo(at(9, 30).toEpochMilli())
    }
}

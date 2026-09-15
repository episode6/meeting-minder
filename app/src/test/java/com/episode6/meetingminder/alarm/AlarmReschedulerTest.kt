package com.episode6.meetingminder.alarm

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AlarmReschedulerTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val now = Instant.parse("2026-09-14T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun row(id: Long, beginOffsetMinutes: Long, state: AlarmState = AlarmState.SCHEDULED): ScheduledAlarmEntity {
        val begin = now.plusSeconds(beginOffsetMinutes * 60).toEpochMilli()
        return ScheduledAlarmEntity(
            alarmId = id, date = today, eventId = id, instanceTime = 0,
            fireAt = begin - 300_000, title = "Event $id", beginMillis = begin, endMillis = begin + 1_800_000,
            soundIndex = 1, state = state,
        )
    }

    @Test
    fun rearmsEveryScheduledRowWhoseEventHasNotEnded() = runTest {
        val future = row(1, beginOffsetMinutes = 60)
        // alarm time passed while the phone was off, but the meeting is still on: re-arm
        // (fires immediately) rather than lose it
        val underWay = row(2, beginOffsetMinutes = -10)
        val over = row(3, beginOffsetMinutes = -120)
        val cancelled = row(4, beginOffsetMinutes = 30, state = AlarmState.CANCELLED)
        val fired = row(5, beginOffsetMinutes = 45, state = AlarmState.FIRED)
        val scheduler = FakeAlarmScheduler()

        val count = AlarmRescheduler(FakeScheduledAlarmDao(listOf(future, underWay, over, cancelled, fired)), scheduler, clock).rescheduleAll()

        assertThat(count).isEqualTo(2)
        assertThat(scheduler.armed.values.toList()).containsExactly(future, underWay)
    }

    @Test
    fun aSnoozedAlarm_isRearmedToo() = runTest {
        // snoozed just before a reboot: its snooze time has passed, so it rings as soon as it's re-armed
        val snoozed = row(1, beginOffsetMinutes = 10, state = AlarmState.SNOOZED)
        val scheduler = FakeAlarmScheduler()

        val count = AlarmRescheduler(FakeScheduledAlarmDao(listOf(snoozed)), scheduler, clock).rescheduleAll()

        assertThat(count).isEqualTo(1)
        assertThat(scheduler.armed.values.toList()).containsExactly(snoozed)
    }

    @Test
    fun withoutTheExactAlarmGrant_armsNothing() = runTest {
        val scheduler = FakeAlarmScheduler(canSchedule = false)

        val count = AlarmRescheduler(FakeScheduledAlarmDao(listOf(row(1, 60))), scheduler, clock).rescheduleAll()

        assertThat(count).isEqualTo(0)
        assertThat(scheduler.armed).isEmpty()
    }

    @Test
    fun aRowTheOsRefuses_isNotCounted() = runTest {
        val scheduler = FakeAlarmScheduler(refuse = true)

        val count = AlarmRescheduler(FakeScheduledAlarmDao(listOf(row(1, 60))), scheduler, clock).rescheduleAll()

        assertThat(count).isEqualTo(0)
    }
}

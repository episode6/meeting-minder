package com.episode6.meetingminder.alarm

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.Settings
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.RingingAlarm
import com.episode6.meetingminder.model.SCHEDULE_CHANGE_ALARM_EVENT_ID
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.model.ScheduleChangeAlert
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Every `scheduled_alarm` transition of a ringing alarm (TODO.md §4.4). */
class AlarmRingerTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val now = Instant.parse("2026-09-14T08:55:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val scheduler = FakeAlarmScheduler()
    private val changeAlerts = FakeScheduleChangeAlertContent()
    private val settings = FakeSettingsRepository(Settings(snoozeLength = Duration.ofMinutes(4), autoTimeout = Duration.ofMinutes(1)))

    private fun row(state: AlarmState = AlarmState.SCHEDULED, timedOut: Boolean = false) = ScheduledAlarmEntity(
        alarmId = 3, date = today, eventId = 10, instanceTime = 0, fireAt = now.toEpochMilli(), title = "Daily standup",
        beginMillis = Instant.parse("2026-09-14T09:00:00Z").toEpochMilli(),
        endMillis = Instant.parse("2026-09-14T09:30:00Z").toEpochMilli(),
        soundIndex = 77, state = state, location = "Room 4", timedOut = timedOut,
    )

    private fun ringer(dao: FakeScheduledAlarmDao, scheduler: AlarmScheduler = this.scheduler) = AlarmRinger(dao, scheduler, settings, clock, changeAlerts)

    @Test
    fun fire_marksTheRowFired_andReturnsWhatToRing() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row()))

        val ringing = ringer(dao).fire(3)

        assertThat(ringing).isEqualTo(
            RingingAlarm(
                alarmId = 3, date = today, key = EventKey(10, 0), title = "Daily standup", location = "Room 4",
                begin = Instant.parse("2026-09-14T09:00:00Z"), end = Instant.parse("2026-09-14T09:30:00Z"),
                soundIndex = 77, snoozeLength = Duration.ofMinutes(4),
            ),
        )
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.FIRED)
    }

    @Test
    fun fire_ringsASnoozedAlarmWhenItComesBack() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(AlarmState.SNOOZED)))

        assertThat(ringer(dao).fire(3)?.alarmId).isEqualTo(3L)
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.FIRED)
    }

    @Test
    fun fire_ofARowThatIsNoLongerArmed_ringsNothing() = runTest {
        for (state in listOf(AlarmState.FIRED, AlarmState.DISMISSED, AlarmState.CANCELLED)) {
            val dao = FakeScheduledAlarmDao(listOf(row(state)))

            assertThat(ringer(dao).fire(3), state.name).isNull()
            assertThat(dao.rows.getValue(3).state).isEqualTo(state)
        }
        assertThat(ringer(FakeScheduledAlarmDao()).fire(99)).isNull()
    }

    @Test
    fun snooze_rearmsTheSameAlarmIdAtNowPlusTheSnoozeLength() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(AlarmState.FIRED)))

        val result = ringer(dao).snooze(3)

        val snoozed = row(AlarmState.SNOOZED).copy(fireAt = Instant.parse("2026-09-14T08:59:00Z").toEpochMilli())
        assertThat(result).isEqualTo(SnoozeResult.SNOOZED)
        assertThat(dao.rows.getValue(3)).isEqualTo(snoozed)
        assertThat(scheduler.armed.getValue(3)).isEqualTo(snoozed)
    }

    @Test
    fun snooze_ofAnAlarmThatIsNotRinging_changesNothing() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(AlarmState.SNOOZED)))

        assertThat(ringer(dao).snooze(3)).isEqualTo(SnoozeResult.NOT_RINGING)
        assertThat(dao.rows.getValue(3)).isEqualTo(row(AlarmState.SNOOZED))
        assertThat(scheduler.armed).isEmpty()
    }

    @Test
    fun snooze_theOsRefuses_cancelsTheRow() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(AlarmState.FIRED)))

        val result = ringer(dao, FakeAlarmScheduler(refuse = true)).snooze(3)

        assertThat(result).isEqualTo(SnoozeResult.REFUSED)
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.CANCELLED)
    }

    @Test
    fun dismiss_marksARingingAlarmDismissed() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(AlarmState.FIRED)))

        assertThat(ringer(dao).dismiss(3)).isTrue()
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.DISMISSED)
        assertThat(scheduler.armed).isEmpty()
    }

    @Test
    fun dismiss_ofAnAlarmThatIsNotRinging_changesNothing() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(AlarmState.SCHEDULED)))

        assertThat(ringer(dao).dismiss(3)).isFalse()
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.SCHEDULED)
    }

    @Test
    fun timeOut_theFirstTime_snoozesAndRemembersIt() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(AlarmState.FIRED)))

        assertThat(ringer(dao).timeOut(3)).isEqualTo(TimeoutResult.SNOOZED)
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.SNOOZED)
        assertThat(dao.rows.getValue(3).timedOut).isTrue()
        assertThat(scheduler.armed.keys.toList()).isEqualTo(listOf(3L))
    }

    @Test
    fun timeOut_againAfterThat_givesUp() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(AlarmState.FIRED, timedOut = true)))

        assertThat(ringer(dao).timeOut(3)).isEqualTo(TimeoutResult.GAVE_UP)
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.DISMISSED)
        assertThat(scheduler.armed).isEmpty()
    }

    @Test
    fun timeOut_aSnoozeTheOsRefuses_isReported() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(AlarmState.FIRED)))

        assertThat(ringer(dao, FakeAlarmScheduler(refuse = true)).timeOut(3)).isEqualTo(TimeoutResult.REFUSED)
    }

    @Test
    fun timeOut_ofAnAlarmThatIsNotRinging_changesNothing() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(AlarmState.DISMISSED)))

        assertThat(ringer(dao).timeOut(3)).isEqualTo(TimeoutResult.NOT_RINGING)
    }

    @Test
    fun autoTimeout_comesFromSettings() = runTest {
        assertThat(ringer(FakeScheduledAlarmDao()).autoTimeout()).isEqualTo(Duration.ofMinutes(1))
    }

    private fun changeRow(state: AlarmState = AlarmState.SCHEDULED) = row(state).copy(eventId = SCHEDULE_CHANGE_ALARM_EVENT_ID)

    private val alert = ScheduleChangeAlert(
        listOf(ScheduleChange.New(today, EventKey(8, 0), Instant.parse("2026-09-14T15:00:00Z"), Instant.parse("2026-09-14T15:30:00Z"))),
        syncsBusyCalendar = true,
    )

    @Test
    fun fire_aScheduleChangeAlert_loadsWhatChanged() = runTest {
        changeAlerts.alerts[today] = alert
        val dao = FakeScheduledAlarmDao(listOf(changeRow()))

        assertThat(ringer(dao).fire(3)?.scheduleChange).isEqualTo(alert)
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.FIRED)
    }

    @Test
    fun fire_aScheduleChangeAlertWithNothingLeftToSay_ringsNothing_andIsDone() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(changeRow()))

        assertThat(ringer(dao).fire(3)).isNull()
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.DISMISSED)
    }

    @Test
    fun dismiss_aScheduleChangeAlert_acknowledgesTheDay_anAlarmDoesNot() = runTest {
        ringer(FakeScheduledAlarmDao(listOf(row(AlarmState.FIRED)))).dismiss(3)
        assertThat(changeAlerts.acknowledged).isEmpty()

        ringer(FakeScheduledAlarmDao(listOf(changeRow(AlarmState.FIRED)))).dismiss(3)
        assertThat(changeAlerts.acknowledged).isEqualTo(listOf(today))
    }

    @Test
    fun timeOut_aScheduleChangeAlert_expiresWithoutSnoozingOrAcknowledging() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(changeRow(AlarmState.FIRED)))

        assertThat(ringer(dao).timeOut(3)).isEqualTo(TimeoutResult.EXPIRED)
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.DISMISSED)
        assertThat(scheduler.armed).isEmpty()
        assertThat(changeAlerts.acknowledged).isEmpty()
    }
}

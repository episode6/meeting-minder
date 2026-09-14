package com.episode6.meetingminder.alarm

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.model.RingingAlarm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The ringing service's rules (TODO.md §4.4): the foreground deadline, the queue, awaited writes, the auto-timeout. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmRingingSessionTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val now = Instant.parse("2026-09-14T08:55:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val autoTimeoutMillis = Duration.ofMinutes(3).toMillis()
    private val scheduler = FakeAlarmScheduler()
    private val outputs = RecordingOutputs()

    private fun row(id: Long, state: AlarmState = AlarmState.SCHEDULED, timedOut: Boolean = false) = ScheduledAlarmEntity(
        alarmId = id, date = today, eventId = id, instanceTime = 0, fireAt = now.toEpochMilli(), title = "Meeting $id",
        beginMillis = now.plusSeconds(300).toEpochMilli(), endMillis = now.plusSeconds(2_100).toEpochMilli(),
        soundIndex = id.toInt(), state = state, timedOut = timedOut,
    )

    private fun TestScope.session(dao: ScheduledAlarmDao, scheduler: AlarmScheduler = this@AlarmRingingSessionTest.scheduler) =
        AlarmRingingSession(backgroundScope, AlarmRinger(dao, scheduler, FakeSettingsRepository(), clock), outputs)

    private class RecordingOutputs : RingingOutputs {
        val events = mutableListOf<String>()
        val published = mutableListOf<RingingAlarm?>()

        override fun showRinging(alarm: RingingAlarm, alert: Boolean) {
            events += "ringing:${alarm.alarmId}:$alert"
        }

        override fun showPlaceholder() {
            events += "placeholder"
        }

        override fun startSound(alarm: RingingAlarm) {
            events += "sound:${alarm.alarmId}"
        }

        override fun stopSound() {
            events += "silence"
        }

        override fun publish(ringing: RingingAlarm?) {
            published += ringing
            events += "publish:${ringing?.alarmId}"
        }

        override fun postMissed(alarm: RingingAlarm) {
            events += "missed:${alarm.alarmId}"
        }

        override fun stop() {
            events += "stop"
        }
    }

    /** A DAO whose reads take [millis], like a cold database in a process the alarm just started. */
    private class SlowDao(private val delegate: FakeScheduledAlarmDao, private val millis: Long) : ScheduledAlarmDao by delegate {
        override suspend fun byId(alarmId: Long): ScheduledAlarmEntity? {
            delay(millis)
            return delegate.byId(alarmId)
        }
    }

    @Test
    fun fire_marksTheRowFired_publishesIt_goesForeground_andRings() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(1)))
        val session = session(dao)

        session.fire(1)
        runCurrent()

        assertThat(outputs.events).containsExactly("publish:1", "ringing:1:true", "sound:1")
        assertThat(dao.rows.getValue(1).state).isEqualTo(AlarmState.FIRED)
        assertThat(session.ringing?.title).isEqualTo("Meeting 1")
    }

    @Test
    fun aFireWithNothingToRing_stillAnswersTheForegroundStart_thenStops() = runTest {
        val session = session(FakeScheduledAlarmDao(listOf(row(1, AlarmState.CANCELLED))))

        session.fire(1)
        runCurrent()
        advanceTimeBy(FOREGROUND_WATCHDOG_MILLIS * 2)

        assertThat(outputs.events).containsExactly("placeholder", "publish:null", "stop")
    }

    @Test
    fun aSlowLoad_postsAPlaceholderBeforeTheForegroundDeadline_thenRings() = runTest {
        val session = session(SlowDao(FakeScheduledAlarmDao(listOf(row(1))), millis = 5_000))

        session.fire(1)
        advanceTimeBy(FOREGROUND_WATCHDOG_MILLIS + 1)

        assertThat(outputs.events).containsExactly("placeholder")

        advanceTimeBy(5_000)

        assertThat(outputs.events).containsExactly("placeholder", "publish:1", "ringing:1:true", "sound:1")
    }

    @Test
    fun aSecondAlarmWhileOneRings_isAnsweredInTheForegroundAtOnce_andWaitsItsTurn() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(1), row(2)))
        val session = session(dao)
        session.fire(1)
        runCurrent()
        outputs.events.clear()

        session.fire(2)

        assertThat(outputs.events).containsExactly("ringing:1:false")

        runCurrent()

        assertThat(outputs.events).containsExactly("ringing:1:false")
        assertThat(dao.rows.getValue(2).state).isEqualTo(AlarmState.FIRED)

        session.dismiss(1)
        runCurrent()

        assertThat(outputs.events).containsExactly("ringing:1:false", "silence", "publish:2", "ringing:2:true", "sound:2")
        assertThat(dao.rows.getValue(1).state).isEqualTo(AlarmState.DISMISSED)
    }

    @Test
    fun dismiss_silencesFirst_writesTheRow_thenStops() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(1)))
        val session = session(dao)
        session.fire(1)
        runCurrent()
        outputs.events.clear()

        session.dismiss(1)
        runCurrent()

        assertThat(outputs.events).containsExactly("silence", "publish:null", "stop")
        assertThat(dao.rows.getValue(1).state).isEqualTo(AlarmState.DISMISSED)
        assertThat(session.ringing).isNull()
    }

    @Test
    fun snooze_rearmsTheSameAlarmIdAtTheSnoozeTime_thenStops() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(1)))
        val session = session(dao)
        session.fire(1)
        runCurrent()
        outputs.events.clear()

        session.snooze(1)
        runCurrent()

        val snoozed = row(1, AlarmState.SNOOZED).copy(fireAt = now.plusSeconds(120).toEpochMilli())
        assertThat(outputs.events).containsExactly("silence", "publish:null", "stop")
        assertThat(dao.rows.getValue(1)).isEqualTo(snoozed)
        assertThat(scheduler.armed.getValue(1)).isEqualTo(snoozed)
    }

    @Test
    fun aSnoozeTheOsRefusesToArm_isReportedAsMissed() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(1)))
        val session = session(dao, FakeAlarmScheduler(refuse = true))
        session.fire(1)
        runCurrent()
        outputs.events.clear()

        session.snooze(1)
        runCurrent()

        assertThat(outputs.events).containsExactly("silence", "missed:1", "publish:null", "stop")
        assertThat(dao.rows.getValue(1).state).isEqualTo(AlarmState.CANCELLED)
    }

    @Test
    fun unanswered_snoozesItselfOnceAfterTheAutoTimeout() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(1)))
        val session = session(dao)
        session.fire(1)
        runCurrent()
        outputs.events.clear()

        advanceTimeBy(autoTimeoutMillis - 1)

        assertThat(outputs.events).isEmpty()

        advanceTimeBy(2)

        assertThat(outputs.events).containsExactly("silence", "publish:null", "stop")
        assertThat(dao.rows.getValue(1).state).isEqualTo(AlarmState.SNOOZED)
        assertThat(dao.rows.getValue(1).timedOut).isTrue()
        assertThat(scheduler.armed.keys.toList()).containsExactly(1L)
    }

    @Test
    fun unansweredAgainAfterTimingOut_givesUpWithAMissedNotification() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(1, AlarmState.SNOOZED, timedOut = true)))
        val session = session(dao)
        session.fire(1)
        runCurrent()
        outputs.events.clear()

        advanceTimeBy(autoTimeoutMillis + 1)

        assertThat(outputs.events).containsExactly("silence", "missed:1", "publish:null", "stop")
        assertThat(dao.rows.getValue(1).state).isEqualTo(AlarmState.DISMISSED)
    }

    @Test
    fun answeringBeforeTheAutoTimeout_cancelsIt() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(1)))
        val session = session(dao)
        session.fire(1)
        runCurrent()
        session.dismiss(1)
        runCurrent()
        outputs.events.clear()

        advanceTimeBy(autoTimeoutMillis * 2)

        assertThat(outputs.events).isEmpty()
        assertThat(dao.rows.getValue(1).state).isEqualTo(AlarmState.DISMISSED)
    }

    @Test
    fun theNextQueuedAlarm_getsItsOwnFullAutoTimeout() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(1), row(2)))
        val session = session(dao)
        session.fire(1)
        session.fire(2)
        runCurrent()
        advanceTimeBy(autoTimeoutMillis - 10)
        session.dismiss(1)
        runCurrent()
        outputs.events.clear()

        advanceTimeBy(autoTimeoutMillis - 1)

        assertThat(outputs.events).isEmpty()

        advanceTimeBy(2)

        assertThat(dao.rows.getValue(2).state).isEqualTo(AlarmState.SNOOZED)
    }

    @Test
    fun aFireArrivingWhileTheLastAlarmIsSettling_keepsTheServiceRunning() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(1), row(2)))
        val session = session(dao)
        session.fire(1)
        runCurrent()
        outputs.events.clear()

        session.dismiss(1)
        session.fire(2)
        runCurrent()

        assertThat(outputs.events).containsExactly(
            "ringing:1:false",
            "silence",
            "publish:null",
            "publish:2",
            "ringing:2:true",
            "sound:2",
        )
    }

    @Test
    fun aCommandForAnAlarmThatIsNotRinging_writesNothing_andLetsTheIdleServiceStop() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(row(5)))
        val session = session(dao)

        session.dismiss(5)
        runCurrent()

        assertThat(outputs.events).containsExactly("publish:null", "stop")
        assertThat(dao.rows.getValue(5).state).isEqualTo(AlarmState.SCHEDULED)
    }

    @Test
    fun soundStarted_publishesItsName_forTheRingingAlarmOnly() = runTest {
        val session = session(FakeScheduledAlarmDao(listOf(row(1))))
        session.fire(1)
        runCurrent()

        session.onSoundStarted(1, "Argon")
        session.onSoundStarted(99, "Neon")

        assertThat(outputs.published.last()).isEqualTo(session.ringing)
        assertThat(session.ringing?.soundName).isEqualTo("Argon")
        assertThat(outputs.events.count { it.startsWith("publish") }).isEqualTo(2)
    }

    @Test
    fun release_silencesAndClearsTheScreen() = runTest {
        val session = session(FakeScheduledAlarmDao(listOf(row(1))))
        session.fire(1)
        runCurrent()
        outputs.events.clear()

        session.release()
        advanceTimeBy(autoTimeoutMillis * 2)

        assertThat(outputs.events).containsExactly("silence", "publish:null")
    }
}

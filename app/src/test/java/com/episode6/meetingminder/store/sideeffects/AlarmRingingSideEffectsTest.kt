package com.episode6.meetingminder.store.sideeffects

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.R
import com.episode6.meetingminder.alarm.AlarmNotifications
import com.episode6.meetingminder.alarm.AlarmRinger
import com.episode6.meetingminder.alarm.AlarmRingingCommands
import com.episode6.meetingminder.alarm.AlarmScheduler
import com.episode6.meetingminder.alarm.FakeAlarmScheduler
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.RingingAlarm
import com.episode6.meetingminder.store.DismissAlarm
import com.episode6.meetingminder.store.LoadDay
import com.episode6.meetingminder.store.SetRinging
import com.episode6.meetingminder.store.SnoozeAlarm
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Robolectric only for the "missed alarm" notification the undeliverable-snooze fallback posts. */
@RunWith(RobolectricTestRunner::class)
class AlarmRingingSideEffectsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val today = LocalDate.of(2026, 9, 14)
    private val now = Instant.parse("2026-09-14T08:55:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val dao = FakeScheduledAlarmDao(listOf(firedRow(3)))
    private val ringingState = TestAppState.copy(ringing = ringing(3))

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlarmNotifications.createChannels(context)
    }

    private fun firedRow(id: Long) = ScheduledAlarmEntity(
        alarmId = id, date = today, eventId = id, instanceTime = 0, fireAt = now.toEpochMilli(), title = "Standup",
        beginMillis = now.plusSeconds(300).toEpochMilli(), endMillis = now.plusSeconds(1_800).toEpochMilli(),
        soundIndex = 1, state = AlarmState.FIRED,
    )

    private fun ringing(id: Long) = RingingAlarm(
        alarmId = id, date = today, key = EventKey(id, 0), title = "Standup", location = null,
        begin = now.plusSeconds(300), end = now.plusSeconds(1_800), soundIndex = 1, snoozeLength = Duration.ofMinutes(2),
    )

    private class FakeCommands(private val deliver: Boolean) : AlarmRingingCommands {
        val sent = mutableListOf<String>()

        override fun snooze(alarmId: Long): Boolean = deliver.also { sent += "snooze:$alarmId" }

        override fun dismiss(alarmId: Long): Boolean = deliver.also { sent += "dismiss:$alarmId" }
    }

    private fun effect(commands: AlarmRingingCommands, scheduler: AlarmScheduler = FakeAlarmScheduler()) =
        object : AlarmRingingSideEffects {}.alarmRinging(commands, AlarmRinger(dao, scheduler, FakeSettingsRepository(), clock), context, clock)

    @Test
    fun snooze_isHandedToTheRingingService() = runTest {
        val commands = FakeCommands(deliver = true)

        val output = effect(commands).output(SnoozeAlarm(3), state = ringingState).toList()

        assertThat(output).isEmpty()
        assertThat(commands.sent).containsExactly("snooze:3")
        // the service writes the row, not this effect
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.FIRED)
    }

    @Test
    fun dismiss_isHandedToTheRingingService() = runTest {
        val commands = FakeCommands(deliver = true)

        val output = effect(commands).output(DismissAlarm(3), state = ringingState).toList()

        assertThat(output).isEmpty()
        assertThat(commands.sent).containsExactly("dismiss:3")
    }

    @Test
    fun anUndeliverableDismiss_isWrittenHere_andTheScreenCleared() = runTest {
        val output = effect(FakeCommands(deliver = false)).output(DismissAlarm(3), state = ringingState).toList()

        assertThat(output).containsExactly(SetRinging(null))
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.DISMISSED)
    }

    @Test
    fun anUndeliverableSnooze_isWrittenHere_andTheScreenCleared() = runTest {
        val output = effect(FakeCommands(deliver = false)).output(SnoozeAlarm(3), state = ringingState).toList()

        assertThat(output).containsExactly(SetRinging(null))
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.SNOOZED)
        assertThat(shadowOf(notificationManager).allNotifications).isEmpty()
    }

    @Test
    fun anUndeliverableSnooze_theOsRefusesToArm_isReportedAsMissed_likeTheServiceWould() = runTest {
        val output = effect(FakeCommands(deliver = false), FakeAlarmScheduler(refuse = true))
            .output(SnoozeAlarm(3), state = ringingState).toList()

        assertThat(output).containsExactly(SetRinging(null))
        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.CANCELLED)
        val missed = shadowOf(notificationManager).allNotifications.single()
        assertThat(shadowOf(missed).contentTitle.toString()).isEqualTo(context.getString(R.string.alarm_missed_title, "Standup"))
    }

    @Test
    fun anUndeliverableCommand_forAnAlarmTheScreenIsNotShowing_leavesTheScreenAlone() = runTest {
        val output = effect(FakeCommands(deliver = false)).output(DismissAlarm(3), state = TestAppState.copy(ringing = ringing(4))).toList()

        assertThat(output).isEmpty()
    }

    @Test
    fun otherActions_areIgnored() = runTest {
        val commands = FakeCommands(deliver = true)

        val output = effect(commands).output(LoadDay(today), state = ringingState).toList()

        assertThat(output).isEmpty()
        assertThat(commands.sent).isEmpty()
    }
}

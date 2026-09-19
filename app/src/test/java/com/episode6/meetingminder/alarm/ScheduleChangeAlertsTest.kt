package com.episode6.meetingminder.alarm

import android.provider.CalendarContract.Calendars
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.data.calendar.ShareMode
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.ChangeSnapshotEntity
import com.episode6.meetingminder.data.db.FakeChangeSnapshotDao
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.data.db.encodeScheduleChanges
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.SCHEDULE_CHANGE_ALARM_EVENT_ID
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.model.ScheduleChangeAlert
import com.episode6.meetingminder.monitor.FakeScheduleChangeNotifier
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/** The loud schedule-change alert's `scheduled_alarm` row (TODO.md §4.3): armed for now, one per day, and what it says when it fires. */
class ScheduleChangeAlertsTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val now = Instant.parse("2026-09-14T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val new = ScheduleChange.New(today, EventKey(8, 0), Instant.parse("2026-09-14T15:00:00Z"), Instant.parse("2026-09-14T15:30:00Z"))

    private val family = CalendarInfo(
        id = 5, accountName = "me@example.com", accountType = "com.google", displayName = "Family", color = 0, visible = true,
        syncEvents = true, ownerAccount = "me@example.com", isPrimary = false, accessLevel = Calendars.CAL_ACCESS_OWNER, canOrganizerRespond = true,
    )

    private val alarmDao = FakeScheduledAlarmDao()
    private val scheduler = FakeAlarmScheduler()
    private val snapshots = FakeChangeSnapshotDao()
    private val settings = FakeSettingsRepository()
    private val repository = FakeCalendarRepository(calendars = listOf(family))
    private val notifier = FakeScheduleChangeNotifier()
    private val commands = RecordingCommands()
    private val alerts = ScheduleChangeAlerts(alarmDao, scheduler, snapshots, settings, repository, notifier, commands, clock, Random(1))

    private class RecordingCommands(var deliver: Boolean = true) : AlarmRingingCommands {
        val sent = mutableListOf<String>()

        override fun snooze(alarmId: Long): Boolean = deliver.also { sent += "snooze:$alarmId" }

        override fun dismiss(alarmId: Long): Boolean = deliver.also { sent += "dismiss:$alarmId" }

        override fun silence(alarmId: Long): Boolean = deliver.also { sent += "silence:$alarmId" }
    }

    private suspend fun recordChanges(date: LocalDate, vararg changes: ScheduleChange) {
        snapshots.upsert(ChangeSnapshotEntity(date, takenAt = 1, eventsJson = "[]", changesJson = encodeScheduleChanges(changes.toList())))
    }

    @Test
    fun alert_writesTheDaysRow_thenArmsItForNow() = runTest {
        assertThat(alerts.alert(today)).isTrue()

        val row = alarmDao.rows.values.single()
        assertThat(row.eventId).isEqualTo(SCHEDULE_CHANGE_ALARM_EVENT_ID)
        assertThat(row.date).isEqualTo(today)
        assertThat(row.fireAt).isEqualTo(now.toEpochMilli())
        assertThat(row.state).isEqualTo(AlarmState.SCHEDULED)
        assertThat(scheduler.armed[row.alarmId]).isEqualTo(row)
    }

    @Test
    fun alert_again_reArmsTheSameRow_withAFreshSound_evenWhileItRings() = runTest {
        alerts.alert(today)
        val first = alarmDao.rows.values.single()
        alarmDao.setState(first.alarmId, AlarmState.FIRED)

        assertThat(alerts.alert(today)).isTrue()

        val second = alarmDao.rows.values.single()
        assertThat(second.alarmId).isEqualTo(first.alarmId)
        assertThat(second.state).isEqualTo(AlarmState.SCHEDULED)
        assertThat(second.soundIndex).isNotEqualTo(first.soundIndex)
        assertThat(scheduler.armed[second.alarmId]).isEqualTo(second)
    }

    @Test
    fun alert_withoutTheExactAlarmGrant_writesNothing() = runTest {
        scheduler.canSchedule = false

        assertThat(alerts.alert(today)).isFalse()
        assertThat(alarmDao.rows).isEmpty()
    }

    @Test
    fun alert_theOsRefusesToArm_isCancelled_andReported() = runTest {
        scheduler.refuse = true

        assertThat(alerts.alert(today)).isFalse()
        assertThat(alarmDao.rows.values.single().state).isEqualTo(AlarmState.CANCELLED)
    }

    @Test
    fun cancel_disarmsAnAlertThatHasNotFiredYet() = runTest {
        alerts.alert(today)
        val id = alarmDao.rows.values.single().alarmId

        alerts.cancel(today)

        assertThat(scheduler.cancelled).containsExactly(id)
        assertThat(alarmDao.rows.getValue(id).state).isEqualTo(AlarmState.CANCELLED)
        assertThat(commands.sent).isEmpty()
    }

    @Test
    fun cancel_whileItRings_asksTheServiceToDismissIt() = runTest {
        alerts.alert(today)
        val id = alarmDao.rows.values.single().alarmId
        alarmDao.setState(id, AlarmState.FIRED)

        alerts.cancel(today)

        assertThat(commands.sent).containsExactly("dismiss:$id")
        // the service writes the row when it dismisses
        assertThat(alarmDao.rows.getValue(id).state).isEqualTo(AlarmState.FIRED)
    }

    @Test
    fun cancel_whileItRings_writesTheRowItself_whenTheServiceCantBeReached() = runTest {
        commands.deliver = false
        alerts.alert(today)
        val id = alarmDao.rows.values.single().alarmId
        alarmDao.setState(id, AlarmState.FIRED)

        alerts.cancel(today)

        assertThat(alarmDao.rows.getValue(id).state).isEqualTo(AlarmState.DISMISSED)
    }

    @Test
    fun cancel_withNoAlert_orAnAnsweredOne_doesNothing() = runTest {
        alerts.cancel(today)
        alerts.alert(today)
        val id = alarmDao.rows.values.single().alarmId
        alarmDao.setState(id, AlarmState.DISMISSED)

        alerts.cancel(today)

        assertThat(scheduler.cancelled).isEmpty()
        assertThat(commands.sent).isEmpty()
    }

    @Test
    fun load_isTheDaysRecordedChanges() = runTest {
        recordChanges(today, new)

        assertThat(alerts.load(today)).isEqualTo(ScheduleChangeAlert(listOf(new), shareMode = ShareMode.TEXT))
    }

    @Test
    fun load_saysWhetherAReShareSyncsTheBusyCalendar() = runTest {
        recordChanges(today, new)
        settings.setBusySyncEnabled(true)
        settings.setBusySyncCalendar(family.id)

        assertThat(alerts.load(today)?.shareMode).isEqualTo(ShareMode.SYNC_AND_TEXT)

        settings.setBusySyncSendText(false)
        assertThat(alerts.load(today)?.shareMode).isEqualTo(ShareMode.SYNC_ONLY)

        repository.calendars = emptyList()
        assertThat(alerts.load(today)?.shareMode).isEqualTo(ShareMode.TEXT)

        repository.error = SecurityException("no calendar access")
        assertThat(alerts.load(today)?.shareMode).isEqualTo(ShareMode.TEXT)
    }

    @Test
    fun load_isNothing_whenTheDayIsNotShared_hasNoChangesLeft_orHasEnded() = runTest {
        assertThat(alerts.load(today)).isNull()

        recordChanges(today)
        assertThat(alerts.load(today)).isNull()

        recordChanges(today.minusDays(1), new.copy(date = today.minusDays(1)))
        assertThat(alerts.load(today.minusDays(1))).isNull()
    }

    @Test
    fun acknowledge_cancelsTheDaysQuietNotification() {
        alerts.acknowledge(today)

        assertThat(notifier.cancelled).containsExactly(today)
    }
}

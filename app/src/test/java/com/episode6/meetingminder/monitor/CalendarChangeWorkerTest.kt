package com.episode6.meetingminder.monitor

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.episode6.meetingminder.appGraph
import com.episode6.meetingminder.alarm.alarmTimeFor
import com.episode6.meetingminder.data.calendar.FakeCalendarProvider
import com.episode6.meetingminder.data.calendar.at
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.ChangeSnapshotEntity
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.db.decodeScheduleChanges
import com.episode6.meetingminder.model.ScheduleChange
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.ZoneId

/**
 * [CalendarChangeWorker] end to end through the real graph: a `change_snapshot` baseline in
 * the app's Room database, the calendar in [FakeCalendarProvider], WorkManager's test
 * instance. The shared day is tomorrow, so the whole day is in range whatever the time.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarChangeWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val tomorrow = today.plusDays(1)
    private val me = "me@example.com"

    private lateinit var provider: FakeCalendarProvider

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
        provider = Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        provider.addCalendar(id = 1, ownerAccount = me)
    }

    private fun addMeeting(eventId: Long, hour: Int) {
        provider.addInstance(
            instanceId = eventId,
            eventId = eventId,
            begin = tomorrow.at(hour, zone = zone),
            end = tomorrow.at(hour, 30, zone = zone),
            zone = zone,
            selfStatus = Attendees.ATTENDEE_STATUS_INVITED,
        )
        provider.addAttendee(id = eventId * 10, eventId = eventId, email = me)
        provider.addAttendee(id = eventId * 10 + 1, eventId = eventId, email = "boss@example.com")
    }

    private fun worker(reason: ChangeCheckReason) = TestListenableWorkerBuilder<CalendarChangeWorker>(context)
        .setInputData(workDataOf(CalendarChangeWorker.KEY_REASON to reason.name))
        .build()

    private fun works(name: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    @Test
    fun triggerRun_recordsAndNotifiesAMeetingAddedToASharedDay_andReArmsMonitoring() = runBlocking {
        context.appGraph.changeSnapshotDao.upsert(ChangeSnapshotEntity(tomorrow, takenAt = 1, eventsJson = "[]"))
        addMeeting(eventId = 7, hour = 10)

        val result = worker(ChangeCheckReason.CONTENT_TRIGGER).doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class)
        val changes = decodeScheduleChanges(tomorrow, context.appGraph.changeSnapshotDao.forDate(tomorrow)!!.changesJson)
        assertThat(changes.map { it::class }).containsExactly(ScheduleChange.New::class)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        assertThat(
            shadowOf(notificationManager).getNotification(ScheduleChangeNotifications.NOTIFICATION_TAG, ScheduleChangeNotifications.notificationId(tomorrow)),
        ).isNotNull()
        assertThat(works(WorkManagerChangeWorkScheduler.TRIGGER_WORK).single().state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(works(WorkManagerChangeWorkScheduler.PERIODIC_WORK).single().state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(works(WorkManagerChangeWorkScheduler.EXPIRY_WORK).single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun anyRun_retimesAnArmedAlarmWhoseMeetingMoved() = runBlocking {
        // the meeting was armed for 10:00 and then moved to 11:00 while the app was away: the
        // background check is the only thing running, so it re-times the alarm itself rather
        // than leaving the 10:00 alarm to ring until the app is next opened
        val graph = context.appGraph
        val leadTime = graph.settingsRepository.current().leadTime
        val armedAt10 = ScheduledAlarmEntity(
            date = tomorrow, eventId = 7, instanceTime = 0,
            fireAt = alarmTimeFor(tomorrow.at(10, zone = zone), leadTime),
            title = "Event 7", beginMillis = tomorrow.at(10, zone = zone), endMillis = tomorrow.at(10, 30, zone = zone), soundIndex = 3,
        )
        val alarmId = graph.scheduledAlarmDao.insert(armedAt10)
        addMeeting(eventId = 7, hour = 11)

        val result = worker(ChangeCheckReason.PERIODIC).doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class)
        val newFireAt = alarmTimeFor(tomorrow.at(11, zone = zone), leadTime)
        val row = graph.scheduledAlarmDao.byId(alarmId)!!
        assertThat(row.state).isEqualTo(AlarmState.SCHEDULED)
        assertThat(row.fireAt).isEqualTo(newFireAt)
        assertThat(row.beginMillis).isEqualTo(tomorrow.at(11, zone = zone))
        // and AlarmManager agrees, which is the half the user would notice
        val armed = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .single { shadowOf(it.operation).requestCode == alarmId.toInt() }
        assertThat(armed.triggerAtTime).isEqualTo(newFireAt)
    }

    @Test
    fun dayEndedRun_dropsTheEndedDay_andDisarmsMonitoring() = runBlocking {
        val yesterday = today.minusDays(1)
        context.appGraph.changeSnapshotDao.upsert(ChangeSnapshotEntity(yesterday, takenAt = 1, eventsJson = "[]"))
        WorkManagerChangeWorkScheduler(context, java.time.Clock.system(zone)).update(setOf(today), ChangeCheckReason.IN_APP)

        val result = worker(ChangeCheckReason.DAY_ENDED).doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class)
        assertThat(context.appGraph.changeSnapshotDao.forDate(yesterday)).isNull()
        assertThat(works(WorkManagerChangeWorkScheduler.TRIGGER_WORK).single().state).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(works(WorkManagerChangeWorkScheduler.PERIODIC_WORK).single().state).isEqualTo(WorkInfo.State.CANCELLED)
    }
}

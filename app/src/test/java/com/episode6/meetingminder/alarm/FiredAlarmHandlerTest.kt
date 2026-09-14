package com.episode6.meetingminder.alarm

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The PR-8 firing path: a plain high-priority notification on the `alarms` channel, and the row marked `FIRED`. */
@RunWith(RobolectricTestRunner::class)
class FiredAlarmHandlerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-09-14T08:55:00Z"), ZoneOffset.UTC)
    private val standup = ScheduledAlarmEntity(
        alarmId = 3, date = LocalDate.of(2026, 9, 14), eventId = 1, instanceTime = 0,
        fireAt = Instant.parse("2026-09-14T08:55:00Z").toEpochMilli(), title = "Daily standup",
        beginMillis = Instant.parse("2026-09-14T09:00:00Z").toEpochMilli(),
        endMillis = Instant.parse("2026-09-14T09:30:00Z").toEpochMilli(),
        soundIndex = 1,
    )

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlarmNotifications.createChannels(context)
    }

    @Test
    fun scheduledAlarm_postsANotificationAndMarksTheRowFired() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(standup))

        FiredAlarmHandler(context, dao, clock).onFired(3)

        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.FIRED)
        val notification = shadowOf(notificationManager).getNotification(3)
        assertThat(notification).isNotNull()
        assertThat(notification.channelId).isEqualTo(AlarmNotifications.CHANNEL_ALARMS)
        assertThat(notification.category).isEqualTo(NotificationCompat.CATEGORY_ALARM)
        assertThat(shadowOf(notification).contentTitle.toString()).isEqualTo("Daily standup")
        // newer JDKs format the AM/PM gap as a narrow no-break space
        assertThat(shadowOf(notification).contentText.toString().replace('\u202f', ' ')).isEqualTo("Starts at 9:00 AM")
    }

    @Test
    fun alarmCancelledAfterTheOsQueuedIt_isIgnored() = runTest {
        val dao = FakeScheduledAlarmDao(listOf(standup.copy(state = AlarmState.CANCELLED)))

        FiredAlarmHandler(context, dao, clock).onFired(3)

        assertThat(dao.rows.getValue(3).state).isEqualTo(AlarmState.CANCELLED)
        assertThat(shadowOf(notificationManager).size()).isEqualTo(0)
    }

    @Test
    fun unknownAlarmId_isIgnored() = runTest {
        FiredAlarmHandler(context, FakeScheduledAlarmDao(), clock).onFired(99)

        assertThat(shadowOf(notificationManager).size()).isEqualTo(0)
    }

    @Test
    fun alarmsChannel_isHighImportance() {
        val channel = notificationManager.getNotificationChannel(AlarmNotifications.CHANNEL_ALARMS)

        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
    }
}

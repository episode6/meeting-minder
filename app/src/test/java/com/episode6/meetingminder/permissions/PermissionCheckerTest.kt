package com.episode6.meetingminder.permissions

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.episode6.meetingminder.alarm.AlarmNotifications
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class PermissionCheckerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val checker = AndroidPermissionChecker(context)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // MeetingMinderApp.onCreate already did this; the test re-creates it after deleting it below
        AlarmNotifications.createChannels(context)
    }

    @After
    fun tearDown() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun nothingGranted_reportsEveryRowNotGranted() {
        shadowOf(notificationManager).setNotificationsEnabled(false)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertThat(checker.currentState()).isEqualTo(PermissionState())
        assertThat(checker.currentState().allRequiredGranted).isFalse()
    }

    @Test
    fun onlyReadGranted_stillReportsCalendarNotGranted() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR)

        assertThat(checker.currentState().calendarGranted).isFalse()
    }

    @Test
    fun bothReadAndWriteGranted_reportsCalendarGranted() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        assertThat(checker.currentState().calendarGranted).isTrue()
    }

    @Test
    fun everythingGranted_reportsAllRequiredGranted() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        assertThat(checker.currentState()).isEqualTo(
            PermissionState(calendarGranted = true, notificationsGranted = true, exactAlarmsGranted = true),
        )
        assertThat(checker.currentState().allRequiredGranted).isTrue()
    }

    @Test
    fun notificationsDisabledAppWide_reportsNotificationsNotGranted() {
        shadowOf(notificationManager).setNotificationsEnabled(false)

        assertThat(checker.currentState().notificationsGranted).isFalse()
    }

    @Test
    fun alarmsChannelSilenced_reportsNotificationsNotGranted() {
        notificationManager.deleteNotificationChannel(AlarmNotifications.CHANNEL_ALARMS)
        notificationManager.createNotificationChannel(
            android.app.NotificationChannel(AlarmNotifications.CHANNEL_ALARMS, "Alarms", NotificationManager.IMPORTANCE_NONE),
        )

        assertThat(checker.currentState().notificationsGranted).isFalse()
    }

    @Test
    fun exactAlarmsDenied_reportsExactAlarmsNotGranted() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertThat(checker.currentState().exactAlarmsGranted).isFalse()
    }
}

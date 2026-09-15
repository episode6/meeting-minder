package com.episode6.meetingminder.permissions

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.os.PowerManager
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
    // Robolectric doesn't model the full-screen-intent app op, so the tests drive it
    private var fullScreenIntentAllowed = true
    private val checker = AndroidPermissionChecker(context) { fullScreenIntentAllowed }

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
        fullScreenIntentAllowed = false

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
            PermissionState(calendarGranted = true, notificationsGranted = true, exactAlarmsGranted = true, fullScreenIntentGranted = true),
        )
        assertThat(checker.currentState().allRequiredGranted).isTrue()
    }

    @Test
    fun fullScreenIntentDenied_reportsItNotGranted_andBlocksTheDayView() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        fullScreenIntentAllowed = false

        assertThat(checker.currentState().fullScreenIntentGranted).isFalse()
        assertThat(checker.currentState().allRequiredGranted).isFalse()
    }

    @Test
    fun thePlatformFullScreenIntentCheck_runs() {
        // the default check calls NotificationManager.canUseFullScreenIntent() on 34+
        AndroidPermissionChecker(context).currentState()
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
    fun ignoringBatteryOptimizations_isReported_butNeverRequired() {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, true)

        assertThat(checker.currentState().ignoringBatteryOptimizations).isTrue()
        assertThat(checker.currentState().allRequiredGranted).isFalse()
    }

    @Test
    fun backgroundRestrictedByTheUser_isReported_withoutBlockingTheDayView() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        assertThat(checker.currentState().backgroundRestricted).isFalse()

        shadowOf(context.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)

        assertThat(checker.currentState().backgroundRestricted).isTrue()
        assertThat(checker.currentState().allRequiredGranted).isTrue()
    }

    @Test
    fun theRestrictedStandbyBucket_isReportedAsBackgroundRestricted() {
        shadowOf(context.getSystemService(UsageStatsManager::class.java))
            .setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RESTRICTED)

        assertThat(checker.currentState().backgroundRestricted).isTrue()
    }

    @Test
    fun theRareStandbyBucket_isNotRestricted() {
        shadowOf(context.getSystemService(UsageStatsManager::class.java))
            .setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE)

        assertThat(checker.currentState().backgroundRestricted).isFalse()
    }

    @Test
    fun exactAlarmsDenied_reportsExactAlarmsNotGranted() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertThat(checker.currentState().exactAlarmsGranted).isFalse()
    }
}

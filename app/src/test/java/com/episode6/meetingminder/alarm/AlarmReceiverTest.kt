package com.episode6.meetingminder.alarm

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/** A fired alarm goes straight to the ringing service, and the manifest declares the pieces the way §4.4 needs them. */
@RunWith(RobolectricTestRunner::class)
class AlarmReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        AlarmWakeLock.release()
    }

    @Test
    fun fire_startsTheRingingServiceAtOnce_holdingAWakeLock() {
        AlarmReceiver().onReceive(context, AlarmReceiver.fireIntent(context, 7))

        val started = shadowOf(context).nextStartedService
        assertThat(started.component?.className).isEqualTo(AlarmRingingService::class.java.name)
        assertThat(started.action).isEqualTo(AlarmRingingService.ACTION_FIRE)
        assertThat(started.data).isEqualTo(AlarmUris.alarm(7))
        assertThat(AlarmWakeLock.isHeld).isTrue()
    }

    @Test
    fun otherBroadcasts_areIgnored() {
        AlarmReceiver().onReceive(context, Intent("com.example.NOT_AN_ALARM"))
        AlarmReceiver().onReceive(context, Intent(AlarmReceiver.ACTION_FIRE))

        assertThat(shadowOf(context).nextStartedService).isNull()
    }

    @Test
    fun ringingService_isAPrivateMediaPlaybackForegroundService() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, AlarmRingingService::class.java), 0)

        assertThat(info.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        assertThat(info.exported).isFalse()
    }

    @Test
    fun ringingScreen_isAPrivateSingleInstanceActivity_keptOutOfRecents() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, AlarmActivity::class.java), 0)

        assertThat(info.launchMode).isEqualTo(ActivityInfo.LAUNCH_SINGLE_INSTANCE)
        assertThat(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS).isNotEqualTo(0)
        assertThat(info.exported).isFalse()
    }

    @Test
    fun alarmUris_roundTripTheAlarmId_andRejectOtherUris() {
        assertThat(AlarmUris.alarmIdOf(AlarmUris.alarm(42))).isEqualTo(42L)
        assertThat(AlarmUris.alarmIdOf(AlarmUris.day(LocalDate.of(2026, 9, 14)))).isNull()
    }
}

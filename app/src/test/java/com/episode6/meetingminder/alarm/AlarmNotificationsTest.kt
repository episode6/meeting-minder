package com.episode6.meetingminder.alarm

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.episode6.meetingminder.MainActivity
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.RingingAlarm
import com.episode6.meetingminder.model.SCHEDULE_CHANGE_ALARM_EVENT_ID
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.model.ScheduleChangeAlert
import com.episode6.meetingminder.ui.navigation.DeepLinks
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The `alarms` channel and what the ringing service posts on it. */
@RunWith(RobolectricTestRunner::class)
class AlarmNotificationsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val today = LocalDate.of(2026, 9, 14)
    private val alarm = RingingAlarm(
        alarmId = 3, date = today, key = EventKey(1, 0), title = "Daily standup", location = null,
        begin = Instant.parse("2026-09-14T09:00:00Z"), end = Instant.parse("2026-09-14T09:30:00Z"),
        soundIndex = 1, snoozeLength = Duration.ofMinutes(2),
    )

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlarmNotifications.createChannels(context)
    }

    private fun ringing(alert: Boolean = true) = AlarmNotifications.ringing(context, alarm, ZoneOffset.UTC, alert)

    @Test
    fun alarmsChannel_isHighImportance_andSilent_becauseTheServicePlaysTheSound() {
        val channel = notificationManager.getNotificationChannel(AlarmNotifications.CHANNEL_ALARMS)

        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        assertThat(channel.sound).isNull()
        assertThat(channel.shouldVibrate()).isFalse()
    }

    @Test
    fun ringing_isAnOngoingPublicAlarm_withAFullScreenIntentToTheRingingScreen() {
        val notification = ringing()

        assertThat(notification.channelId).isEqualTo(AlarmNotifications.CHANNEL_ALARMS)
        assertThat(notification.category).isEqualTo(Notification.CATEGORY_ALARM)
        assertThat(notification.visibility).isEqualTo(Notification.VISIBILITY_PUBLIC)
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE).isEqualTo(0)
        assertThat(shadowOf(notification).contentTitle.toString()).isEqualTo("Daily standup")
        // newer JDKs format the AM/PM gap as a narrow no-break space
        assertThat(shadowOf(notification).contentText.toString().replace(' ', ' ')).isEqualTo("Starts at 9:00 AM")

        assertThat(notification.fullScreenIntent).isNotNull()
        val fullScreen = shadowOf(notification.fullScreenIntent)
        assertThat(fullScreen.isActivityIntent).isTrue()
        assertThat(fullScreen.savedIntent.component?.className).isEqualTo(AlarmActivity::class.java.name)
        assertThat(fullScreen.savedIntent.data).isEqualTo(AlarmUris.alarm(3))
        assertThat(shadowOf(notification.contentIntent).savedIntent.component?.className).isEqualTo(AlarmActivity::class.java.name)
    }

    @Test
    fun ringing_silenceSnoozeAndDismiss_goStraightBackToTheService() {
        val notification = ringing()

        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Silence", "Snooze", "Dismiss")
        val (silence, snooze, dismiss) = notification.actions.map { shadowOf(it.actionIntent) }
        assertThat(silence.isServiceIntent).isTrue()
        assertThat(silence.savedIntent.action).isEqualTo(AlarmRingingService.ACTION_SILENCE)
        assertThat(silence.savedIntent.data).isEqualTo(AlarmUris.alarm(3))
        assertThat(snooze.isServiceIntent).isTrue()
        assertThat(snooze.savedIntent.component?.className).isEqualTo(AlarmRingingService::class.java.name)
        assertThat(snooze.savedIntent.action).isEqualTo(AlarmRingingService.ACTION_SNOOZE)
        assertThat(snooze.savedIntent.data).isEqualTo(AlarmUris.alarm(3))
        assertThat(dismiss.isServiceIntent).isTrue()
        assertThat(dismiss.savedIntent.action).isEqualTo(AlarmRingingService.ACTION_DISMISS)
        assertThat(dismiss.savedIntent.data).isEqualTo(AlarmUris.alarm(3))
        // swiping it away snoozes rather than leaving the alarm ringing with nothing to stop it
        assertThat(shadowOf(notification.deleteIntent).savedIntent.action).isEqualTo(AlarmRingingService.ACTION_SNOOZE)
    }

    @Test
    fun ringing_onceSilenced_hasNoSilenceAction() {
        val notification = AlarmNotifications.ringing(context, alarm.copy(silenced = true), ZoneOffset.UTC, alert = false)

        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Snooze", "Dismiss")
    }

    private val changeAlert = alarm.copy(
        key = EventKey(SCHEDULE_CHANGE_ALARM_EVENT_ID, 0),
        title = "",
        scheduleChange = ScheduleChangeAlert(
            listOf(
                ScheduleChange.New(today, EventKey(8, 0), Instant.parse("2026-09-14T15:00:00Z"), Instant.parse("2026-09-14T15:30:00Z")),
                ScheduleChange.Cancelled(today, EventKey(9, 0), Instant.parse("2026-09-14T13:00:00Z"), Instant.parse("2026-09-14T14:00:00Z")),
            ),
            syncsBusyCalendar = true,
        ),
    )

    @Test
    fun aScheduleChangeAlert_saysWhatChanged_timesOnly_andStillOpensTheRingingScreen() {
        val notification = AlarmNotifications.ringing(context, changeAlert, ZoneOffset.UTC, alert = true)

        assertThat(notification.category).isEqualTo(Notification.CATEGORY_ALARM)
        assertThat(shadowOf(notification).contentTitle.toString()).isEqualTo("Your schedule changed since you shared it")
        assertThat(shadowOf(notification).contentText.toString().replace('\u202f', ' '))
            .isEqualTo("New: 3:00 – 3:30 PM · Cancelled: 1:00 – 2:00 PM")
        assertThat(shadowOf(notification.fullScreenIntent).savedIntent.component?.className).isEqualTo(AlarmActivity::class.java.name)
    }

    @Test
    fun aScheduleChangeAlert_offersSilenceDismissAndReShare_andSwipingItAwayOnlyStopsIt() {
        val notification = AlarmNotifications.ringing(context, changeAlert, ZoneOffset.UTC, alert = true)

        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Silence", "Dismiss", "Sync & Re-share")
        val (silence, dismiss, reshare) = notification.actions.map { shadowOf(it.actionIntent) }
        assertThat(silence.savedIntent.action).isEqualTo(AlarmRingingService.ACTION_SILENCE)
        assertThat(dismiss.savedIntent.action).isEqualTo(AlarmRingingService.ACTION_DISMISS)
        // straight into MainActivity, never through the service: that would be a trampoline
        assertThat(reshare.isActivityIntent).isTrue()
        assertThat(reshare.savedIntent.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(reshare.savedIntent.data).isEqualTo(DeepLinks.share(today))
        // not Dismiss: a reflexive swipe mustn't take the quiet notification with it
        assertThat(shadowOf(notification.deleteIntent).savedIntent.action).isEqualTo(AlarmRingingService.ACTION_SWIPED_AWAY)
    }

    @Test
    fun aSilencedScheduleChangeAlert_offersTheItineraryInSilencesPlace_andPlainReShareWithoutTheSync() {
        val silenced = changeAlert.copy(silenced = true, scheduleChange = changeAlert.scheduleChange?.copy(syncsBusyCalendar = false))
        val notification = AlarmNotifications.ringing(context, silenced, ZoneOffset.UTC, alert = false)

        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Dismiss", "Open itinerary", "Re-share")
        val itinerary = shadowOf(notification.actions[1].actionIntent)
        assertThat(itinerary.isActivityIntent).isTrue()
        assertThat(itinerary.savedIntent.data).isEqualTo(DeepLinks.day(today))
    }

    @Test
    fun ringing_aRepostOfTheSameAlarm_doesNotAlertAgain() {
        assertThat(ringing(alert = false).flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
    }

    @Test
    fun missed_postsANotificationForThatAlarm_openingItsDay() {
        AlarmNotifications.postMissed(context, alarm, ZoneOffset.UTC)

        val posted = shadowOf(notificationManager).getNotification(3)
        assertThat(posted).isNotNull()
        assertThat(shadowOf(posted).contentTitle.toString()).isEqualTo("Missed alarm: Daily standup")
        assertThat(posted.fullScreenIntent).isNull()
        val open = shadowOf(posted.contentIntent).savedIntent
        assertThat(open.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(open.data).isEqualTo(AlarmUris.day(today))
    }

    @Test
    fun missed_withoutTheNotificationPermission_postsNothing() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        AlarmNotifications.postMissed(context, alarm, ZoneOffset.UTC)

        assertThat(shadowOf(notificationManager).size()).isEqualTo(0)
    }
}

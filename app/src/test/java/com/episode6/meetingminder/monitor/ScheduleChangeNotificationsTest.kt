package com.episode6.meetingminder.monitor

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.episode6.meetingminder.DeepLinks
import com.episode6.meetingminder.MainActivity
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.ScheduleChange
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The `schedule_updates` channel and the schedule-changed notification (render 6, TODO.md §4.3). */
@RunWith(RobolectricTestRunner::class)
class ScheduleChangeNotificationsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val today = LocalDate.of(2026, 9, 14)
    private val zone = ZoneOffset.UTC
    private val clock = Clock.fixed(Instant.parse("2026-09-14T13:42:00Z"), zone)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant = date.atTime(hour, minute).toInstant(zone)

    private fun changesOn(date: LocalDate) = listOf(
        ScheduleChange.New(date, EventKey(1, 0), at(date, 15), at(date, 15, 30)),
        ScheduleChange.Moved(date, EventKey(2, 0), at(date, 12), at(date, 13), at(date, 12, 30), at(date, 13, 30)),
    )

    private val notifier = AndroidScheduleChangeNotifier(context, clock)

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ScheduleChangeNotifications.createChannel(context)
    }

    private fun build(date: LocalDate) = ScheduleChangeNotifications.build(context, date, changesOn(date), today, zone)

    private fun posted(date: LocalDate): Notification? = shadowOf(notificationManager)
        .getNotification(ScheduleChangeNotifications.NOTIFICATION_TAG, ScheduleChangeNotifications.notificationId(date))

    private fun Notification.Action.intent(): Intent = shadowOf(actionIntent).savedIntent

    @Test
    fun channel_isDefaultImportance() {
        val channel = notificationManager.getNotificationChannel(ScheduleChangeNotifications.CHANNEL_SCHEDULE_UPDATES)

        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
        assertThat(channel.name.toString()).isEqualTo("Schedule updates")
    }

    @Test
    fun notification_forToday_listsEachChangeByTimeOnly_andAlertsOnlyOnce() {
        val notification = build(today)

        assertThat(notification.channelId).isEqualTo(ScheduleChangeNotifications.CHANNEL_SCHEDULE_UPDATES)
        assertThat(notification.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("Your schedule changed since you shared it")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("New: 3:00 – 3:30 PM · Moved: 12:00 – 1:00 PM → 12:30 – 1:30 PM")
        assertThat(notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() })
            .containsExactly("New: 3:00 – 3:30 PM", "Moved: 12:00 – 1:00 PM → 12:30 – 1:30 PM")
        assertThat(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0).isTrue()
        assertThat(notification.category).isEqualTo(NotificationCompat.CATEGORY_REMINDER)
    }

    @Test
    fun notification_forAnotherDay_namesIt() {
        assertThat(build(today.plusDays(1)).extras.getString(Notification.EXTRA_TITLE))
            .isEqualTo("Your Tuesday schedule changed since you shared it")
        assertThat(build(today.plusDays(14)).extras.getString(Notification.EXTRA_TITLE))
            .isEqualTo("Your Mon, Sep 28 schedule changed since you shared it")
    }

    @Test
    fun notification_reviewAndShareUpdate_deepLinkStraightIntoMainActivity() {
        val notification = build(today)

        val (review, shareUpdate) = notification.actions.toList()
        assertThat(review.title.toString()).isEqualTo("Review")
        assertThat(shareUpdate.title.toString()).isEqualTo("Share update")
        assertThat(review.intent().component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(review.intent().data).isEqualTo(DeepLinks.day(today))
        assertThat(shareUpdate.intent().component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(shareUpdate.intent().data).isEqualTo(DeepLinks.share(today))
        assertThat(shadowOf(notification.contentIntent).isActivityIntent).isTrue()
        assertThat(shadowOf(notification.contentIntent).savedIntent.data).isEqualTo(DeepLinks.day(today))
        assertThat(review.intent().flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0).isTrue()
    }

    @Test
    fun notifier_postsOnePerDay_andCancelsIt() {
        notifier.show(today, changesOn(today), alert = true)
        notifier.show(today.plusDays(1), changesOn(today.plusDays(1)), alert = true)

        assertThat(posted(today)).isNotNull()
        assertThat(posted(today.plusDays(1))).isNotNull()

        notifier.cancel(today)

        assertThat(posted(today)).isNull()
        assertThat(posted(today.plusDays(1))).isNotNull()
    }

    @Test
    fun notifier_withoutAnAlert_onlyUpdatesANotificationStillShowing() {
        notifier.show(today, changesOn(today), alert = false)

        assertThat(shadowOf(notificationManager).allNotifications).isEmpty()

        notifier.show(today, changesOn(today), alert = true)
        notifier.show(today, changesOn(today).take(1), alert = false)

        assertThat(posted(today)!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("New: 3:00 – 3:30 PM")
    }

    @Test
    fun notifier_withoutNotificationPermission_postsNothing() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifier.show(today, changesOn(today), alert = true)

        assertThat(shadowOf(notificationManager).allNotifications).isEmpty()
    }
}

package com.episode6.meetingminder.alarm

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.episode6.meetingminder.ui.navigation.DeepLinks
import com.episode6.meetingminder.R
import com.episode6.meetingminder.model.RingingAlarm
import com.episode6.meetingminder.monitor.text
import com.episode6.meetingminder.monitor.toLine
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The `alarms` notification channel (TODO.md §4.4: `IMPORTANCE_HIGH`, silent because
 * [AlarmRingingService] plays the audio itself) and the notifications posted on it: the
 * ringing service's foreground notification and the "missed alarm" notification.
 */
object AlarmNotifications {
    const val CHANNEL_ALARMS = "alarms"

    /** The ringing service's foreground notification; negative so it never collides with a per-alarm (`alarmId`) one. */
    const val RINGING_NOTIFICATION_ID = -1

    /**
     * Idempotent; called from `MeetingMinderApp.onCreate` so onboarding can read the
     * channel's importance. A channel's sound can't change once it exists, so an install
     * that created it before PR-10 keeps the old default sound on it until its data is
     * cleared; the notification's own sound is never what rings.
     */
    fun createChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ALARMS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.notification_channel_alarms))
                .setDescription(context.getString(R.string.notification_channel_alarms_description))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .build(),
        )
    }

    /** App-level notifications on, and the `alarms` channel not silenced by the user. */
    fun enabled(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        val channelImportance = manager.getNotificationChannelCompat(CHANNEL_ALARMS)?.importance
        return manager.areNotificationsEnabled() && channelImportance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /**
     * The ringing notification (TODO.md §4.4): `CATEGORY_ALARM`, public on the lock screen,
     * ongoing, with a full-screen intent to [AlarmActivity] — which wakes the screen when
     * the phone is off or locked and shows as a heads-up with Snooze/Dismiss when it's in
     * use — and both actions sent straight back to [AlarmRingingService] (never a
     * trampoline). Swiping it away (allowed for foreground-service notifications since
     * Android 14) snoozes, so a ringing alarm is never left with nothing to stop it. With
     * [alert] false it's a quiet re-post of an alarm that is already ringing. "Silence"
     * comes first while it makes a sound and goes once it doesn't.
     *
     * A schedule-change alert ([RingingAlarm.scheduleChange], TODO.md §4.3) is the same
     * notification saying what changed, times only. A notification shows three actions at
     * most, so while it makes a sound they are Silence, Dismiss and "Sync & Re-share", and
     * "Open itinerary" takes Silence's place once it is silent; the body opens the alert
     * screen, which always has all four. The two links go straight into `MainActivity`
     * (never a trampoline), which dismisses the alert as it takes them. Swiping it away
     * stops the ringing but, unlike the Dismiss action, leaves the quiet schedule-changed
     * notification: a reflexive swipe isn't "I've seen it".
     */
    fun ringing(context: Context, alarm: RingingAlarm, zone: ZoneId, alert: Boolean): Notification {
        val ringingScreen = ringingScreenIntent(context, alarm.alarmId)
        val silence = serviceIntent(context, alarm.alarmId, AlarmRingingService.silenceIntent(context, alarm.alarmId))
        val snooze = serviceIntent(context, alarm.alarmId, AlarmRingingService.snoozeIntent(context, alarm.alarmId))
        val dismiss = serviceIntent(context, alarm.alarmId, AlarmRingingService.dismissIntent(context, alarm.alarmId))
        val builder = NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setFullScreenIntent(ringingScreen, true)
            .setContentIntent(ringingScreen)
        if (!alarm.silenced) builder.addAction(R.drawable.ic_notification_alarm, context.getString(R.string.alarm_silence), silence)
        val change = alarm.scheduleChange
        if (change == null) {
            return builder
                .setContentTitle(alarm.title)
                .setContentText(startsAt(context, alarm, zone))
                .setDeleteIntent(snooze)
                .addAction(R.drawable.ic_notification_alarm, context.getString(R.string.alarm_snooze_action), snooze)
                .addAction(R.drawable.ic_notification_alarm, context.getString(R.string.alarm_dismiss), dismiss)
                .build()
        }
        val title = context.getString(R.string.schedule_changed_title)
        val lines = change.changes.map { context.resources.text(it.toLine(zone)) }
        val reshare = if (change.syncsBusyCalendar) R.string.schedule_alert_sync_reshare else R.string.schedule_alert_reshare
        builder
            .setContentTitle(title)
            .setContentText(lines.joinToString(context.getString(R.string.schedule_change_separator)))
            .setStyle(NotificationCompat.InboxStyle().setBigContentTitle(title).also { style -> lines.forEach(style::addLine) })
            .setDeleteIntent(serviceIntent(context, alarm.alarmId, AlarmRingingService.swipedAwayIntent(context, alarm.alarmId)))
            .addAction(R.drawable.ic_notification_alarm, context.getString(R.string.alarm_dismiss), dismiss)
        if (alarm.silenced) {
            builder.addAction(R.drawable.ic_notification_alarm, context.getString(R.string.schedule_alert_open_itinerary), linkIntent(context, alarm.alarmId, DeepLinks.day(alarm.date)))
        }
        return builder
            .addAction(R.drawable.ic_notification_alarm, context.getString(reshare), linkIntent(context, alarm.alarmId, DeepLinks.share(alarm.date)))
            .build()
    }

    /** A bare foreground notification for the moment before (or instead of) anything ringing; see `AlarmRingingSession`. */
    fun placeholder(context: Context): Notification = NotificationCompat.Builder(context, CHANNEL_ALARMS)
        .setSmallIcon(R.drawable.ic_notification_alarm)
        .setContentTitle(context.getString(R.string.alarm_notification_starting))
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setSilent(true)
        .build()

    /** "Missed alarm: <title>": the alarm went unanswered twice, or couldn't be rung or re-armed at all. */
    fun postMissed(context: Context, alarm: RingingAlarm, zone: ZoneId) {
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle(context.getString(R.string.alarm_missed_title, alarm.title))
            .setContentText(startsAt(context, alarm, zone))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(linkIntent(context, alarm.alarmId, DeepLinks.day(alarm.date)))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(alarm.alarmId.toInt(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check above and the post
        }
    }

    private fun startsAt(context: Context, alarm: RingingAlarm, zone: ZoneId): String =
        context.getString(R.string.alarm_notification_starts_at, TimeFormatter.format(alarm.begin.atZone(zone)))

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ringingScreenIntent(context: Context, alarmId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        alarmId.toInt(),
        Intent(context, AlarmActivity::class.java)
            .setData(AlarmUris.alarm(alarmId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Identified by the intent's action + alarm data, so Snooze and Dismiss are distinct `PendingIntent`s. */
    private fun serviceIntent(context: Context, alarmId: Long, intent: Intent): PendingIntent =
        PendingIntent.getService(context, alarmId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * Opens [link] (the alarm's day, or its share) through the same deep links the
     * schedule-changed notification uses, so a running day view jumps to it. The link is the
     * intent's data, so a day and a share with the same request code stay distinct.
     */
    private fun linkIntent(context: Context, alarmId: Long, link: Uri): PendingIntent = PendingIntent.getActivity(
        context,
        alarmId.toInt(),
        DeepLinks.activityIntent(context, link),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
}

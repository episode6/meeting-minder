package com.episode6.meetingminder.alarm

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.episode6.meetingminder.MainActivity
import com.episode6.meetingminder.R
import com.episode6.meetingminder.model.RingingAlarm
import java.time.LocalDate
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
     * [alert] false it's a quiet re-post of an alarm that is already ringing.
     */
    fun ringing(context: Context, alarm: RingingAlarm, zone: ZoneId, alert: Boolean): Notification {
        val ringingScreen = ringingScreenIntent(context, alarm.alarmId)
        val snooze = serviceIntent(context, alarm.alarmId, AlarmRingingService.snoozeIntent(context, alarm.alarmId))
        val dismiss = serviceIntent(context, alarm.alarmId, AlarmRingingService.dismissIntent(context, alarm.alarmId))
        return NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle(alarm.title)
            .setContentText(startsAt(context, alarm, zone))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setFullScreenIntent(ringingScreen, true)
            .setContentIntent(ringingScreen)
            .setDeleteIntent(snooze)
            .addAction(R.drawable.ic_notification_alarm, context.getString(R.string.alarm_snooze_action), snooze)
            .addAction(R.drawable.ic_notification_alarm, context.getString(R.string.alarm_dismiss), dismiss)
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
            .setContentIntent(openDayIntent(context, alarm.alarmId, alarm.date))
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

    private fun openDayIntent(context: Context, alarmId: Long, date: LocalDate): PendingIntent = PendingIntent.getActivity(
        context,
        alarmId.toInt(),
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(AlarmUris.day(date))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
}

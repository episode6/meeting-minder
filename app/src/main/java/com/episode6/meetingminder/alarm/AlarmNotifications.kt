package com.episode6.meetingminder.alarm

import android.Manifest
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
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The `alarms` notification channel (TODO.md §4.4: `IMPORTANCE_HIGH`, `CATEGORY_ALARM`,
 * `VISIBILITY_PUBLIC`) and, until PR-10's ringing service takes over, the plain
 * high-priority notification an alarm posts when it fires.
 */
object AlarmNotifications {
    const val CHANNEL_ALARMS = "alarms"

    /** Idempotent; called from `MeetingMinderApp.onCreate` so onboarding can read the channel's importance. */
    fun createChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ALARMS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.notification_channel_alarms))
                .setDescription(context.getString(R.string.notification_channel_alarms_description))
                .build(),
        )
    }

    /** App-level notifications on, and the `alarms` channel not silenced by the user. */
    fun enabled(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        val channelImportance = manager.getNotificationChannelCompat(CHANNEL_ALARMS)?.importance
        return manager.areNotificationsEnabled() && channelImportance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    fun postFired(context: Context, alarm: ScheduledAlarmEntity, zone: ZoneId) {
        if (!canPost(context)) return
        val startsAt = TimeFormatter.format(Instant.ofEpochMilli(alarm.beginMillis).atZone(zone))
        val notification = NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle(alarm.title)
            .setContentText(context.getString(R.string.alarm_notification_starts_at, startsAt))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openDayIntent(context, alarm))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(alarm.alarmId.toInt(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check above and the post
        }
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openDayIntent(context: Context, alarm: ScheduledAlarmEntity): PendingIntent = PendingIntent.getActivity(
        context,
        alarm.alarmId.toInt(),
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(AlarmUris.day(alarm.date))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
}

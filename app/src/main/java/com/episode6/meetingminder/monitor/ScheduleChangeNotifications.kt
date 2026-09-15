package com.episode6.meetingminder.monitor

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.episode6.meetingminder.ui.navigation.DeepLinks
import com.episode6.meetingminder.R
import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.share.ScheduleTextFormatter
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Posts and cancels a shared day's "your schedule changed since you shared it" notification (TODO.md §4.3). */
interface ScheduleChangeNotifier {
    /**
     * Shows [changes] for [date]. With [alert] true (something in [changes] is new) it posts
     * the notification, but since it is `setOnlyAlertOnce` (TODO.md §4.3) it only makes a
     * sound when the notification isn't already showing: a second new change while the
     * first is still unread updates it silently. With [alert] false (nothing in [changes] is
     * new, some just dropped out) it only updates a notification that is still showing, so a
     * dismissed one doesn't come back for old news.
     */
    fun show(date: LocalDate, changes: List<ScheduleChange>, alert: Boolean)

    fun cancel(date: LocalDate)
}

/**
 * One change as a line of text (render 6): times only, the way the share text writes them,
 * so the notification and the in-app banner never show titles either. The range strings
 * are pre-formatted in the device zone; [text] picks the label.
 */
sealed interface ScheduleChangeLine {
    data class New(val range: String) : ScheduleChangeLine
    data class Moved(val from: String, val to: String) : ScheduleChangeLine
    data class Cancelled(val range: String) : ScheduleChangeLine
    data class Declined(val range: String) : ScheduleChangeLine
}

fun ScheduleChange.toLine(zone: ZoneId): ScheduleChangeLine {
    fun range(begin: java.time.Instant, end: java.time.Instant) = ScheduleTextFormatter.formatRange(BusyRange(begin, end), zone)
    return when (this) {
        is ScheduleChange.New -> ScheduleChangeLine.New(range(begin, end))
        is ScheduleChange.Moved -> ScheduleChangeLine.Moved(range(oldBegin, oldEnd), range(newBegin, newEnd))
        is ScheduleChange.Cancelled -> ScheduleChangeLine.Cancelled(range(begin, end))
        is ScheduleChange.Declined -> ScheduleChangeLine.Declined(range(begin, end))
    }
}

/** "New: 3:00 – 3:30 PM", "Moved: 12:00 – 1:00 PM → 12:30 – 1:30 PM", … */
fun Resources.text(line: ScheduleChangeLine): String = when (line) {
    is ScheduleChangeLine.New -> getString(R.string.schedule_change_new, line.range)
    is ScheduleChangeLine.Moved -> getString(R.string.schedule_change_moved, line.from, line.to)
    is ScheduleChangeLine.Cancelled -> getString(R.string.schedule_change_cancelled, line.range)
    is ScheduleChangeLine.Declined -> getString(R.string.schedule_change_declined, line.range)
}

/**
 * The `schedule_updates` channel (`IMPORTANCE_DEFAULT`) and its notification: one per
 * shared day (tag [NOTIFICATION_TAG], id = the day's epoch day, so it never collides with an
 * alarm's), `setOnlyAlertOnce`, `InboxStyle` with one line per change. Tapping it or
 * "Review" opens `meetingminder://day/{date}`; "Share update" opens
 * `meetingminder://share/{date}`, where the activity opens the chooser. Both are
 * `PendingIntent.getActivity` straight into `MainActivity`, never a trampoline.
 */
object ScheduleChangeNotifications {
    const val CHANNEL_SCHEDULE_UPDATES = "schedule_updates"
    const val NOTIFICATION_TAG = "schedule_updates"

    /** Days within this many days after today are named by weekday ("Tuesday"); further out by date. */
    private const val WEEKDAY_NAME_DAYS = 6L

    fun createChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_SCHEDULE_UPDATES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.notification_channel_schedule_updates))
                .setDescription(context.getString(R.string.notification_channel_schedule_updates_description))
                .build(),
        )
    }

    fun notificationId(date: LocalDate): Int = date.toEpochDay().toInt()

    /** The notification for [changes] on [date], titled for [today] ("Your Tuesday schedule…" when [date] isn't today). */
    fun build(context: Context, date: LocalDate, changes: List<ScheduleChange>, today: LocalDate, zone: ZoneId): Notification {
        val lines = changes.map { context.resources.text(it.toLine(zone)) }
        val title = if (date == today) {
            context.getString(R.string.schedule_changed_title)
        } else {
            val dayName = if (date > today && date <= today.plusDays(WEEKDAY_NAME_DAYS)) WeekdayFormatter else DateFormatter
            context.getString(R.string.schedule_changed_title_on_day, date.format(dayName))
        }
        val review = activityIntent(context, date, DeepLinks.day(date))
        val shareUpdate = activityIntent(context, date, DeepLinks.share(date))
        return NotificationCompat.Builder(context, CHANNEL_SCHEDULE_UPDATES)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle(title)
            .setContentText(lines.joinToString(context.getString(R.string.schedule_change_separator)))
            .setStyle(NotificationCompat.InboxStyle().setBigContentTitle(title).also { style -> lines.forEach(style::addLine) })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(review)
            .addAction(0, context.getString(R.string.schedule_changed_review), review)
            .addAction(0, context.getString(R.string.schedule_changed_share_update), shareUpdate)
            .build()
    }

    private fun activityIntent(context: Context, date: LocalDate, link: Uri): PendingIntent = PendingIntent.getActivity(
        context,
        notificationId(date),
        DeepLinks.activityIntent(context, link),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val WeekdayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE")
    private val DateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
}

/** [ScheduleChangeNotifier] over `NotificationManagerCompat`; silently does nothing without `POST_NOTIFICATIONS`. */
class AndroidScheduleChangeNotifier(private val context: Context, private val clock: Clock) : ScheduleChangeNotifier {

    private val manager get() = NotificationManagerCompat.from(context)

    override fun show(date: LocalDate, changes: List<ScheduleChange>, alert: Boolean) {
        if (!canPost()) return
        val id = ScheduleChangeNotifications.notificationId(date)
        if (!alert && manager.activeNotifications.none { it.tag == ScheduleChangeNotifications.NOTIFICATION_TAG && it.id == id }) return
        val notification = ScheduleChangeNotifications.build(context, date, changes, LocalDate.now(clock), clock.zone)
        try {
            manager.notify(ScheduleChangeNotifications.NOTIFICATION_TAG, id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check above and the post
        }
    }

    override fun cancel(date: LocalDate) {
        manager.cancel(ScheduleChangeNotifications.NOTIFICATION_TAG, ScheduleChangeNotifications.notificationId(date))
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

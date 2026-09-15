package com.episode6.meetingminder.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.episode6.meetingminder.MainActivity
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import java.time.LocalDate

/**
 * Arms and disarms exact alarms for `scheduled_alarm` rows (TODO.md §4.4). The DAO row is
 * the source of truth; this only mirrors it into `AlarmManager`. `AndroidAlarmScheduler`
 * is the production binding (`di/AlarmModule.kt`); tests use `FakeAlarmScheduler`.
 */
interface AlarmScheduler {
    /** `AlarmManager.canScheduleExactAlarms()`: true whenever `USE_EXACT_ALARM` (33+) or `SCHEDULE_EXACT_ALARM` (31/32) applies. */
    fun canScheduleExactAlarms(): Boolean

    /**
     * Arms [alarm] at [ScheduledAlarmEntity.fireAt], replacing any alarm already armed
     * for the same [ScheduledAlarmEntity.alarmId]. False if the OS refused (the exact-alarm
     * grant was revoked between the check and the call).
     */
    fun schedule(alarm: ScheduledAlarmEntity): Boolean

    fun cancel(alarmId: Long)
}

/** The `meetingminder://` URIs that give alarm and day `PendingIntent`s their identity (extras never do). */
object AlarmUris {
    private const val SCHEME = "meetingminder"

    fun alarm(alarmId: Long): Uri = Uri.Builder().scheme(SCHEME).authority("alarm").appendPath(alarmId.toString()).build()

    /** The alarm id in an [alarm] URI; null for anything else. */
    fun alarmIdOf(uri: Uri): Long? = uri.takeIf { it.scheme == SCHEME && it.authority == "alarm" }?.lastPathSegment?.toLongOrNull()

    /** The day view for [date] (`Navigation.kt` handles it; see [com.episode6.meetingminder.ui.navigation.DeepLinks]). */
    fun day(date: LocalDate): Uri = com.episode6.meetingminder.ui.navigation.DeepLinks.day(date)
}

/**
 * [AlarmScheduler] over `AlarmManager.setAlarmClock` — not `setExactAndAllowWhileIdle`:
 * alarm-clock alarms fire on time in Doze, are exempt from the per-app exact-alarm
 * throttle that could drop the second of two back-to-back meeting alarms, show the
 * status-bar alarm icon, and their firing allowlists the app to start the ringing
 * foreground service (PR-10). One `PendingIntent` per alarm, identified by its
 * `meetingminder://alarm/{alarmId}` data and `requestCode = alarmId`, `FLAG_IMMUTABLE |
 * FLAG_UPDATE_CURRENT` and never `FLAG_ONE_SHOT` (which would make it uncancellable).
 */
class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {

    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExactAlarms(): Boolean = alarmManager.canScheduleExactAlarms()

    override fun schedule(alarm: ScheduledAlarmEntity): Boolean = try {
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(alarm.fireAt, showDayIntent(alarm.date)),
            fireIntent(alarm.alarmId),
        )
        true
    } catch (_: SecurityException) {
        false
    }

    override fun cancel(alarmId: Long) {
        alarmManager.cancel(fireIntent(alarmId))
    }

    private fun fireIntent(alarmId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        // Int request codes wrap after 2^31 rows, but the data URI keeps every alarm's
        // PendingIntent distinct even then.
        alarmId.toInt(),
        AlarmReceiver.fireIntent(context, alarmId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** `AlarmClockInfo.showIntent`: tapping the status-bar alarm icon opens the day view. */
    private fun showDayIntent(date: LocalDate): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(AlarmUris.day(date))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

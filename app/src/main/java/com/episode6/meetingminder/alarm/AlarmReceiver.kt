package com.episode6.meetingminder.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.episode6.meetingminder.appGraph
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/** How long a receiver may hold its broadcast open with `goAsync()` before the system complains (10 s). */
internal const val BROADCAST_BUDGET_MILLIS = 8_000L

/** Long enough for [AlarmRingingService] to be created and take the wake lock over. */
private const val RECEIVE_WAKE_LOCK_MILLIS = 30_000L

private const val LOG_TAG = "AlarmReceiver"

/**
 * The target of every alarm `PendingIntent` (see [AndroidAlarmScheduler]). Takes the
 * [AlarmWakeLock] and immediately starts [AlarmRingingService] as a foreground service —
 * no coroutine, no database read first: firing an alarm-clock alarm puts the app on the
 * temporary allowlist that permits a foreground-service start from the background, and
 * that window is short (TODO.md §4.4). The service reads the row and decides whether
 * there is anything to ring.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val alarmId = intent.data?.let(AlarmUris::alarmIdOf) ?: return
        AlarmWakeLock.acquire(context, RECEIVE_WAKE_LOCK_MILLIS)
        try {
            ContextCompat.startForegroundService(context, AlarmRingingService.fireIntent(context, alarmId))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException. Not expected for an alarm-clock
            // alarm, but if it happens the alarm can't ring: say so rather than lose it.
            Log.w(LOG_TAG, "couldn't start the ringing service for alarm $alarmId", e)
            AlarmWakeLock.release()
            val pendingResult = goAsync()
            val graph = context.appGraph
            graph.appCoroutineScope.launch {
                try {
                    withTimeoutOrNull(BROADCAST_BUDGET_MILLIS) {
                        graph.alarmRinger.fire(alarmId)?.let { AlarmNotifications.postMissed(context, it, ZoneId.systemDefault()) }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.episode6.meetingminder.action.ALARM_FIRE"

        /** The explicit intent behind alarm [alarmId]'s `PendingIntent`; its data is the alarm's identity. */
        fun fireIntent(context: Context, alarmId: Long): Intent =
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE).setData(AlarmUris.alarm(alarmId))
    }
}

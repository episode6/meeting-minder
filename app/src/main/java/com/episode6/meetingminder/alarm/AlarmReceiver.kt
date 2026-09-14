package com.episode6.meetingminder.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.episode6.meetingminder.appGraph
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock

/** How long a receiver may hold its broadcast open with `goAsync()` before the system complains (10 s). */
internal const val BROADCAST_BUDGET_MILLIS = 8_000L

/**
 * The target of every alarm `PendingIntent` (see [AndroidAlarmScheduler]). For now it
 * hands the alarm to [FiredAlarmHandler], which posts a plain high-priority notification;
 * PR-10 replaces that with an immediate `ContextCompat.startForegroundService` on the
 * ringing service (no coroutine first, or the alarm-clock allowlist window is lost).
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val alarmId = intent.data?.lastPathSegment?.toLongOrNull() ?: return
        val pendingResult = goAsync()
        val graph = context.appGraph
        graph.appCoroutineScope.launch {
            try {
                withTimeoutOrNull(BROADCAST_BUDGET_MILLIS) { graph.firedAlarmHandler.onFired(alarmId) }
            } finally {
                pendingResult.finish()
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

/**
 * What happens when alarm [ScheduledAlarmEntity.alarmId] fires: the row is marked `FIRED`
 * and a notification posted from the denormalised row alone (never the provider). A row
 * that is no longer `SCHEDULED` (cancelled after the OS had already queued the broadcast)
 * is ignored.
 */
@Inject
@SingleIn(AppScope::class)
class FiredAlarmHandler(
    private val context: Context,
    private val dao: ScheduledAlarmDao,
    private val clock: Clock,
) {
    suspend fun onFired(alarmId: Long) {
        val alarm = dao.byId(alarmId) ?: return
        if (alarm.state != AlarmState.SCHEDULED) return
        dao.setState(alarmId, AlarmState.FIRED)
        AlarmNotifications.postFired(context, alarm, clock.zone)
    }
}

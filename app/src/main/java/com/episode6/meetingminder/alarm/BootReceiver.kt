package com.episode6.meetingminder.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.episode6.meetingminder.appGraph
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Re-arms every stored alarm ([AlarmRescheduler]) after the OS may have dropped them
 * (TODO.md §4.4): boot (alarms never survive a shutdown, so this path is mandatory), an
 * app update, a wall-clock or timezone change, and the exact-alarm grant flipping (a
 * grant arriving after "Set alarms" failed to arm anything is only useful if something
 * re-arms). The work runs under `goAsync()` so the broadcast stays open — and the process
 * alive — until Room has been read and every `setAlarmClock` call made; a plain store
 * dispatch couldn't be awaited from here. No direct-boot handling: nothing here needs the
 * calendar provider, but the database lives in credential-encrypted storage anyway.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val pendingResult = goAsync()
        val graph = context.appGraph
        graph.appCoroutineScope.launch {
            try {
                withTimeoutOrNull(BROADCAST_BUDGET_MILLIS) { graph.alarmRescheduler.rescheduleAll() }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        val HANDLED_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}

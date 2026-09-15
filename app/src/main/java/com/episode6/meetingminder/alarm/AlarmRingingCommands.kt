package com.episode6.meetingminder.alarm

import android.content.Context
import android.content.Intent

/**
 * Carries the ringing screen's Snooze/Dismiss (`SnoozeAlarm`/`DismissAlarm`, via
 * `AlarmRingingSideEffects`) to [AlarmRingingService], which owns the ringing: only it can
 * stop the sound and move on to the next queued alarm. `ServiceAlarmRingingCommands` is
 * the production binding.
 */
interface AlarmRingingCommands {
    /** Returns false if the command couldn't be delivered, in which case the caller applies it to the row itself. */
    fun snooze(alarmId: Long): Boolean

    fun dismiss(alarmId: Long): Boolean
}

/**
 * Sends the command as a plain `startService`: the ringing screen is in the foreground
 * when it's tapped, and the service it addresses is a foreground service, so background
 * start restrictions don't apply — but if the OS refuses anyway the command is reported
 * undelivered rather than silently dropped.
 */
class ServiceAlarmRingingCommands(private val context: Context) : AlarmRingingCommands {
    override fun snooze(alarmId: Long): Boolean = send(AlarmRingingService.snoozeIntent(context, alarmId))

    override fun dismiss(alarmId: Long): Boolean = send(AlarmRingingService.dismissIntent(context, alarmId))

    private fun send(intent: Intent): Boolean = try {
        context.startService(intent) != null
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

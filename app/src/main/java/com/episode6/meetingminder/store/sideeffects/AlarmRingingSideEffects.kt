package com.episode6.meetingminder.store.sideeffects

import android.content.Context
import com.episode6.meetingminder.alarm.AlarmNotifications
import com.episode6.meetingminder.alarm.AlarmRinger
import com.episode6.meetingminder.alarm.AlarmRingingCommands
import com.episode6.meetingminder.alarm.SnoozeResult
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.DismissAlarm
import com.episode6.meetingminder.store.SetRinging
import com.episode6.meetingminder.store.SilenceAlarm
import com.episode6.meetingminder.store.SnoozeAlarm
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import java.time.Clock

/**
 * The ringing screen's [SnoozeAlarm]/[DismissAlarm]/[SilenceAlarm] (TODO.md §4.4). `AlarmRingingService`
 * owns the ringing — the sound, the queue, the row writes it awaits before letting go of
 * the foreground — so the command is handed to it ([AlarmRingingCommands]) and the service
 * publishes the outcome back as `SetRinging`. Should the OS refuse to deliver it, the
 * snooze/dismiss is written to the row here instead and the screen cleared, so a tap is
 * never silently lost — and a snooze the OS then refuses to arm gets the same "missed
 * alarm" notification the service would post, rather than a row quietly `CANCELLED`.
 */
@ContributesTo(AppScope::class)
interface AlarmRingingSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun alarmRinging(commands: AlarmRingingCommands, ringer: AlarmRinger, context: Context, clock: Clock): SideEffect<AppState> = sideEffect {
        actions.filter { it is SnoozeAlarm || it is DismissAlarm || it is SilenceAlarm }.flatMapMerge { action ->
            flow {
                if (action is SilenceAlarm) {
                    // undelivered means no service, which means no sound: nothing to fall back to
                    commands.silence(action.alarmId)
                    return@flow
                }
                val alarmId = if (action is SnoozeAlarm) action.alarmId else (action as DismissAlarm).alarmId
                val delivered = if (action is SnoozeAlarm) commands.snooze(alarmId) else commands.dismiss(alarmId)
                if (!delivered) {
                    val ringing = currentState().ringing?.takeIf { it.alarmId == alarmId }
                    if (action is SnoozeAlarm) {
                        if (ringer.snooze(alarmId) == SnoozeResult.REFUSED && ringing != null) {
                            AlarmNotifications.postMissed(context, ringing, clock.zone)
                        }
                    } else {
                        ringer.dismiss(alarmId)
                    }
                    if (ringing != null) emit(SetRinging(null))
                }
            }
        }
    }
}

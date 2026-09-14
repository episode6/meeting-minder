package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.alarm.AlarmRinger
import com.episode6.meetingminder.alarm.AlarmRingingCommands
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.DismissAlarm
import com.episode6.meetingminder.store.SetRinging
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

/**
 * The ringing screen's [SnoozeAlarm]/[DismissAlarm] (TODO.md §4.4). `AlarmRingingService`
 * owns the ringing — the sound, the queue, the row writes it awaits before letting go of
 * the foreground — so the command is handed to it ([AlarmRingingCommands]) and the service
 * publishes the outcome back as `SetRinging`. Should the OS refuse to deliver it, the
 * snooze/dismiss is written to the row here instead and the screen cleared, so a tap is
 * never silently lost.
 */
@ContributesTo(AppScope::class)
interface AlarmRingingSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun alarmRinging(commands: AlarmRingingCommands, ringer: AlarmRinger): SideEffect<AppState> = sideEffect {
        actions.filter { it is SnoozeAlarm || it is DismissAlarm }.flatMapMerge { action ->
            flow {
                val alarmId = if (action is SnoozeAlarm) action.alarmId else (action as DismissAlarm).alarmId
                val delivered = if (action is SnoozeAlarm) commands.snooze(alarmId) else commands.dismiss(alarmId)
                if (!delivered) {
                    if (action is SnoozeAlarm) ringer.snooze(alarmId) else ringer.dismiss(alarmId)
                    if (currentState().ringing?.alarmId == alarmId) emit(SetRinging(null))
                }
            }
        }
    }
}

package com.episode6.meetingminder.ui.alarm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.episode6.meetingminder.model.RingingAlarm
import com.episode6.meetingminder.model.ScheduleChangeAlert
import com.episode6.meetingminder.model.isSyntheticAlarmEvent
import com.episode6.meetingminder.store.AppStore
import com.episode6.meetingminder.monitor.toLine
import com.episode6.meetingminder.store.DismissAlarm
import com.episode6.meetingminder.store.SilenceAlarm
import com.episode6.meetingminder.store.SnoozeAlarm
import com.episode6.redux.mapStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private const val STOP_TIMEOUT_MILLIS = 5_000L

/** The clock on the ringing screen ticks once a second. */
private const val TICK_MILLIS = 1_000L

/**
 * How long the ringing screen waits for a ringing alarm before closing: the full-screen
 * intent can launch it a moment before the store has reduced the service's `SetRinging`.
 */
internal const val RINGING_WAIT_MILLIS = 3_000L

/** What `AlarmActivity` does with the store's ringing alarm. */
sealed interface AlarmRingingUiState {
    /** Nothing is ringing yet; see [RINGING_WAIT_MILLIS]. */
    data object Waiting : AlarmRingingUiState

    /** [alarm] is ringing; callbacks are addressed to its id, so a tap never reaches an alarm the screen isn't showing. */
    data class Ringing(val alarm: RingingAlarm, val screen: AlarmRingingScreenState) : AlarmRingingUiState

    /** [alarm] is a day's schedule-change alert (TODO.md §4.3), ringing the same way. */
    data class ScheduleChanged(val alarm: RingingAlarm, val screen: ScheduleChangeAlertScreenState) : AlarmRingingUiState

    /** Nothing rings any more (snoozed, dismissed, timed out) or never did: the activity closes. */
    data object Finished : AlarmRingingUiState
}

/**
 * [AlarmRingingScreen]'s store adapter: the store's [com.episode6.meetingminder.store.AppState.ringing]
 * alarm (or schedule-change alert) with a ticking clock, and Snooze/Dismiss/Silence dispatched as [SnoozeAlarm]/[DismissAlarm]/[SilenceAlarm]
 * (which `AlarmRingingService` carries out). The activity closes on [AlarmRingingUiState.Finished].
 */
@Inject
@ViewModelKey(AlarmRingingViewModel::class)
@ContributesIntoMap(AppScope::class)
class AlarmRingingViewModel(private val store: AppStore, private val clock: Clock) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<AlarmRingingUiState> = combine(phases(), ticks()) { phase, now ->
        when (phase) {
            is Phase.Ringing -> when (val change = phase.alarm.scheduleChange) {
                null -> AlarmRingingUiState.Ringing(phase.alarm, phase.alarm.toScreenState(now, clock.zone))
                else -> AlarmRingingUiState.ScheduleChanged(phase.alarm, phase.alarm.toAlertScreenState(change, now, clock.zone))
            }
            Phase.Waiting -> AlarmRingingUiState.Waiting
            Phase.Finished -> AlarmRingingUiState.Finished
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AlarmRingingUiState.Waiting)

    fun onDismiss(alarmId: Long) {
        store.dispatch(DismissAlarm(alarmId))
    }

    fun onSnooze(alarmId: Long) {
        store.dispatch(SnoozeAlarm(alarmId))
    }

    /** The Silence button, or a volume key while the screen shows. */
    fun onSilence(alarmId: Long) {
        store.dispatch(SilenceAlarm(alarmId))
    }

    /**
     * A volume key went down while the screen shows. True when it was taken to silence what
     * is making a sound (and so mustn't move the volume); false once that is silent, or
     * nothing rings, and the key is an ordinary volume key again. A held key repeats, and
     * each repeat is swallowed here until the silenced alarm is published back.
     */
    fun onVolumeKey(): Boolean {
        val sounding = store.state.ringing?.takeIf { !it.silenced } ?: return false
        store.dispatch(SilenceAlarm(sounding.alarmId))
        return true
    }

    /**
     * The alert's "Open itinerary": answered, so dismissed; the activity opens the day view.
     * (Its "Sync & Re-share" needs nothing here: the activity opens the share link, and the
     * share itself dismisses the alert it answers.)
     */
    fun onOpenItinerary(alarmId: Long) {
        store.dispatch(DismissAlarm(alarmId))
    }

    /** "Open meeting": the user is on their way, so the alarm is dismissed; the activity opens the calendar. */
    fun onOpenMeeting(alarmId: Long) {
        store.dispatch(DismissAlarm(alarmId))
    }

    private sealed interface Phase {
        data object Waiting : Phase
        data class Ringing(val alarm: RingingAlarm) : Phase
        data object Finished : Phase
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun phases(): Flow<Phase> = store.mapStore { it.ringing }
        .runningFold(null as RingingAlarm? to false) { (_, seen), ringing -> ringing to (seen || ringing != null) }
        .transformLatest { (ringing, seen) ->
            when {
                ringing != null -> emit(Phase.Ringing(ringing))
                seen -> emit(Phase.Finished)
                else -> {
                    emit(Phase.Waiting)
                    delay(RINGING_WAIT_MILLIS)
                    emit(Phase.Finished)
                }
            }
        }
        .distinctUntilChanged()

    private fun ticks(): Flow<Instant> = flow {
        while (true) {
            val now = clock.instant()
            emit(now)
            delay(TICK_MILLIS - now.toEpochMilli() % TICK_MILLIS)
        }
    }
}

internal fun RingingAlarm.toScreenState(now: Instant, zone: ZoneId) = AlarmRingingScreenState(
    title = title,
    location = location,
    begin = begin.atZone(zone).toLocalTime(),
    end = end.atZone(zone).toLocalTime(),
    now = now.atZone(zone).toLocalTime(),
    minutesUntilStart = minutesUntil(now, begin),
    snoozeMinutes = snoozeLength.toMinutes(),
    soundName = soundName,
    canOpenMeeting = !isSyntheticAlarmEvent(key.eventId),
    silenced = silenced,
)

internal fun RingingAlarm.toAlertScreenState(change: ScheduleChangeAlert, now: Instant, zone: ZoneId) = ScheduleChangeAlertScreenState(
    now = now.atZone(zone).toLocalTime(),
    lines = change.changes.map { it.toLine(zone) },
    shareMode = change.shareMode,
    soundName = soundName,
    silenced = silenced,
)

/**
 * Whole minutes from [now] until [begin]: rounded up while the meeting is ahead ("in 1
 * minute" right until it starts), 0 in the minute it starts, and minus the whole minutes
 * elapsed once it has.
 */
internal fun minutesUntil(now: Instant, begin: Instant): Long {
    val seconds = Duration.between(now, begin).seconds
    return if (seconds > 0) (seconds + 59) / 60 else -(-seconds / 60)
}

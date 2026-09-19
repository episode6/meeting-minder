package com.episode6.meetingminder.model

import com.episode6.meetingminder.data.calendar.ShareMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The `scheduled_alarm.event_id` Settings' "Test alarm" row uses (`TestAlarmSideEffects`,
 * TODO.md §5 PR-12): never a real `Events._ID`, which is always positive. Shared between
 * `data/db` (excluded from [com.episode6.meetingminder.model.DayPlan.armedKeys], so the
 * test alarm never shows the day view's "Clear alarms" FAB with nothing selected) and the
 * ringing screen (which has no real event to open for it).
 */
const val TEST_ALARM_EVENT_ID = -1L

/**
 * The `scheduled_alarm.event_id` of a day's schedule-change alert (TODO.md §4.3's loud
 * alert, `alarm/ScheduleChangeAlerts`): one row per shared day, armed for "now" when a
 * background check finds a new change, so the alert rings through the same
 * `AlarmReceiver`/`AlarmRingingService`/`AlarmActivity` path as a meeting's alarm. Like
 * [TEST_ALARM_EVENT_ID] it is never a real `Events._ID`; see [isSyntheticAlarmEvent].
 */
const val SCHEDULE_CHANGE_ALARM_EVENT_ID = -2L

/**
 * True for the `event_id`s of rows that aren't a meeting's alarm ([TEST_ALARM_EVENT_ID],
 * [SCHEDULE_CHANGE_ALARM_EVENT_ID]): everything that folds `scheduled_alarm` rows into a
 * day's selection or reads their event back from the provider skips them.
 */
fun isSyntheticAlarmEvent(eventId: Long): Boolean = eventId < 0

/**
 * What a ringing schedule-change alert is about ([RingingAlarm.scheduleChange]): the day's
 * recorded [changes] (times only, like everything else the app says about a shared day),
 * and what a re-share of it would do right now ([ShareMode], TODO.md §4.7), which decides
 * its button — "Re-share", "Sync & Re-share" or, sync-only, "Re-sync" — and whether it says
 * "since you shared" or "since you synced".
 */
data class ScheduleChangeAlert(val changes: List<ScheduleChange>, val shareMode: ShareMode)

/**
 * The alarm that is ringing right now ([com.episode6.meetingminder.store.AppState.ringing]):
 * what `AlarmRingingService` is playing and the full-screen `AlarmActivity` shows (render
 * 5). Built from the denormalised `scheduled_alarm` row alone, never the provider, so it
 * works in a process the alarm itself just started.
 */
data class RingingAlarm(
    /** The `scheduled_alarm` row, and the identity every Snooze/Dismiss command is addressed by. */
    val alarmId: Long,
    val date: LocalDate,
    val key: EventKey,
    val title: String,
    val location: String?,
    val begin: Instant,
    val end: Instant,
    /** Seeds the randomised sound, so a snoozed alarm comes back sounding the same. */
    val soundIndex: Int,
    /** How long Snooze silences it for (a setting, read when the alarm fired). */
    val snoozeLength: Duration,
    /**
     * The name of the sound playing right now, for the ringing screen's subtle "Sound: …"
     * line (a debug aid); null until the player has started one. Changes with every
     * re-roll.
     */
    val soundName: String? = null,
    /**
     * The user silenced it (the Silence button, or a volume key, as for an incoming call):
     * the sound and vibration are off but it is still ringing — on screen, unanswered, and
     * subject to the auto-timeout.
     */
    val silenced: Boolean = false,
    /** Set when this is a day's schedule-change alert rather than a meeting's alarm; [title], [begin] and [end] then mean nothing. */
    val scheduleChange: ScheduleChangeAlert? = null,
)

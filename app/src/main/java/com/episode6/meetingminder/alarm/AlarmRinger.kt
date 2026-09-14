package com.episode6.meetingminder.alarm

import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.RingingAlarm
import dev.zacsweers.metro.Inject
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What [AlarmRinger.snooze] did. */
enum class SnoozeResult {
    /** Re-armed at the snooze time; it rings again then. */
    SNOOZED,

    /** The OS refused the new `setAlarmClock` (the exact-alarm grant is gone): the alarm is lost, so say so. */
    REFUSED,

    /** The alarm wasn't ringing (already snoozed or dismissed from elsewhere); nothing changed. */
    NOT_RINGING,
}

/** What [AlarmRinger.timeOut] did. */
enum class TimeoutResult {
    /** First time unanswered: snoozed once, like a Snooze tap. */
    SNOOZED,

    /** Unanswered again after that snooze: given up (`DISMISSED`); the ringing service posts a "missed alarm" notification. */
    GAVE_UP,

    /** Wanted to snooze but the OS refused; treated like [GAVE_UP] by the caller. */
    REFUSED,

    NOT_RINGING,
}

/**
 * The `scheduled_alarm` side of ringing (TODO.md §4.4), for [AlarmRingingSession]: every
 * transition of a ringing alarm's row, awaited by the ringing service before it lets go of
 * the foreground so a snooze is never lost to the process being reclaimed. Works from the
 * denormalised row alone, never the provider.
 */
@Inject
class AlarmRinger(
    private val dao: ScheduledAlarmDao,
    private val scheduler: AlarmScheduler,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    /**
     * Alarm [alarmId] went off: marks the row `FIRED` and returns what to ring. Null when
     * there is nothing to ring — the row is gone, or no longer armed (cancelled after the
     * OS had already queued the broadcast, or a duplicate delivery of one already ringing).
     */
    suspend fun fire(alarmId: Long): RingingAlarm? {
        val row = dao.byId(alarmId)?.takeIf { it.state.armed } ?: return null
        dao.setState(alarmId, AlarmState.FIRED)
        return row.toRingingAlarm(settings.current().snoozeLength)
    }

    /**
     * Snooze (TODO.md §4.4): the same row, `SNOOZED`, re-armed with a fresh `setAlarmClock`
     * at now + the snooze length — never a `Handler` delay, the process may well die in
     * between. The row is written before it is armed (never an alarm without its row), and
     * keeps its `alarm_id` so the `PendingIntent` is simply replaced.
     */
    suspend fun snooze(alarmId: Long): SnoozeResult = snooze(alarmId, timedOut = false)

    /** The ringing screen's Dismiss: `DISMISSED`. Returns false if it wasn't ringing. */
    suspend fun dismiss(alarmId: Long): Boolean {
        dao.byId(alarmId)?.takeIf { it.state == AlarmState.FIRED } ?: return false
        dao.setState(alarmId, AlarmState.DISMISSED)
        return true
    }

    /**
     * Nobody answered for the auto-timeout (TODO.md §4.4): the first time the alarm snoozes
     * itself (and remembers it did, in `timed_out`); unanswered again, it gives up.
     */
    suspend fun timeOut(alarmId: Long): TimeoutResult {
        val row = dao.byId(alarmId)?.takeIf { it.state == AlarmState.FIRED } ?: return TimeoutResult.NOT_RINGING
        if (row.timedOut) {
            dao.setState(alarmId, AlarmState.DISMISSED)
            return TimeoutResult.GAVE_UP
        }
        return when (snooze(alarmId, timedOut = true)) {
            SnoozeResult.SNOOZED -> TimeoutResult.SNOOZED
            SnoozeResult.REFUSED -> TimeoutResult.REFUSED
            SnoozeResult.NOT_RINGING -> TimeoutResult.NOT_RINGING
        }
    }

    /** How long an alarm rings before [timeOut]. */
    suspend fun autoTimeout(): Duration = settings.current().autoTimeout

    private suspend fun snooze(alarmId: Long, timedOut: Boolean): SnoozeResult {
        val row = dao.byId(alarmId)?.takeIf { it.state == AlarmState.FIRED } ?: return SnoozeResult.NOT_RINGING
        val snoozed = row.copy(
            state = AlarmState.SNOOZED,
            fireAt = clock.millis() + settings.current().snoozeLength.toMillis(),
            timedOut = row.timedOut || timedOut,
        )
        dao.update(snoozed)
        if (!scheduler.schedule(snoozed)) {
            dao.setState(alarmId, AlarmState.CANCELLED)
            return SnoozeResult.REFUSED
        }
        return SnoozeResult.SNOOZED
    }
}

internal fun ScheduledAlarmEntity.toRingingAlarm(snoozeLength: Duration) = RingingAlarm(
    alarmId = alarmId,
    date = date,
    key = key,
    title = title,
    location = location,
    begin = Instant.ofEpochMilli(beginMillis),
    end = Instant.ofEpochMilli(endMillis),
    soundIndex = soundIndex,
    snoozeLength = snoozeLength,
)

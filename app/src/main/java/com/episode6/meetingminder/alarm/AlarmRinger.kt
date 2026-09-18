package com.episode6.meetingminder.alarm

import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.RingingAlarm
import com.episode6.meetingminder.model.SCHEDULE_CHANGE_ALARM_EVENT_ID
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

    /**
     * A schedule-change alert went unanswered: it just stops (`DISMISSED`). Never snoozed
     * and never "missed" — the quiet schedule-changed notification is still in the shade.
     */
    EXPIRED,

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
    private val changeAlerts: ScheduleChangeAlertContent,
) {
    /**
     * Alarm [alarmId] went off: marks the row `FIRED` and returns what to ring. Null when
     * there is nothing to ring — the row is gone, or no longer armed (cancelled after the
     * OS had already queued the broadcast, or a duplicate delivery of one already ringing),
     * or it is a schedule-change alert whose changes are gone by now (re-shared meanwhile).
     */
    suspend fun fire(alarmId: Long): RingingAlarm? {
        val row = dao.byId(alarmId)?.takeIf { it.state.armed } ?: return null
        val alarm = row.toRingingAlarm(settings.current().snoozeLength)
        if (row.eventId != SCHEDULE_CHANGE_ALARM_EVENT_ID) {
            dao.setState(alarmId, AlarmState.FIRED)
            return alarm
        }
        val alert = changeAlerts.load(row.date)
        dao.setState(alarmId, if (alert == null) AlarmState.DISMISSED else AlarmState.FIRED)
        return alert?.let { alarm.copy(scheduleChange = it) }
    }

    /**
     * Snooze (TODO.md §4.4): the same row, `SNOOZED`, re-armed with a fresh `setAlarmClock`
     * at now + the snooze length — never a `Handler` delay, the process may well die in
     * between. The row is written before it is armed (never an alarm without its row), and
     * keeps its `alarm_id` so the `PendingIntent` is simply replaced.
     */
    suspend fun snooze(alarmId: Long): SnoozeResult = snooze(alarmId, timedOut = false)

    /**
     * The ringing screen's Dismiss: `DISMISSED`. Returns false if it wasn't ringing. A
     * dismissed schedule-change alert takes the day's quiet notification with it.
     */
    suspend fun dismiss(alarmId: Long): Boolean {
        val row = dao.byId(alarmId) ?: return false
        if (!leaveFired(alarmId, AlarmState.DISMISSED)) return false
        if (row.eventId == SCHEDULE_CHANGE_ALARM_EVENT_ID) changeAlerts.acknowledge(row.date)
        return true
    }

    /**
     * A schedule-change alert stops ringing without having been answered — its notification
     * was swiped away, or nobody was there ([timeOut]): `DISMISSED`, but not acknowledged, so
     * the day's quiet notification stays in the shade. Returns false if it wasn't ringing.
     */
    suspend fun expire(alarmId: Long): Boolean = leaveFired(alarmId, AlarmState.DISMISSED)

    // One conditional statement, never a read and then a write: `ScheduleChangeAlerts.alert`
    // re-arms a ringing alert's row (`FIRED` -> `SCHEDULED`), and an answer that read `FIRED`
    // just before must not write over that, or the newer change would fire into a row that
    // is no longer armed and ring nothing.
    private suspend fun leaveFired(alarmId: Long, to: AlarmState): Boolean = dao.transition(alarmId, AlarmState.FIRED, to) == 1

    /**
     * Nobody answered for the auto-timeout (TODO.md §4.4): the first time the alarm snoozes
     * itself (and remembers it did, in `timed_out`); unanswered again, it gives up.
     */
    suspend fun timeOut(alarmId: Long): TimeoutResult {
        val row = dao.byId(alarmId)?.takeIf { it.state == AlarmState.FIRED } ?: return TimeoutResult.NOT_RINGING
        if (row.eventId == SCHEDULE_CHANGE_ALARM_EVENT_ID) {
            return if (expire(alarmId)) TimeoutResult.EXPIRED else TimeoutResult.NOT_RINGING
        }
        if (row.timedOut) {
            return if (leaveFired(alarmId, AlarmState.DISMISSED)) TimeoutResult.GAVE_UP else TimeoutResult.NOT_RINGING
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
        if (row.eventId == SCHEDULE_CHANGE_ALARM_EVENT_ID) {
            // never snoozed (nothing offers it): a snooze that reaches the row anyway just
            // stops it, rather than bringing stale changes back in a few minutes
            expire(alarmId)
            return SnoozeResult.NOT_RINGING
        }
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

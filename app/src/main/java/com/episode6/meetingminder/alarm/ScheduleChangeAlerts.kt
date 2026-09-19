package com.episode6.meetingminder.alarm

import android.util.Log
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.calendar.ShareMode
import com.episode6.meetingminder.data.calendar.shareMode
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.ChangeSnapshotDao
import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.db.changeAlertOn
import com.episode6.meetingminder.data.db.decodeScheduleChanges
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.SCHEDULE_CHANGE_ALARM_EVENT_ID
import com.episode6.meetingminder.model.ScheduleChangeAlert
import com.episode6.meetingminder.monitor.ScheduleChangeAlerter
import com.episode6.meetingminder.monitor.ScheduleChangeNotifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.LocalDate
import kotlin.random.Random

private const val TAG = "ScheduleChangeAlerts"

/** What [AlarmRinger] needs to ring (and answer) a schedule-change alert row. */
interface ScheduleChangeAlertContent {
    /** What [date]'s alert is about right now; null when there is nothing left to ring for (re-shared, ended, or the changes went away). */
    suspend fun load(date: LocalDate): ScheduleChangeAlert?

    /** The user dismissed [date]'s alert: they have seen the changes, so the quiet notification goes too. */
    fun acknowledge(date: LocalDate)
}

/**
 * The loud schedule-change alert (TODO.md §4.3): the `scheduled_alarm` side of it, so it
 * rings through the very path a meeting's alarm does. A background change check can't start
 * a foreground service or play audio by itself; an alarm-clock alarm going off can. So
 * [alert] arms the day's one [SCHEDULE_CHANGE_ALARM_EVENT_ID] row for *now* through
 * [AlarmScheduler], `AlarmReceiver` starts `AlarmRingingService`, and [AlarmRinger.fire]
 * comes back here ([load]) for the changes to show — read fresh from `change_snapshot`, so
 * the alert always says what the banner and the quiet notification say.
 *
 * One row per day, re-armed for every new change: an alert still ringing (or silenced) is
 * replaced by `AlarmRingingSession` rather than queued behind itself. The row is written
 * before it is armed, like every alarm.
 */
@Inject
@SingleIn(AppScope::class)
class ScheduleChangeAlerts(
    private val alarmDao: ScheduledAlarmDao,
    private val scheduler: AlarmScheduler,
    private val snapshotDao: ChangeSnapshotDao,
    private val settings: SettingsRepository,
    private val repository: CalendarRepository,
    private val notifier: ScheduleChangeNotifier,
    private val commands: AlarmRingingCommands,
    private val clock: Clock,
    private val random: Random,
) : ScheduleChangeAlerter, ScheduleChangeAlertContent {

    override suspend fun alert(date: LocalDate): Boolean {
        if (!scheduler.canScheduleExactAlarms()) return false
        val now = clock.millis()
        val existing = alarmDao.changeAlertOn(date)
        val row = (existing ?: newRow(date, now))
            // a fresh draw every time: each alert sounds different, like each alarm
            .copy(fireAt = now, beginMillis = now, endMillis = now, soundIndex = random.nextInt(Int.MAX_VALUE), state = AlarmState.SCHEDULED, timedOut = false)
        val armed = if (existing == null) row.copy(alarmId = alarmDao.insert(row)) else row.also { alarmDao.update(it) }
        if (!scheduler.schedule(armed)) {
            alarmDao.setState(armed.alarmId, AlarmState.CANCELLED)
            return false
        }
        return true
    }

    override suspend fun cancel(date: LocalDate) {
        val row = alarmDao.changeAlertOn(date) ?: return
        when {
            row.state.armed -> {
                scheduler.cancel(row.alarmId)
                alarmDao.setState(row.alarmId, AlarmState.CANCELLED)
            }
            // ringing: the service owns the sound and the foreground, so it does the dismissing
            row.state == AlarmState.FIRED -> if (!commands.dismiss(row.alarmId)) alarmDao.transition(row.alarmId, AlarmState.FIRED, AlarmState.DISMISSED)
        }
    }

    override suspend fun load(date: LocalDate): ScheduleChangeAlert? {
        if (date < LocalDate.now(clock)) return null
        val changes = snapshotDao.forDate(date)?.let { decodeScheduleChanges(date, it.changesJson) }.orEmpty()
        if (changes.isEmpty()) return null
        return ScheduleChangeAlert(changes, shareMode = shareMode())
    }

    override fun acknowledge(date: LocalDate) {
        notifier.cancel(date)
    }

    // The title and times mean nothing for this row; what it shows is loaded when it fires.
    // `endMillis = now` is deliberate all the same: `AlarmRescheduler` only re-arms rows whose
    // event hasn't ended, so an alert caught armed by a reboot is never re-fired hours later.
    private fun newRow(date: LocalDate, now: Long) = ScheduledAlarmEntity(
        date = date, eventId = SCHEDULE_CHANGE_ALARM_EVENT_ID, instanceTime = 0, fireAt = now,
        title = "", beginMillis = now, endMillis = now, soundIndex = 0,
    )

    // only decides the words on the alert, so any failure reads as "no sync"
    private suspend fun shareMode(): ShareMode = try {
        val busySync = settings.current().busySync
        if (busySync.enabled) shareMode(busySync, repository.calendars()) else ShareMode.TEXT
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "couldn't read the busy calendar", e)
        ShareMode.TEXT
    }
}

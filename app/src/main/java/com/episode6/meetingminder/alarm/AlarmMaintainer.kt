package com.episode6.meetingminder.alarm

import android.util.Log
import com.episode6.meetingminder.data.calendar.CalendarFilter
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.SelfStatus
import com.episode6.meetingminder.model.isSyntheticAlarmEvent
import com.episode6.meetingminder.permissions.PermissionChecker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val TAG = "MeetingMinderAlarms"

/** What [maintainAlarms] found to do; `AlarmMaintainer` applies it. */
data class AlarmMaintenance(
    /** Rows whose event moved: update the row (fresh `SCHEDULED` state) and re-arm it. */
    val retime: List<ScheduledAlarmEntity> = emptyList(),
    /** Rows whose event is now cancelled or declined by me: disarm and mark `CANCELLED`. */
    val cancel: List<ScheduledAlarmEntity> = emptyList(),
    /** Rows that only need their denormalised copy refreshed, without touching the alarm. */
    val refresh: List<ScheduledAlarmEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = retime.isEmpty() && cancel.isEmpty() && refresh.isEmpty()
}

/**
 * The narrower, automatic reconcile (TODO.md §4.4 `MaintainAlarms`): unlike "Set alarms" it
 * only ever touches rows that are already armed ([armed]), never arms a newly selected event
 * and never RSVPs. For each row, by the event now in [fresh] under its key:
 *  - absent: nothing. An event that vanished from the provider (including one the organizer
 *    cancelled, which the repository's query filters out) keeps its alarm until the day
 *    ends — a stale alarm after a sync hiccup is cheaper than a missed meeting, and the
 *    change banner still reports it. The explicit "Set alarms" tap is what cancels it
 *    (`reconcileAlarms`' `providerRead`);
 *  - cancelled or declined by me: cancel the alarm (the selection is kept, so the banner can
 *    explain), and put the day back to "Set alarms": its armed set no longer matches its
 *    selection, so a later re-accept in Google Calendar can be re-armed from the FAB;
 *  - over already: nothing;
 *  - same times: refresh a changed title or location only;
 *  - moved: re-time it to the new begin − [leadTime], as a fresh `SCHEDULED` alarm. A new
 *    alarm time already past (the meeting was pulled forward to start within the lead time,
 *    or dragged so that it is already in progress — `begin < now < end` — which "Set alarms"
 *    would skip as past) still arms, and so rings at once: the user can't be asked from
 *    here. A `SNOOZED` row keeps its snooze while its new alarm time has passed (only its
 *    copy is refreshed), as the "Set alarms" reconcile does.
 *
 * Settings' test alarm and a schedule-change alert ([isSyntheticAlarmEvent]) are never touched.
 */
fun maintainAlarms(
    armed: List<ScheduledAlarmEntity>,
    fresh: Map<EventKey, CalendarEvent>,
    leadTime: Duration,
    now: Instant,
): AlarmMaintenance {
    val nowMillis = now.toEpochMilli()
    val retime = mutableListOf<ScheduledAlarmEntity>()
    val cancel = mutableListOf<ScheduledAlarmEntity>()
    val refresh = mutableListOf<ScheduledAlarmEntity>()
    for (row in armed) {
        if (isSyntheticAlarmEvent(row.eventId) || !row.state.armed) continue
        val event = fresh[row.key] ?: continue
        val begin = event.begin.toEpochMilli()
        val end = event.end.toEpochMilli()
        when {
            event.status == EventStatus.CANCELED || event.selfStatus == SelfStatus.DECLINED -> cancel += row
            end <= nowMillis -> Unit
            begin == row.beginMillis && end == row.endMillis -> {
                if (event.title != row.title || event.location != row.location) {
                    refresh += row.copy(title = event.title, location = event.location)
                }
            }
            else -> {
                val fireAt = alarmTimeFor(begin, leadTime)
                val updated = row.copy(title = event.title, location = event.location, beginMillis = begin, endMillis = end)
                if (row.state == AlarmState.SNOOZED && fireAt <= nowMillis) {
                    refresh += updated
                } else {
                    retime += updated.copy(fireAt = fireAt, state = AlarmState.SCHEDULED, timedOut = false)
                }
            }
        }
    }
    return AlarmMaintenance(retime = retime, cancel = cancel, refresh = refresh)
}

/**
 * Applies [maintainAlarms] to every armed row whose event hasn't ended (TODO.md §4.4): on
 * `CalendarContentChanged` in the app (`MaintainAlarmsSideEffects`), from every background
 * change check (`monitor/CalendarChangeWorker`, so a meeting moved while the app is away is
 * re-timed without waiting for it to be opened), and from [BootReceiver] after boot, a
 * wall-clock or timezone change and an app update, once the stored alarms are re-armed. A
 * timezone change doesn't move a timed event's instant, but the provider re-expands its
 * instances in the new zone (a floating-time event, a recurrence expanded in local time),
 * so the events are read fresh and any that moved are re-timed.
 *
 * Each armed row's day is read with the day either side of it — a zone change can move an
 * occurrence across midnight — from every calendar, whatever the Settings filter says: the
 * alarm was armed on purpose, and hiding its calendar later mustn't read as the event
 * vanishing. Without calendar access nothing is read or changed. The reads can be
 * cancelled; the writes can't, so a cancelled run never leaves a row re-timed in Room but
 * not in `AlarmManager`. A cancelled row and a re-timed row the OS refuses to arm (marked
 * `CANCELLED`) both clear their day's `alarms_set_at`, so it goes back to "Set alarms", as
 * the reconcile does whenever the armed set stops matching the selection.
 */
@Inject
@SingleIn(AppScope::class)
class AlarmMaintainer(
    private val repository: CalendarRepository,
    private val alarmDao: ScheduledAlarmDao,
    private val dayPlanDao: DayPlanDao,
    private val scheduler: AlarmScheduler,
    private val settings: SettingsRepository,
    private val permissionChecker: PermissionChecker,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** Returns the [AlarmMaintenance] that was applied (empty when there was nothing to do or nothing could be read). */
    suspend fun maintain(): AlarmMaintenance = mutex.withLock {
        if (!permissionChecker.currentState().calendarGranted) return@withLock AlarmMaintenance()
        val now = clock.instant()
        val armed = alarmDao.allScheduled().filter { !isSyntheticAlarmEvent(it.eventId) && it.endMillis > now.toEpochMilli() }
        if (armed.isEmpty()) return@withLock AlarmMaintenance()
        val fresh = try {
            val everyCalendar = CalendarFilter.Only(repository.calendars().mapTo(mutableSetOf()) { it.id })
            armed.flatMapTo(sortedSetOf()) { listOf(it.date.minusDays(1), it.date, it.date.plusDays(1)) }
                .flatMap { day -> repository.eventsOn(day, everyCalendar) }
                .associateBy { it.key }
        } catch (e: SecurityException) {
            Log.w(TAG, "calendar access lost; alarms left as they are", e)
            return@withLock AlarmMaintenance()
        }
        val plan = maintainAlarms(armed, fresh, settings.current().leadTime, now)
        if (!plan.isEmpty) withContext(NonCancellable) { apply(plan) }
        plan
    }

    private suspend fun apply(plan: AlarmMaintenance) {
        for (row in plan.cancel) {
            scheduler.cancel(row.alarmId)
            alarmDao.setState(row.alarmId, AlarmState.CANCELLED)
            pointSelectionAt(row, armed = false)
            dayPlanDao.setAlarmsSetAt(row.date, null)
        }
        for (row in plan.refresh) alarmDao.update(row)
        for (row in plan.retime) {
            alarmDao.update(row)
            if (scheduler.schedule(row)) {
                pointSelectionAt(row, armed = true)
            } else {
                alarmDao.setState(row.alarmId, AlarmState.CANCELLED)
                pointSelectionAt(row, armed = false)
                dayPlanDao.setAlarmsSetAt(row.date, null)
            }
        }
    }

    private suspend fun pointSelectionAt(row: ScheduledAlarmEntity, armed: Boolean) {
        dayPlanDao.armSelectedEvent(
            row.date, row.eventId, row.instanceTime,
            alarmId = row.alarmId.takeIf { armed }, alarmAt = row.fireAt.takeIf { armed },
            title = row.title, beginMillis = row.beginMillis, endMillis = row.endMillis,
        )
    }
}

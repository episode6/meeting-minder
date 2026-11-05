package com.episode6.meetingminder.alarm

import android.util.Log
import com.episode6.meetingminder.data.calendar.CalendarFilter
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.ChangeSnapshotDao
import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.db.SelectedEventEntity
import com.episode6.meetingminder.data.db.baseline
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.SelfStatus
import com.episode6.meetingminder.model.isSyntheticAlarmEvent
import com.episode6.meetingminder.permissions.PermissionChecker
import com.episode6.meetingminder.share.BusyCalendarSyncer
import com.episode6.meetingminder.share.BusySyncResult
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private const val TAG = "MeetingMinderAlarms"

/** What [maintainAlarms] found to do; `AlarmMaintainer` applies it. */
data class AlarmMaintenance(
    /** Rows whose event moved: update the row (fresh `SCHEDULED` state) and re-arm it. */
    val retime: List<ScheduledAlarmEntity> = emptyList(),
    /** Rows whose event is now cancelled or declined by me: disarm and mark `CANCELLED`. */
    val cancel: List<ScheduledAlarmEntity> = emptyList(),
    /** Rows that only need their denormalised copy refreshed, without touching the alarm. */
    val refresh: List<ScheduledAlarmEntity> = emptyList(),
    /**
     * Selections whose meeting is now cancelled or declined by me: deleted, with their
     * time taken out of the day's busy blocks first (`BusyCalendarSyncer.trim`).
     */
    val drop: List<SelectedEventEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = retime.isEmpty() && cancel.isEmpty() && refresh.isEmpty() && drop.isEmpty()
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
 *  - cancelled or declined by me: cancel the alarm. Its selection is dropped too (below),
 *    so the rest of the day still matches its armed set; only a row whose selection wasn't
 *    dropped (deselected already, or its busy blocks couldn't be trimmed) puts the day
 *    back to "Set alarms";
 *  - over already: nothing;
 *  - same times: refresh a changed title or location only;
 *  - moved: re-time it to the new begin − [leadTime], as a fresh `SCHEDULED` alarm. A new
 *    alarm time already past (the meeting was pulled forward to start within the lead time,
 *    or dragged so that it is already in progress — `begin < now < end` — which "Set alarms"
 *    would skip as past) still arms, and so rings at once: the user can't be asked from
 *    here. A `SNOOZED` row keeps its snooze while its new alarm time has passed (only its
 *    copy is refreshed), as the "Set alarms" reconcile does.
 *
 * And for each of [selections] (armed or not) whose meeting hasn't ended, by the same
 * [fresh] read: one now cancelled or declined by me is [dropped][AlarmMaintenance.drop].
 * A declined chip can't be tapped, so without this nothing could deselect it, and its
 * stored time would keep going out in the share text and the busy-calendar sync. An
 * absent event keeps its selection, as it keeps its alarm. The change banner still
 * reports the decline: the differ reads "selected" from the share's baseline, not from
 * the selection.
 *
 * Settings' test alarm and a schedule-change alert ([isSyntheticAlarmEvent]) are never touched.
 */
fun maintainAlarms(
    armed: List<ScheduledAlarmEntity>,
    fresh: Map<EventKey, CalendarEvent>,
    leadTime: Duration,
    now: Instant,
    selections: List<SelectedEventEntity> = emptyList(),
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
            event.notAttending -> cancel += row
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
    val drop = selections.filter { selection ->
        selection.endMillis > nowMillis && fresh[selection.key]?.notAttending == true
    }
    return AlarmMaintenance(retime = retime, cancel = cancel, refresh = refresh, drop = drop)
}

private val CalendarEvent.notAttending: Boolean get() = status == EventStatus.CANCELED || selfStatus == SelfStatus.DECLINED

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
 * not in `AlarmManager`. A cancelled row whose selection wasn't dropped and a re-timed row
 * the OS refuses to arm (marked `CANCELLED`) both clear their day's `alarms_set_at`, so it goes back to "Set alarms", as
 * the reconcile does whenever the armed set stops matching the selection.
 *
 * A selection whose meeting was declined or cancelled ([AlarmMaintenance.drop]) has its time
 * taken out of the day's busy blocks ([BusyCalendarSyncer.trim]) before it is deleted, so
 * the partner's calendar loses the meeting without the day being re-shared; the time cut
 * is what the share wrote for it (the baseline's time, or the stored one when the baseline
 * doesn't have it), minus whatever the day's other selections still cover. A trim that
 * fails keeps the selection for the next pass to try again. A day left with nothing
 * selected goes back to "nothing picked", as the "Set alarms" reconcile's does.
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
    private val changeSnapshotDao: ChangeSnapshotDao,
    private val busyCalendarSyncer: BusyCalendarSyncer,
) {
    private val mutex = Mutex()

    /** Returns the [AlarmMaintenance] that was applied (empty when there was nothing to do or nothing could be read). */
    suspend fun maintain(): AlarmMaintenance = mutex.withLock {
        if (!permissionChecker.currentState().calendarGranted) return@withLock AlarmMaintenance()
        val now = clock.instant()
        val armed = alarmDao.allScheduled().filter { !isSyntheticAlarmEvent(it.eventId) && it.endMillis > now.toEpochMilli() }
        val selections = dayPlanDao.selectedEventsEndingAfter(now.toEpochMilli())
        if (armed.isEmpty() && selections.isEmpty()) return@withLock AlarmMaintenance()
        val fresh = try {
            val everyCalendar = CalendarFilter.Only(repository.calendars().mapTo(mutableSetOf()) { it.id })
            (armed.map { it.date } + selections.map { it.date })
                .flatMapTo(sortedSetOf()) { listOf(it.minusDays(1), it, it.plusDays(1)) }
                .flatMap { day -> repository.eventsOn(day, everyCalendar) }
                .associateBy { it.key }
        } catch (e: SecurityException) {
            Log.w(TAG, "calendar access lost; alarms left as they are", e)
            return@withLock AlarmMaintenance()
        }
        val plan = maintainAlarms(armed, fresh, settings.current().leadTime, now, selections)
        if (!plan.isEmpty) withContext(NonCancellable) { apply(plan, fresh) }
        plan
    }

    private suspend fun apply(plan: AlarmMaintenance, fresh: Map<EventKey, CalendarEvent>) {
        val dropped = mutableSetOf<Pair<LocalDate, EventKey>>()
        for ((date, rows) in plan.drop.groupBy { it.date }) {
            if (!trimBusyBlocks(date, rows, fresh)) continue
            for (row in rows) {
                dayPlanDao.deleteSelectedEvent(row.date, row.eventId, row.instanceTime)
                dropped += row.date to row.key
            }
            if (dayPlanDao.selectedEventsOn(date).isEmpty()) dayPlanDao.setAlarmsSetAt(date, null)
        }
        for (row in plan.cancel) {
            scheduler.cancel(row.alarmId)
            alarmDao.setState(row.alarmId, AlarmState.CANCELLED)
            // its selection went with it, and what is left of the day still matches its armed set
            if (row.date to row.key in dropped) continue
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

    /**
     * Takes [dropping]'s time out of [date]'s busy blocks; false when that failed, so the
     * selections stay for the next pass. A selection the share didn't include (picked after
     * it: in the baseline, but not selected there) has nothing on the calendar to take out.
     */
    private suspend fun trimBusyBlocks(date: LocalDate, dropping: List<SelectedEventEntity>, fresh: Map<EventKey, CalendarEvent>): Boolean {
        val baseline = changeSnapshotDao.forDate(date)?.baseline().orEmpty().associateBy { it.key }
        val remove = dropping.mapNotNull { selection ->
            val was = baseline[selection.key]
            when {
                was == null -> selection.storedRange
                was.selected -> BusyRange(was.begin, was.end)
                else -> null
            }
        }
        if (remove.isEmpty()) return true
        val droppingKeys = dropping.mapTo(mutableSetOf()) { it.key }
        // every time a remaining selection could have been shared at stays busy
        val keep = dayPlanDao.selectedEventsOn(date).filter { it.key !in droppingKeys }.flatMap { selection ->
            listOfNotNull(
                selection.storedRange,
                baseline[selection.key]?.let { BusyRange(it.begin, it.end) },
                fresh[selection.key]?.let { BusyRange(it.begin, it.end) },
            )
        }
        return try {
            busyCalendarSyncer.trim(date, remove, keep) !is BusySyncResult.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not take $date's declined meetings out of the busy calendar; selections kept", e)
            false
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

private val SelectedEventEntity.storedRange: BusyRange
    get() = BusyRange(Instant.ofEpochMilli(beginMillis), Instant.ofEpochMilli(endMillis))

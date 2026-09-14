package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.R
import com.episode6.meetingminder.alarm.AlarmReconciliation
import com.episode6.meetingminder.alarm.AlarmScheduler
import com.episode6.meetingminder.alarm.reconcileAlarms
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.SetAlarms
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.UiMessage
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Applies [reconcileAlarms] for [SetAlarms] (TODO.md §4.4): reads the day's selection and
 * armed rows from Room, the fresh events from the loaded window (so a moved event is
 * re-timed), and the lead time from settings; then cancels, inserts, re-times and arms
 * through [AlarmScheduler], points each selection at its alarm, and records
 * `alarms_set_at`. `ObserveDayPlansSideEffects` streams the result back into the store;
 * this effect only emits the snackbar ("3 alarms set for today", how many were skipped
 * because their alarm time had passed — never silently dropped — or "3 alarms cleared"
 * when the selection had been emptied and the tap only cancelled).
 *
 * Without the exact-alarm grant nothing is written: the snackbar says so and a permission
 * re-check is requested. If the OS refuses an individual `setAlarmClock` (the grant was
 * revoked between the check and the call), `alarms_set_at` is left clear so the FAB
 * keeps reading "Set alarms (N)" and the tap can be retried once the grant is back.
 * `flatMapMerge` keeps the relay path non-suspending; the mutex serialises reconciles so
 * a double tap can't interleave two of them.
 */
@ContributesTo(AppScope::class)
interface ScheduleAlarmsSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun scheduleAlarms(
        dayPlanDao: DayPlanDao,
        alarmDao: ScheduledAlarmDao,
        scheduler: AlarmScheduler,
        settings: SettingsRepository,
        clock: Clock,
        random: Random,
    ): SideEffect<AppState> = sideEffect {
        val reconciler = AlarmReconcileWriter(dayPlanDao, alarmDao, scheduler, settings, clock, random)
        val mutex = Mutex()
        actions.filterIsInstance<SetAlarms>().flatMapMerge { action ->
            flow {
                if (!scheduler.canScheduleExactAlarms()) {
                    emit(ShowMessage(UiMessage.next(R.string.alarms_exact_permission_missing)))
                    emit(PermissionsMaybeChanged)
                    return@flow
                }
                val state = currentState()
                val result = mutex.withLock {
                    reconciler.apply(action.date, state.eventsByDay[action.date]?.events.orEmpty())
                }
                if (result.failedToArm > 0) {
                    emit(ShowMessage(UiMessage.next(R.string.alarms_exact_permission_missing)))
                    emit(PermissionsMaybeChanged)
                } else {
                    emit(ShowMessage(alarmsSetMessage(result.reconciliation, action.date, today = state.anchorDate)))
                }
            }
        }
    }
}

/** The outcome of one applied reconcile; [failedToArm] counts rows the OS refused to arm. */
internal data class AppliedReconciliation(val reconciliation: AlarmReconciliation, val failedToArm: Int)

/** Writes one [reconcileAlarms] result through the DAOs and [AlarmScheduler]. */
internal class AlarmReconcileWriter(
    private val dayPlanDao: DayPlanDao,
    private val alarmDao: ScheduledAlarmDao,
    private val scheduler: AlarmScheduler,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val random: Random,
) {
    suspend fun apply(date: LocalDate, freshEvents: List<CalendarEvent>): AppliedReconciliation {
        val now = clock.instant()
        val plan = reconcileAlarms(
            date = date,
            selected = dayPlanDao.selectedEventsOn(date),
            freshEvents = freshEvents,
            scheduled = alarmDao.scheduledOn(date),
            leadTime = settings.current().leadTime,
            now = now,
            soundIndex = { random.nextInt(Int.MAX_VALUE) },
        )
        for (row in plan.cancel) {
            scheduler.cancel(row.alarmId)
            alarmDao.setState(row.alarmId, AlarmState.CANCELLED)
        }
        for (selection in plan.skipped) {
            dayPlanDao.armSelectedEvent(
                date, selection.eventId, selection.instanceTime,
                alarmId = null, alarmAt = null,
                title = selection.title, beginMillis = selection.beginMillis, endMillis = selection.endMillis,
            )
        }
        var failed = 0
        for (row in plan.schedule) {
            if (!arm(row.copy(alarmId = alarmDao.insert(row)))) failed++
        }
        for (row in plan.retime) {
            alarmDao.update(row)
            if (!arm(row)) failed++
        }
        // a kept row's selection may have been deleted and re-inserted (toggled off and on
        // again) since it was armed, which drops its alarm pointer: point it back
        for (row in plan.keep) pointSelectionAt(row)
        // "alarms set" (the Share FAB) only once every selection is armed or skipped: a
        // refused arm, or an emptied selection that only cancelled, leaves the day in
        // "Set alarms" so it can be re-tapped, or hidden with nothing armed or selected
        if (failed == 0 && !plan.clearsTheDay) {
            dayPlanDao.markAlarmsSet(date, now.toEpochMilli())
        } else {
            dayPlanDao.setAlarmsSetAt(date, null)
        }
        return AppliedReconciliation(plan, failed)
    }

    /**
     * A refused row is marked `CANCELLED` (it isn't armed, so it must not be reported as
     * such or re-armed after boot); the next reconcile finds no `SCHEDULED` row for the key
     * and inserts a fresh one, which is the retry.
     */
    private suspend fun arm(row: ScheduledAlarmEntity): Boolean {
        if (!scheduler.schedule(row)) {
            alarmDao.setState(row.alarmId, AlarmState.CANCELLED)
            return false
        }
        pointSelectionAt(row)
        return true
    }

    private suspend fun pointSelectionAt(row: ScheduledAlarmEntity) {
        dayPlanDao.armSelectedEvent(
            row.date, row.eventId, row.instanceTime,
            alarmId = row.alarmId, alarmAt = row.fireAt,
            title = row.title, beginMillis = row.beginMillis, endMillis = row.endMillis,
        )
    }
}

private val DayLabelFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")

/**
 * The snackbar after "Set alarms": how many are armed for the day, how many were skipped as
 * already past, or how many were cleared when nothing was selected any more.
 */
internal fun alarmsSetMessage(plan: AlarmReconciliation, date: LocalDate, today: LocalDate): UiMessage {
    val armed = plan.armedCount
    val skipped = plan.skipped.size
    return when {
        plan.clearsTheDay -> UiMessage.nextPlural(R.plurals.day_alarms_cleared, plan.cancel.size, plan.cancel.size)
        skipped == 0 && date == today -> UiMessage.nextPlural(R.plurals.day_alarms_set_today, armed, armed)
        skipped == 0 -> UiMessage.nextPlural(R.plurals.day_alarms_set_on_day, armed, armed, date.format(DayLabelFormatter))
        armed == 0 -> UiMessage.nextPlural(R.plurals.day_alarms_skipped, skipped, skipped)
        else -> UiMessage.next(R.string.day_alarms_set_some_skipped, armed, skipped)
    }
}

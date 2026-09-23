package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.model.SCHEDULE_CHANGE_ALARM_EVENT_ID
import android.util.Log
import com.episode6.meetingminder.R
import com.episode6.meetingminder.alarm.AlarmReconciliation
import com.episode6.meetingminder.alarm.AlarmScheduler
import com.episode6.meetingminder.alarm.reconcileAlarms
import com.episode6.meetingminder.data.calendar.CalendarFilter
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.RsvpState
import com.episode6.meetingminder.model.rsvpDecision
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.RsvpAccept
import com.episode6.meetingminder.store.SetAlarms
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.UiMessage
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.CancellationException
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

private const val TAG = "MeetingMinderAlarms"

/**
 * Applies [reconcileAlarms] for [SetAlarms] (TODO.md §4.4): reads the day's selection and
 * armed rows from Room, the fresh events from the provider ([freshEventsFor]: every
 * calendar, declined events included, so a moved event is re-timed and a since-declined
 * one is recognised whatever Settings hides, and a selection whose meeting is gone from
 * the calendar altogether — cancelled by its organizer, deleted, or a series moved to new
 * times, which gives every occurrence a new key — has its alarm cancelled rather than left
 * to ring for the old time), and the lead time from settings; then
 * cancels, inserts, re-times and arms through [AlarmScheduler], points each selection at
 * its alarm, and records `alarms_set_at`. `ObserveDayPlansSideEffects` streams the result
 * back into the store; this effect emits the snackbar ("3 alarms set for today", how many
 * were skipped because their alarm time had passed or their meeting was declined or
 * cancelled — never silently dropped — or "3 alarms cleared" when the selection had been
 * emptied and the tap only cancelled) and then one
 * [RsvpAccept] per newly armed event that [rsvpDecision] says to answer (TODO.md §4.6):
 * setting alarms is the commitment moment, so the same tap tells the calendar. The
 * decision is recorded on the selection row first (`PENDING`, or the skip reason so the
 * chip can show "couldn't RSVP"); `RsvpAcceptSideEffects` does the write, which never
 * holds up the alarms.
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
        repository: CalendarRepository,
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
                val fresh = freshEventsFor(action.date, repository)
                val result = mutex.withLock {
                    reconciler.apply(
                        action.date,
                        freshEvents = fresh ?: state.eventsByDay[action.date]?.events.orEmpty(),
                        providerRead = fresh != null,
                    )
                }
                if (result.failedToArm > 0) {
                    emit(ShowMessage(UiMessage.next(R.string.alarms_exact_permission_missing)))
                    emit(PermissionsMaybeChanged)
                } else {
                    emit(ShowMessage(alarmsSetMessage(result.reconciliation, action.date, today = state.anchorDate)))
                }
                for (key in result.rsvp) emit(RsvpAccept(action.date, key))
            }
        }
    }
}

/**
 * The events the reconcile compares the selection against: the day from every calendar,
 * declined and cancelled events included — the same read `alarm/AlarmMaintainer` makes, so
 * a selection whose meeting was since declined is never armed here only to be cancelled
 * there, and hiding its calendar or "show declined" in Settings can't make a selected
 * event read as vanished. Null when the provider can't be read: the caller then falls back
 * to the loaded window (already filtered for display), and [reconcileAlarms] is told so,
 * since an event missing from that window isn't known to be gone.
 */
private suspend fun freshEventsFor(date: LocalDate, repository: CalendarRepository): List<CalendarEvent>? = try {
    repository.eventsOn(date, CalendarFilter.Only(repository.calendars().mapTo(mutableSetOf()) { it.id }))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "calendar unreadable; reconciling $date against the loaded window", e)
    null
}

/**
 * The outcome of one applied reconcile: [failedToArm] counts rows the OS refused to arm,
 * [rsvp] is the newly armed events whose RSVP is to be written (`PENDING` on their rows).
 */
internal data class AppliedReconciliation(
    val reconciliation: AlarmReconciliation,
    val failedToArm: Int,
    val rsvp: List<EventKey> = emptyList(),
)

/** Writes one [reconcileAlarms] result through the DAOs and [AlarmScheduler]. */
internal class AlarmReconcileWriter(
    private val dayPlanDao: DayPlanDao,
    private val alarmDao: ScheduledAlarmDao,
    private val scheduler: AlarmScheduler,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val random: Random,
) {
    /** [providerRead]: [freshEvents] is the provider's whole day, not the loaded window's fallback (see [reconcileAlarms]). */
    suspend fun apply(date: LocalDate, freshEvents: List<CalendarEvent>, providerRead: Boolean): AppliedReconciliation {
        val now = clock.instant()
        val plan = reconcileAlarms(
            date = date,
            selected = dayPlanDao.selectedEventsOn(date),
            freshEvents = freshEvents,
            // A schedule-change alert armed for this very second isn't the selection's to
            // cancel. Only that row, not every synthetic one: Settings' test alarm is meant to
            // be cancelled here like any alarm that isn't in the selection (TestAlarmSideEffects).
            scheduled = alarmDao.scheduledOn(date).filter { it.eventId != SCHEDULE_CHANGE_ALARM_EVENT_ID },
            leadTime = settings.current().leadTime,
            now = now,
            soundIndex = { random.nextInt(Int.MAX_VALUE) },
            providerRead = providerRead,
        )
        for (row in plan.cancel) {
            scheduler.cancel(row.alarmId)
            alarmDao.setState(row.alarmId, AlarmState.CANCELLED)
        }
        // skipped and not-attending selections get no alarm, just their copy refreshed
        for (selection in plan.skipped + plan.notAttending) {
            dayPlanDao.armSelectedEvent(
                date, selection.eventId, selection.instanceTime,
                alarmId = null, alarmAt = null,
                title = selection.title, beginMillis = selection.beginMillis, endMillis = selection.endMillis,
            )
        }
        var failed = 0
        val rsvp = mutableListOf<EventKey>()
        val fresh = freshEvents.associateBy { it.key }
        for (row in plan.schedule) {
            if (!arm(row.copy(alarmId = alarmDao.insert(row)))) {
                failed++
                continue
            }
            // only an event that actually got an alarm is answered, and only a newly armed
            // one: a kept row was decided when it was first armed. An event the provider no
            // longer returns can't be answered at all (its row stays NOT_APPLICABLE), and a
            // row already answered on an earlier tap (re-armed after its event moved into the
            // past and back) keeps that answer and its tick
            val decision = fresh[row.key]?.let(::rsvpDecision) ?: continue
            if (dayPlanDao.recordRsvpDecision(date, row.eventId, row.instanceTime, decision) == 0) continue
            if (decision == RsvpState.PENDING) rsvp += row.key
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
        return AppliedReconciliation(plan, failed, rsvp)
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
 * The snackbar after "Set alarms": how many are armed for the day, how many were skipped —
 * as already past, as declined or cancelled, or both, with the reason named when there is
 * only one — or how many were cleared when nothing was selected any more.
 */
internal fun alarmsSetMessage(plan: AlarmReconciliation, date: LocalDate, today: LocalDate): UiMessage {
    val armed = plan.armedCount
    val past = plan.skipped.size
    val notAttending = plan.notAttending.size
    val skipped = past + notAttending
    return when {
        plan.clearsTheDay -> UiMessage.nextPlural(R.plurals.day_alarms_cleared, plan.cancel.size, plan.cancel.size)
        skipped == 0 && date == today -> UiMessage.nextPlural(R.plurals.day_alarms_set_today, armed, armed)
        skipped == 0 -> UiMessage.nextPlural(R.plurals.day_alarms_set_on_day, armed, armed, date.format(DayLabelFormatter))
        armed == 0 && notAttending == 0 -> UiMessage.nextPlural(R.plurals.day_alarms_skipped, skipped, skipped)
        armed == 0 && past == 0 -> UiMessage.nextPlural(R.plurals.day_alarms_not_attending, skipped, skipped)
        armed == 0 -> UiMessage.nextPlural(R.plurals.day_alarms_skipped_mixed, skipped, skipped)
        notAttending == 0 -> UiMessage.next(R.string.day_alarms_set_some_skipped, armed, skipped)
        past == 0 -> UiMessage.next(R.string.day_alarms_set_some_not_attending, armed, skipped)
        else -> UiMessage.next(R.string.day_alarms_set_some_skipped_mixed, armed, skipped)
    }
}

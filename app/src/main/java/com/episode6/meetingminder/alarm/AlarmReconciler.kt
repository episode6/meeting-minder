package com.episode6.meetingminder.alarm

import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.db.SelectedEventEntity
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.SelfStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Alarm time = event begin − lead time (TODO.md §4.4). */
fun alarmTimeFor(beginMillis: Long, leadTime: Duration): Long = beginMillis - leadTime.toMillis()

/**
 * What a `SetAlarms(date)` reconcile has to do to make `scheduled_alarm` match the day's
 * selection; see [reconcileAlarms]. `ScheduleAlarmsSideEffects` applies it.
 */
data class AlarmReconciliation(
    /** New rows (`alarmId = 0`, assigned on insert) to insert and arm. */
    val schedule: List<ScheduledAlarmEntity> = emptyList(),
    /**
     * Existing rows whose event moved (or, for a `SCHEDULED` row, was retitled/relocated):
     * update the row and re-arm it. A `SNOOZED` row is only re-timed for a move; retitled
     * or relocated but unmoved it is kept with its old copy (see [keep]) until the
     * automatic `MaintainAlarms` refreshes it.
     */
    val retime: List<ScheduledAlarmEntity> = emptyList(),
    /** Existing rows to disarm and mark `CANCELLED`: deselected, or moved into the past. */
    val cancel: List<ScheduledAlarmEntity> = emptyList(),
    /** Existing rows that already match (or are snoozed); nothing to do. */
    val keep: List<ScheduledAlarmEntity> = emptyList(),
    /**
     * Selections whose alarm time has already passed and so get no alarm (the snackbar
     * counts them). Each carries the refreshed title/times so the selection row can be
     * updated all the same.
     */
    val skipped: List<SelectedEventEntity> = emptyList(),
    /**
     * Selections whose event the provider now reports as cancelled, or declined by me:
     * never armed (the automatic `MaintainAlarms` cancels exactly these, so arming them
     * here would only bounce), and any row they still had is in [cancel]. Counted in the
     * snackbar; kept as selections (their chip is still drawn) so the change banner can
     * explain.
     */
    val notAttending: List<SelectedEventEntity> = emptyList(),
    /**
     * Selections whose event the provider (read for this reconcile, [reconcileAlarms]'
     * `providerRead`) no longer has at all: never armed, any row they still had is in
     * [cancel], and the selection row itself is **deleted** — there is no chip to deselect
     * it from, and left in place its stored range would still reach the share text, the
     * busy-calendar sync and the FAB's count. Counted in the snackbar with [notAttending].
     */
    val vanished: List<SelectedEventEntity> = emptyList(),
) {
    /** How many alarms are armed for the day once this is applied. */
    val armedCount: Int get() = schedule.size + retime.size + keep.size

    /**
     * Nothing on the day is selected any more once this is applied, so it only cancels
     * (and drops [vanished] selections): every selection lands in exactly one of
     * [schedule]/[retime]/[keep]/[skipped]/[notAttending]/[vanished], and the first five
     * empty means nothing selected is left. The day then goes back to "nothing picked"
     * rather than "alarms set".
     */
    val clearsTheDay: Boolean get() = armedCount == 0 && skipped.isEmpty() && notAttending.isEmpty()
}

/**
 * The pure reconcile behind "Set alarms" (TODO.md §4.4): for every selection on [date] the
 * alarm time is the event's begin (taken from [freshEvents] when the provider still has
 * the event — so a *moved* event is re-timed — and from the stored selection otherwise)
 * minus [leadTime]. Then:
 *  - the event is `STATUS_CANCELED` or declined by me: never armed, whatever its row
 *    (which is cancelled) — the same rule `MaintainAlarms` applies to armed rows, so the two
 *    reconciles can't fight over it; counted as [AlarmReconciliation.notAttending];
 *  - the provider was read ([providerRead]) and no longer has the event at all: never
 *    armed, its row cancelled, and the selection dropped ([AlarmReconciliation.vanished]).
 *    An organizer's cancellation, a deleted meeting, or a series moved to new times (which
 *    gives every occurrence a new [com.episode6.meetingminder.model.EventKey]) all read as
 *    vanished, and the chip is no longer drawn, so this tap is the only way its alarm ever
 *    gets cancelled — the automatic `MaintainAlarms` deliberately keeps a vanished key's
 *    alarm as a hedge against a sync hiccup, but the user tapping "Set alarms" on the day
 *    they are looking at is the commitment moment, and an alarm for a meeting that is not
 *    on the calendar rings for nothing. With the provider unreadable ([freshEvents] is the
 *    loaded window) an absent event is armed from the stored selection instead;
 *  - no armed row for the key: schedule one, unless the alarm time is already past
 *    (`≤ now`) — those are skipped and counted, never silently dropped;
 *  - a row with the same time, title and location: keep it;
 *  - a `SNOOZED` row (it rang and is due back at the snooze time): keep it, unless its
 *    event has moved far enough that a fresh alarm is due in the future — then re-time it
 *    to that like any moved event. Comparing a snoozed row's `fireAt` with the alarm time
 *    would otherwise read every snooze as "moved into the past" and cancel it;
 *  - a row that no longer matches: re-time it in place (same `alarmId`, so the
 *    `PendingIntent` is simply replaced), or cancel it when the new time is already past;
 *  - an armed row whose key is no longer selected: cancel it (snoozed or not).
 *
 * [scheduled] is the day's armed (`SCHEDULED`/`SNOOZED`) rows. [soundIndex] draws each new
 * row's `sound_index`; a row keeps its draw when re-timed so a moved meeting keeps its sound.
 * [providerRead] says [freshEvents] is the provider's whole day (every calendar), so an
 * event absent from it is gone; false when it is only the loaded window's fallback.
 */
fun reconcileAlarms(
    date: LocalDate,
    selected: List<SelectedEventEntity>,
    freshEvents: List<CalendarEvent>,
    scheduled: List<ScheduledAlarmEntity>,
    leadTime: Duration,
    now: Instant,
    soundIndex: () -> Int,
    providerRead: Boolean,
): AlarmReconciliation {
    val fresh = freshEvents.associateBy { it.key }
    val existing = scheduled.associateBy { it.key }
    val selectedKeys = selected.map { it.key }.toSet()
    val nowMillis = now.toEpochMilli()

    val schedule = mutableListOf<ScheduledAlarmEntity>()
    val retime = mutableListOf<ScheduledAlarmEntity>()
    val cancel = mutableListOf<ScheduledAlarmEntity>()
    val keep = mutableListOf<ScheduledAlarmEntity>()
    val skipped = mutableListOf<SelectedEventEntity>()
    val notAttending = mutableListOf<SelectedEventEntity>()
    val vanished = mutableListOf<SelectedEventEntity>()

    for (selection in selected) {
        val event = fresh[selection.key]
        val current = if (event != null) {
            selection.copy(title = event.title, beginMillis = event.begin.toEpochMilli(), endMillis = event.end.toEpochMilli())
        } else {
            selection
        }
        val row = existing[selection.key]
        if (event == null && providerRead) {
            vanished += current
            if (row != null) cancel += row
            continue
        }
        if (event != null && (event.status == EventStatus.CANCELED || event.selfStatus == SelfStatus.DECLINED)) {
            notAttending += current
            if (row != null) cancel += row
            continue
        }
        // the provider's location when it still has the event; otherwise whatever the row saved
        val location = if (event != null) event.location else row?.location
        val fireAt = alarmTimeFor(current.beginMillis, leadTime)
        val past = fireAt <= nowMillis
        when {
            row == null && past -> skipped += current
            row == null -> schedule += ScheduledAlarmEntity(
                date = date,
                eventId = current.eventId,
                instanceTime = current.instanceTime,
                fireAt = fireAt,
                title = current.title,
                beginMillis = current.beginMillis,
                endMillis = current.endMillis,
                soundIndex = soundIndex(),
                location = location,
            )
            row.state == AlarmState.SNOOZED && (past || row.beginMillis == current.beginMillis) -> keep += row
            row.state != AlarmState.SNOOZED && row.fireAt == fireAt && row.title == current.title &&
                row.beginMillis == current.beginMillis && row.endMillis == current.endMillis &&
                row.location == location -> keep += row
            past -> {
                cancel += row
                skipped += current
            }
            else -> retime += row.copy(
                fireAt = fireAt,
                title = current.title,
                beginMillis = current.beginMillis,
                endMillis = current.endMillis,
                location = location,
                // a snoozed row re-timed to its moved event is a fresh alarm again, with a
                // fresh auto-timeout budget
                state = AlarmState.SCHEDULED,
                timedOut = false,
            )
        }
    }
    cancel += scheduled.filter { it.key !in selectedKeys }

    return AlarmReconciliation(
        schedule = schedule, retime = retime, cancel = cancel, keep = keep,
        skipped = skipped, notAttending = notAttending, vanished = vanished,
    )
}

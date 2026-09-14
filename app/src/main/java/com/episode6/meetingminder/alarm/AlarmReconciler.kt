package com.episode6.meetingminder.alarm

import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.data.db.SelectedEventEntity
import com.episode6.meetingminder.model.CalendarEvent
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
    /** Existing rows whose event moved (or was retitled): update the row and re-arm it. */
    val retime: List<ScheduledAlarmEntity> = emptyList(),
    /** Existing rows to disarm and mark `CANCELLED`: deselected, or moved into the past. */
    val cancel: List<ScheduledAlarmEntity> = emptyList(),
    /** Existing rows that already match; nothing to do. */
    val keep: List<ScheduledAlarmEntity> = emptyList(),
    /**
     * Selections whose alarm time has already passed and so get no alarm (the snackbar
     * counts them). Each carries the refreshed title/times so the selection row can be
     * updated all the same.
     */
    val skipped: List<SelectedEventEntity> = emptyList(),
) {
    /** How many alarms are armed for the day once this is applied. */
    val armedCount: Int get() = schedule.size + retime.size + keep.size

    /**
     * Nothing on the day is selected any more, so applying this only cancels: every
     * selection lands in exactly one of [schedule]/[retime]/[keep]/[skipped], so all four
     * empty means an empty selection. The day then goes back to "nothing picked" rather
     * than "alarms set".
     */
    val clearsTheDay: Boolean get() = armedCount == 0 && skipped.isEmpty()
}

/**
 * The pure reconcile behind "Set alarms" (TODO.md §4.4): for every selection on [date] the
 * alarm time is the event's begin (taken from [freshEvents] when the provider still has
 * the event — so a *moved* event is re-timed — and from the stored selection otherwise)
 * minus [leadTime]. Then:
 *  - no `SCHEDULED` row for the key: schedule one, unless the alarm time is already past
 *    (`≤ now`) — those are skipped and counted, never silently dropped;
 *  - a row with the same time and title: keep it;
 *  - a row that no longer matches: re-time it in place (same `alarmId`, so the
 *    `PendingIntent` is simply replaced), or cancel it when the new time is already past;
 *  - a `SCHEDULED` row whose key is no longer selected: cancel it.
 *
 * [soundIndex] draws each new row's `sound_index`; a row keeps its draw when re-timed so a
 * moved meeting keeps its sound.
 */
fun reconcileAlarms(
    date: LocalDate,
    selected: List<SelectedEventEntity>,
    freshEvents: List<CalendarEvent>,
    scheduled: List<ScheduledAlarmEntity>,
    leadTime: Duration,
    now: Instant,
    soundIndex: () -> Int,
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

    for (selection in selected) {
        val event = fresh[selection.key]
        val current = if (event != null) {
            selection.copy(title = event.title, beginMillis = event.begin.toEpochMilli(), endMillis = event.end.toEpochMilli())
        } else {
            selection
        }
        val fireAt = alarmTimeFor(current.beginMillis, leadTime)
        val past = fireAt <= nowMillis
        val row = existing[selection.key]
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
            )
            row.fireAt == fireAt && row.title == current.title &&
                row.beginMillis == current.beginMillis && row.endMillis == current.endMillis -> keep += row
            past -> {
                cancel += row
                skipped += current
            }
            else -> retime += row.copy(
                fireAt = fireAt,
                title = current.title,
                beginMillis = current.beginMillis,
                endMillis = current.endMillis,
            )
        }
    }
    cancel += scheduled.filter { it.key !in selectedKeys }

    return AlarmReconciliation(schedule = schedule, retime = retime, cancel = cancel, keep = keep, skipped = skipped)
}

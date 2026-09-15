package com.episode6.meetingminder.store

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.DayPlan
import com.episode6.meetingminder.model.RingingAlarm
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.permissions.PermissionState
import java.time.LocalDate

/**
 * [AppState.pendingShare]'s payload: [text] is what `Navigation.kt` hands to
 * [com.episode6.meetingminder.share.shareSchedule] for [date]. [id] is chosen the same way
 * as [UiMessage.next], for the same reason: [ClearPendingShare] must clear exactly the
 * share that was launched and never a newer one dispatched in the meantime.
 */
data class PendingShare(val id: Long, val date: LocalDate, val text: String) {
    companion object {
        private var lastId = 0L

        @Synchronized
        fun next(date: LocalDate, text: String): PendingShare = PendingShare(id = ++lastId, date = date, text = text)
    }
}

/**
 * The single app-wide state behind [AppStore] (TODO.md §3.2). Every screen's ViewModel,
 * and later the alarm receivers, services and the change-detection worker, read and
 * write this one object.
 *
 * Fields arrive with the PR that first needs them: permissions (PR-4), calendars and
 * `eventsByDay` (PR-6), `dayPlans` (PR-7), `pendingShare` (PR-9), `ringing` (PR-10),
 * `scheduleChanges` (PR-11).
 */
data class AppState(
    /**
     * Today: the day the day pager is anchored to. Set at launch and kept current while the
     * UI is visible by [SetAnchorDate] (midnight rollover, a clock or timezone change).
     */
    val anchorDate: LocalDate,
    /** The day the user is looking at: the pager's settled page. */
    val settledDate: LocalDate = anchorDate,
    /**
     * Which OS permission grants we currently hold. [com.episode6.meetingminder.di.AppGraph]
     * computes the initial value synchronously (so launch routing never flashes the wrong
     * screen); [PermissionsMaybeChanged] refreshes it afterwards (`ON_RESUME`, right after a
     * request returns, and after "Open settings" is used).
     */
    val permissions: PermissionState = PermissionState(),
    /** Every calendar row on every account, hidden and non-syncing ones included. */
    val calendars: List<CalendarInfo> = emptyList(),
    /**
     * Events for [loadedWindow] (see [SetDayEvents]). A day that is absent hasn't loaded
     * yet; a loaded day with nothing on it maps to an empty event list.
     */
    val eventsByDay: Map<LocalDate, DayEvents> = emptyMap(),
    /**
     * Every day that has ever had a selection, alarms set or a share, read from Room's
     * `day_plan` + `selected_event` tables (`ObserveDayPlansSideEffects`). A date absent
     * here has no selections, exactly like `Map<>::get` returning null on any other date.
     */
    val dayPlans: Map<LocalDate, DayPlan> = emptyMap(),
    /** One-shot snackbar text; ViewModels expose it as a one-shot `Flow` and clear it by id once shown. */
    val transientMessage: UiMessage? = null,
    /**
     * A share whose text is ready and the chooser hasn't launched yet ([ShareDay],
     * TODO.md §4.2). Like [transientMessage], ViewModels expose this as a one-shot `Flow`
     * and `Navigation.kt` clears it by id once `ShareCompat` has opened the chooser —
     * launching an Activity belongs in the UI layer, not a side effect.
     */
    val pendingShare: PendingShare? = null,
    /**
     * A share is under way: from the tap ([ShareStarted], dispatched together with
     * [ShareDay] by [startShare]) until the chooser it opened has closed ([ShareFinished],
     * from `Navigation.kt`'s activity result) or the share failed before it could open.
     * No second share starts while this is set, which is what keeps a fast double tap on
     * "Share schedule" from opening two choosers: the second tap lands long after
     * [pendingShare] was taken and cleared, so a guard there would not see it.
     */
    val shareInFlight: Boolean = false,
    /**
     * The alarm ringing right now, or null when nothing is (TODO.md §4.4). Published by
     * `AlarmRingingService` (the owner of the ringing: it holds the queue when alarms fire
     * back to back) through [SetRinging]; `AlarmActivity` renders it and closes when it
     * goes back to null.
     */
    val ringing: RingingAlarm? = null,
    /**
     * What has changed on every shared day since it was shared (TODO.md §4.3), each change
     * tagged with its day: `change_snapshot.changes_json`, recorded by `monitor/ChangeMonitor`
     * and streamed in by `ChangeDetectionSideEffects`. Drives the day view's "changed since
     * you shared" banner; a re-share empties that day's list.
     */
    val scheduleChanges: List<ScheduleChange> = emptyList(),
)

/** The dates [AppState.eventsByDay] keeps: the settled page and the page either side of it. */
val AppState.loadedWindow: ClosedRange<LocalDate>
    get() = settledDate.minusDays(1)..settledDate.plusDays(1)

/**
 * A snackbar message. [id] is chosen by whoever dispatches [ShowMessage] (see
 * [UiMessage.next]) so [ClearMessage] can clear exactly the message that was shown and
 * never a newer one that replaced it in the meantime. `ui/util/UiMessageText.kt` turns
 * it into text: [text] is a plurals resource chosen by [quantity] when that is set, and a
 * plain string resource otherwise.
 */
data class UiMessage(
    val id: Long,
    val text: Int,
    val formatArgs: List<Any> = emptyList(),
    val quantity: Int? = null,
) {
    companion object {
        private var lastId = 0L

        /** A message with a fresh, process-unique id. */
        @Synchronized
        fun next(@StringRes text: Int, vararg formatArgs: Any): UiMessage =
            UiMessage(id = ++lastId, text = text, formatArgs = formatArgs.toList())

        /** Like [next], for a plurals resource picked by [quantity]. */
        @Synchronized
        fun nextPlural(@PluralsRes text: Int, quantity: Int, vararg formatArgs: Any): UiMessage =
            UiMessage(id = ++lastId, text = text, formatArgs = formatArgs.toList(), quantity = quantity)
    }
}

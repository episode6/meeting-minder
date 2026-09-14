package com.episode6.meetingminder.ui.day

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.episode6.meetingminder.R
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.DayPlan
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.SelectedEvent
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.AppStore
import com.episode6.meetingminder.store.ClearMessage
import com.episode6.meetingminder.store.LoadDay
import com.episode6.meetingminder.store.SetAlarms
import com.episode6.meetingminder.store.SetSettledDate
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.ToggleEvent
import com.episode6.meetingminder.store.UiMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private const val STOP_TIMEOUT_MILLIS = 5_000L
private const val MILLIS_PER_MINUTE = 60_000L

/**
 * The thin store adapter for [DayScreen] — the pattern every screen's ViewModel copies:
 * derive an immutable UI state from the store (here combined with a once-a-minute clock
 * tick for the now-line), expose `on…` callbacks that dispatch, and turn the store's
 * `transientMessage` into a one-shot flow.
 */
@Inject
@ViewModelKey(DayViewModel::class)
@ContributesIntoMap(AppScope::class)
class DayViewModel(private val store: AppStore, private val clock: Clock) : ViewModel() {

    private val minuteTicks: Flow<LocalDateTime> = flow {
        while (true) {
            val now = LocalDateTime.now(clock)
            emit(now)
            delay(MILLIS_PER_MINUTE - now.toLocalTime().toNanoOfDay() / 1_000_000 % MILLIS_PER_MINUTE)
        }
    }

    val state: StateFlow<DayUiState> = combine(store, minuteTicks) { state, now -> state.toDayUiState(now, clock.zone) }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            store.state.toDayUiState(LocalDateTime.now(clock), clock.zone),
        )

    /** Each pending snackbar message once; call [onMessageShown] as it is displayed. */
    val messages: Flow<UiMessage> = store
        .map { it.transientMessage }
        .filterNotNull()
        .distinctUntilChanged { old, new -> old.id == new.id }

    /** The pager came to rest on [date] (at launch, after a swipe, or after "Today" scrolled it back). */
    fun onPageSettled(date: LocalDate) {
        store.dispatch(SetSettledDate(date))
        store.dispatch(LoadDay(date))
    }

    /** A chip on [date]'s page was tapped: flip its selection. */
    fun onEventToggle(date: LocalDate, event: TimelineEvent) {
        store.dispatch(ToggleEvent(date, event.key))
    }

    /**
     * The FAB was tapped: "Set alarms (N)" (or "Clear alarms", the same state with nothing
     * selected) reconciles the settled day's alarms against its selection ([SetAlarms]);
     * "Share schedule" is a placeholder snackbar until PR-9.
     */
    fun onFabClick() {
        val state = store.state
        when (state.dayPlans[state.settledDate].toFabState()) {
            is FabState.SetAlarms -> store.dispatch(SetAlarms(state.settledDate))
            FabState.Share -> store.dispatch(ShowMessage(UiMessage.next(R.string.day_fab_share_coming_soon)))
            FabState.Hidden -> Unit
        }
    }

    /**
     * The provider event behind a chip, for "open in calendar". Null if it has left the
     * loaded window since the chip was drawn.
     */
    fun calendarEventFor(key: EventKey): CalendarEvent? =
        store.state.eventsByDay.values.firstNotNullOfOrNull { day -> day.events.firstOrNull { it.key == key } }

    /** No app on the device can open the long-pressed event. */
    fun onOpenInCalendarFailed() {
        store.dispatch(ShowMessage(UiMessage.next(R.string.open_in_calendar_failed)))
    }

    /** No app on the device can open the "Check for updates" page. */
    fun onCheckForUpdatesFailed() {
        store.dispatch(ShowMessage(UiMessage.next(R.string.check_for_updates_no_browser)))
    }

    fun onMessageShown(message: UiMessage) {
        store.dispatch(ClearMessage(message.id))
    }
}

/** [DayUiState] for the store's loaded window at wall-clock time [now] in [zone]. */
internal fun AppState.toDayUiState(now: LocalDateTime, zone: ZoneId) = DayUiState(
    anchorDate = anchorDate,
    date = settledDate,
    isToday = settledDate == anchorDate,
    meetingCount = eventsByDay[settledDate]?.events?.count { it.isMeeting },
    fabState = dayPlans[settledDate].toFabState(),
    armedCount = dayPlans[settledDate]?.selected?.values?.count { it.alarmId != null } ?: 0,
    days = eventsByDay.mapValues { (date, day) ->
        day.toTimelineState(zone, now = now.toLocalTime().takeIf { now.toLocalDate() == date }, selected = dayPlans[date]?.selected.orEmpty())
    },
    initialFirstVisibleHour = eventsByDay[anchorDate]?.let { initialFirstVisibleHour(it.date, it.events, zone) },
)

/**
 * The FAB's state (TODO.md §3.5): hidden with nothing picked, "Set alarms (N)" with a
 * selection and no alarms yet, "Share schedule" once alarms are set. [DayPlan.alarmsSetAt]
 * is cleared by any later change of selection (`DayPlanDao.toggleSelectedEvent`), which is
 * what puts the day back into "Set alarms" until the next reconcile (§2 interaction rules).
 * That reconcile is also the only thing that cancels a deselected event's alarm, so while
 * [DayPlan.armedKeys] is non-empty the FAB stays even with nothing selected — as
 * `SetAlarms(0)`, which [DayScreen] labels "Clear alarms".
 */
internal fun DayPlan?.toFabState(): FabState {
    val selected = this?.selected.orEmpty()
    return when {
        this?.alarmsSetAt != null -> FabState.Share
        selected.isEmpty() && this?.armedKeys.orEmpty().isEmpty() -> FabState.Hidden
        else -> FabState.SetAlarms(selected.size)
    }
}

/**
 * Splits one day's provider events into the all-day row and the timeline. Timed events that
 * don't actually overlap the day are dropped (see [DayTimelineState.timedEvents]); an event
 * ending exactly at midnight belongs only to the day it started.
 */
internal fun DayEvents.toTimelineState(
    zone: ZoneId,
    now: java.time.LocalTime?,
    selected: Map<EventKey, SelectedEvent> = emptyMap(),
): DayTimelineState {
    val (allDay, timed) = events.partition { it.allDay }
    val dayStart = date.atStartOfDay()
    val dayEnd = date.plusDays(1).atStartOfDay()
    return DayTimelineState(
        date = date,
        allDayEvents = allDay.map { it.toTimelineEvent(zone) },
        timedEvents = timed
            .map { it.toTimelineEvent(zone, selection = selected[it.key]) }
            .filter { it.begin < dayEnd && (it.end > dayStart || (it.end == it.begin && it.begin >= dayStart)) },
        now = now,
    )
}

/** [CalendarEvent.toTimelineEvent] driven by the day's [SelectedEvent] row, if any. */
private fun CalendarEvent.toTimelineEvent(zone: ZoneId, selection: SelectedEvent?): TimelineEvent = toTimelineEvent(
    zone = zone,
    selected = selection != null,
    alarmAt = selection?.alarmAt?.let { LocalDateTime.ofInstant(it, zone).toLocalTime() },
    rsvp = selection?.rsvpState?.toChipRsvp() ?: ChipRsvp.None,
)

package com.episode6.meetingminder.ui.day

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.calendar.ShareMode
import com.episode6.meetingminder.data.calendar.shareMode
import com.episode6.meetingminder.data.settings.BusySync
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.DayEvents
import com.episode6.meetingminder.model.DayPlan
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.EventResponse
import com.episode6.meetingminder.model.SelectedEvent
import com.episode6.meetingminder.monitor.toLine
import com.episode6.meetingminder.share.selectedBusyRanges
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.AppStore
import com.episode6.meetingminder.store.ClearMessage
import com.episode6.meetingminder.store.ClearPendingShare
import com.episode6.meetingminder.store.LoadDay
import com.episode6.meetingminder.store.MarkNotShared
import com.episode6.meetingminder.store.PendingShare
import com.episode6.meetingminder.store.RefreshCalendars
import com.episode6.meetingminder.store.RespondToEvent
import com.episode6.meetingminder.store.SetAlarms
import com.episode6.meetingminder.store.SetSettledDate
import com.episode6.meetingminder.store.ShareFinished
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.ToggleEvent
import com.episode6.meetingminder.store.UiMessage
import com.episode6.meetingminder.store.startShare
import com.episode6.redux.mapStore
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
import kotlinx.coroutines.flow.onStart
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
class DayViewModel(private val store: AppStore, private val clock: Clock, private val settings: SettingsRepository) : ViewModel() {

    private val minuteTicks: Flow<LocalDateTime> = flow {
        while (true) {
            val now = LocalDateTime.now(clock)
            emit(now)
            delay(MILLIS_PER_MINUTE - now.toLocalTime().toNanoOfDay() / 1_000_000 % MILLIS_PER_MINUTE)
        }
    }

    /**
     * What a share does right now ([ShareMode], TODO.md §4.7): drives the FAB/menu/banner/
     * subtitle wording. DataStore's first emission is a disk read, so the settings side
     * starts with the defaults (sync off): the `combine` below then never holds a store
     * update back behind that read, and the labels switch to "Sync …" once the preference is in.
     */
    private val shareMode: Flow<ShareMode> = combine(
        settings.settings.map { it.busySync }.onStart { emit(BusySync()) },
        store.mapStore { it.calendars },
    ) { busySync, calendars -> shareMode(busySync, calendars) }

    val state: StateFlow<DayUiState> = combine(store, minuteTicks, shareMode) { state, now, mode -> state.toDayUiState(now, clock.zone, mode) }
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

    /** Each share whose text is ready to launch, once; call [onShareLaunched] as `Navigation.kt` opens the chooser. */
    val pendingShare: Flow<PendingShare> = store
        .map { it.pendingShare }
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

    /** "Respond Yes / No / Maybe" from a chip's long-press menu on [date]'s page (TODO.md §4.6). */
    fun onEventRespond(date: LocalDate, event: TimelineEvent, response: EventResponse) {
        store.dispatch(RespondToEvent(date, event.key, response))
    }

    /** The app bar's "Refresh": a calendar sync request plus a reload of the shown days. */
    fun onRefreshClick() {
        store.dispatch(RefreshCalendars)
    }

    /**
     * The FAB was tapped: "Set alarms (N)" (or "Clear alarms", the same state with nothing
     * selected) reconciles the settled day's alarms against its selection ([SetAlarms]);
     * "Share schedule" (or "Sync busy times") shares the day's busy ranges ([startShare],
     * TODO.md §4.2/§4.7).
     */
    fun onFabClick() {
        // the mode the screen was drawn with, so the tap reads the button that was on it
        val shareMode = state.value.shareMode
        val state = store.state
        when (state.dayPlans[state.settledDate].toFabState(shareMode)) {
            is FabState.SetAlarms -> store.dispatch(SetAlarms(state.settledDate))
            is FabState.Share -> store.startShare(state.settledDate)
            FabState.Hidden, FabState.Synced -> Unit
        }
    }

    /** Overflow → "Share again" (and the banner's "Re-share") for the settled day: re-sends the current busy ranges. */
    fun onShareAgainClick() {
        store.startShare(store.state.settledDate)
    }

    /** The share sheet `Navigation.kt` opened has closed (or couldn't open): the next share may start. */
    fun onShareSheetClosed() {
        store.dispatch(ShareFinished)
    }

    /** Overflow → "Mark as not shared" for the settled day. */
    fun onMarkNotSharedClick() {
        store.dispatch(MarkNotShared(store.state.settledDate))
    }

    /** `Navigation.kt` has opened the share sheet for [share]: clear it so it isn't re-launched. */
    fun onShareLaunched(share: PendingShare) {
        store.dispatch(ClearPendingShare(share.id))
    }

    /**
     * The provider event behind a chip, for "open in calendar". Null if it has left the
     * loaded window since the chip was drawn.
     */
    fun calendarEventFor(key: EventKey): CalendarEvent? =
        store.state.eventsByDay.values.firstNotNullOfOrNull { day -> day.events.firstOrNull { it.key == key } }

    /** No app on the device can open the event ("Open in calendar" from a chip's long-press menu). */
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

/** [DayUiState] for the store's loaded window at wall-clock time [now] in [zone], with what a share does right now, [shareMode] (TODO.md §4.7). */
internal fun AppState.toDayUiState(now: LocalDateTime, zone: ZoneId, shareMode: ShareMode = ShareMode.TEXT) = DayUiState(
    anchorDate = anchorDate,
    date = settledDate,
    isToday = settledDate == anchorDate,
    meetingCount = eventsByDay[settledDate]?.events?.count { it.isMeeting },
    fabState = dayPlans[settledDate].toFabState(shareMode),
    armedCount = dayPlans[settledDate]?.selected?.values?.count { it.alarmId != null } ?: 0,
    sharedAt = dayPlans[settledDate]?.sharedAt?.let { LocalDateTime.ofInstant(it, zone) },
    days = eventsByDay.mapValues { (date, day) ->
        day.toTimelineState(zone, now = now.toLocalTime().takeIf { now.toLocalDate() == date }, selected = dayPlans[date]?.selected.orEmpty())
    },
    initialFirstVisibleHour = eventsByDay[anchorDate]?.let { initialFirstVisibleHour(it.date, it.events, zone) },
    changeBanner = changeBannerFor(settledDate, zone),
    shareMode = shareMode,
)

/**
 * The "changed since you shared" banner for [date] (TODO.md §2/§4.3): only for a day that
 * has been shared, and only while something differs from that share — the changes the
 * last check recorded ([AppState.scheduleChanges]), or, once the day's events have loaded,
 * a selection whose busy ranges no longer match [DayPlan.sharedSnapshot] (§2: changing the
 * selection after sharing shows the banner too). A re-share resets both. Never for a day
 * before today ([AppState.anchorDate]): monitoring has ended there, and re-sharing it
 * would tell nobody anything useful.
 */
internal fun AppState.changeBannerFor(date: LocalDate, zone: ZoneId): ScheduleChangeBannerState? {
    if (date < anchorDate) return null
    val plan = dayPlans[date]?.takeIf { it.sharedAt != null } ?: return null
    val changes = scheduleChanges.filter { it.date == date }
    val events = eventsByDay[date]?.events
    val selectionChanged = events != null && plan.sharedSnapshot != null &&
        selectedBusyRanges(plan.selected.mapValues { (_, selection) -> BusyRange(selection.begin, selection.end) }, events) != plan.sharedSnapshot
    return if (changes.isEmpty() && !selectionChanged) null else ScheduleChangeBannerState(changes.map { it.toLine(zone) })
}

/**
 * The FAB's state (TODO.md §3.5): hidden with nothing picked, "Set alarms (N)" with a
 * selection and no alarms yet, "Share schedule" (in [shareMode]'s wording) once alarms are set. [DayPlan.alarmsSetAt]
 * is cleared by any later change of selection (`DayPlanDao.toggleSelectedEvent`), which is
 * what puts the day back into "Set alarms" until the next reconcile (§2 interaction rules).
 * That reconcile is also the only thing that cancels a deselected event's alarm, so while
 * [DayPlan.armedKeys] is non-empty the FAB stays even with nothing selected — as
 * `SetAlarms(0)`, which [DayScreen] labels "Clear alarms". A sync-only day that has been
 * synced shows no button at all ([FabState.Synced]); its banner carries the re-sync.
 */
internal fun DayPlan?.toFabState(shareMode: ShareMode = ShareMode.TEXT): FabState {
    val selected = this?.selected.orEmpty()
    return when {
        this?.alarmsSetAt != null -> if (shareMode == ShareMode.SYNC_ONLY && sharedAt != null) FabState.Synced else FabState.Share(shareMode)
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

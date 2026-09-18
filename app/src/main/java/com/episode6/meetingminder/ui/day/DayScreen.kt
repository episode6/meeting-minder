package com.episode6.meetingminder.ui.day

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.calendar.ShareMode
import com.episode6.meetingminder.model.EventResponse
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What [DayScreen] renders: the app bar for the settled [date], the FAB, and one
 * [DayTimelineState] per loaded day of the pager.
 */
@Immutable
data class DayUiState(
    /** The pager's anchor page date (today at launch). */
    val anchorDate: LocalDate,
    /** The settled page's date: what the app bar and FAB show. */
    val date: LocalDate = anchorDate,
    val isToday: Boolean = date == anchorDate,
    /** [com.episode6.meetingminder.model.CalendarEvent.isMeeting] count on [date]; null until it has loaded. */
    val meetingCount: Int? = null,
    /** The FAB for [date]: hidden, "Set alarms (N)", or "Share schedule". */
    val fabState: FabState = FabState.Hidden,
    /** How many of [date]'s selected events have an alarm armed; the subtitle once alarms are set. */
    val armedCount: Int = 0,
    /**
     * When [date] was last shared, in the device zone; null if it never has been. Drives
     * the "shared 8:12 AM" subtitle ("shared Sep 13, 9:00 PM" when the share happened on
     * another day — tomorrow's schedule sent tonight, §2) and whether the overflow shows
     * "Share again"/"Mark as not shared" (TODO.md §4.2).
     */
    val sharedAt: LocalDateTime? = null,
    /** Every loaded day's timeline; days not in here haven't loaded yet. */
    val days: Map<LocalDate, DayTimelineState> = emptyMap(),
    /** Where the timeline should first open, once today's events have loaded; see [initialFirstVisibleHour]. */
    val initialFirstVisibleHour: Float? = null,
    /** The "changed since you shared" banner for [date] (TODO.md §4.3); null when [date] isn't shared or nothing changed. */
    val changeBanner: ScheduleChangeBannerState? = null,
    /**
     * What a share does right now (TODO.md §4.7): the overflow's "Share again" reads "Sync &
     * share again" while it syncs too, and in [ShareMode.SYNC_ONLY] the overflow, banner and
     * subtitle talk about syncing rather than sharing ("Sync again", "Remove busy blocks",
     * "Re-sync", "synced 8:12 AM"). The FAB itself reads [FabState.Share.mode].
     */
    val shareMode: ShareMode = ShareMode.TEXT,
) {
    fun timelineFor(date: LocalDate): DayTimelineState = days[date] ?: DayTimelineState(date)
}

private val TitleFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")

/** The day part of "shared Sep 13, 9:00 PM", for a day shared on another day. */
private val SharedDateFormatter = DateTimeFormatter.ofPattern("MMM d")

/**
 * The day view (render 2): date + subtitle app bar with the Refresh and Today actions and
 * the overflow menu over the [DayPager]. Reports each settled page through [onPageSettled]
 * (a fling reports only where it stops); "Today" scrolls the pager back to its anchor page,
 * which then settles like any swipe; "Refresh" ([onRefreshClick]) asks for a calendar sync
 * and reloads the shown days. A chip's long-press menu reports "Open in calendar" through
 * [onEventOpenClick] and "Respond Yes / No / Maybe" through [onEventRespond] with the
 * page's date, like a tap does. All pages share [scrollState], which jumps once to
 * [DayUiState.initialFirstVisibleHour] when it arrives, unless the user has already scrolled.
 * A shared day that has changed since shows the [ScheduleChangeBanner] above the pager, whose
 * "Re-share" is [onShareAgainClick]. [jumpToDate] (a notification's deep link) scrolls the
 * pager straight to that day, which then settles like any other page; [onJumpHandled]
 * reports it done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    state: DayUiState,
    onPageSettled: (LocalDate) -> Unit,
    onRefreshClick: () -> Unit,
    onPermissionsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onCheckForUpdatesClick: () -> Unit,
    onShareAgainClick: () -> Unit,
    onMarkNotSharedClick: () -> Unit,
    onEventClick: (LocalDate, TimelineEvent) -> Unit,
    onEventOpenClick: (TimelineEvent) -> Unit,
    onEventRespond: (LocalDate, TimelineEvent, EventResponse) -> Unit,
    onFabClick: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    pagerState: PagerState = rememberDayPagerState(state.anchorDate, state.date),
    scrollState: ScrollState = rememberTimelineScrollState(),
    jumpToDate: LocalDate? = null,
    onJumpHandled: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val currentOnPageSettled by rememberUpdatedState(onPageSettled)
    LaunchedEffect(pagerState, state.anchorDate) {
        // The anchor moves at midnight (SetAnchorDate) while the page index doesn't: keep
        // showing the date the user was on rather than sliding every page a day forward.
        // The pager's epoch-day keys usually carry the position over already; this covers
        // the case where they didn't.
        val shownPage = dateToPage(state.date, state.anchorDate)
        if (pagerState.settledPage != shownPage && !pagerState.isScrollInProgress) pagerState.scrollToPage(shownPage)
        snapshotFlow { pagerState.settledPage }.collect { currentOnPageSettled(pageToDate(it, state.anchorDate)) }
    }
    val currentOnJumpHandled by rememberUpdatedState(onJumpHandled)
    LaunchedEffect(pagerState, jumpToDate) {
        if (jumpToDate == null) return@LaunchedEffect
        pagerState.scrollToPage(dateToPage(jumpToDate, state.anchorDate))
        currentOnJumpHandled()
    }
    InitialScroll(state.initialFirstVisibleHour, scrollState)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                        Text(
                            state.date.format(TitleFormatter),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Subtitle(state.date, state.meetingCount, state.fabState, state.armedCount, state.sharedAt)
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshClick) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.day_refresh))
                    }
                    IconButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(DayViewDefaults.PagerAnchorPage) } },
                        enabled = !state.isToday,
                    ) {
                        Icon(Icons.Outlined.Today, contentDescription = stringResource(R.string.day_today))
                    }
                    OverflowMenu(
                        hasBeenShared = state.sharedAt != null,
                        shareMode = state.shareMode,
                        onPermissionsClick = onPermissionsClick,
                        onSettingsClick = onSettingsClick,
                        onLicensesClick = onLicensesClick,
                        onCheckForUpdatesClick = onCheckForUpdatesClick,
                        onShareAgainClick = onShareAgainClick,
                        onMarkNotSharedClick = onMarkNotSharedClick,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = { DayFab(state.fabState, onFabClick) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ChangeBanner(state.changeBanner, state.shareMode, onShareAgainClick)
            DayPager(
                state = state,
                pagerState = pagerState,
                scrollState = scrollState,
                onEventClick = onEventClick,
                onEventOpenClick = onEventOpenClick,
                onEventRespond = onEventRespond,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

/** [ScheduleChangeBanner] sliding in and out with [state]; the last non-null state keeps rendering while it animates away. */
@Composable
private fun ChangeBanner(state: ScheduleChangeBannerState?, shareMode: ShareMode, onReshareClick: () -> Unit) {
    var lastState by remember { mutableStateOf(state) }
    if (state != null) lastState = state
    AnimatedVisibility(visible = state != null) {
        lastState?.let { ScheduleChangeBanner(it, onReshareClick, shareMode = shareMode) }
    }
}

/**
 * The app bar's state summary line: [subtitleText] in `onSurfaceVariant`, except once
 * alarms are set, when it becomes an orange app-bar accent (TODO.md §3.7) with a bell in
 * front, as in render 3.
 */
@Composable
private fun Subtitle(date: LocalDate, meetingCount: Int?, fabState: FabState, armedCount: Int, sharedAt: LocalDateTime?) {
    val armed = fabState is FabState.Share || fabState == FabState.Synced
    val color = if (armed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DayViewDefaults.SubtitleIconSpacing)) {
        if (armed) {
            Icon(
                Icons.Outlined.Notifications,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(DayViewDefaults.SubtitleIconSize),
            )
        }
        Text(
            subtitleText(date, meetingCount, fabState, armedCount, sharedAt),
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * "3 meetings" / "1 meeting" / "No meetings", with " · N selected" appended while the FAB
 * reads "Set alarms" (render 2); "3 alarms set · not shared yet" once alarms are set but
 * not yet shared, or "shared 8:12 AM" once [sharedAt] is set (render 3/render 4) — with
 * the date in front ("shared Sep 13, 9:00 PM") when the share happened on a day other
 * than [date], so a bare time can't read as that day's evening; blank (but still a line
 * tall) while the day loads.
 */
@Composable
private fun subtitleText(date: LocalDate, meetingCount: Int?, fabState: FabState, armedCount: Int, sharedAt: LocalDateTime?): String {
    if (meetingCount == null) return ""
    val meetings = when (meetingCount) {
        0 -> stringResource(R.string.day_subtitle_no_meetings)
        else -> pluralStringResource(R.plurals.day_subtitle_meetings, meetingCount, meetingCount)
    }
    val syncOnly = fabState == FabState.Synced || (fabState is FabState.Share && fabState.mode == ShareMode.SYNC_ONLY)
    return when (fabState) {
        is FabState.SetAlarms -> stringResource(R.string.day_subtitle_with_selected_count, meetings, fabState.count)
        is FabState.Share, FabState.Synced -> if (sharedAt != null) {
            val time = rememberTimelineTimeFormat().timeWithPeriod(sharedAt.toLocalTime())
            if (sharedAt.toLocalDate() == date) {
                stringResource(if (syncOnly) R.string.day_subtitle_synced_at else R.string.day_subtitle_shared_at, time)
            } else {
                stringResource(
                    if (syncOnly) R.string.day_subtitle_synced_on else R.string.day_subtitle_shared_on,
                    sharedAt.toLocalDate().format(SharedDateFormatter),
                    time,
                )
            }
        } else {
            stringResource(
                if (syncOnly) R.string.day_subtitle_not_synced_yet else R.string.day_subtitle_not_shared_yet,
                pluralStringResource(R.plurals.day_subtitle_alarms_set, armedCount, armedCount),
            )
        }
        FabState.Hidden -> meetings
    }
}

/**
 * [ExtendedFloatingActionButton] for [state], animated between the icon/label of
 * [FabState.SetAlarms] ("Set alarms (N)", or "Clear alarms" when N is 0 — see
 * [FabState.SetAlarms]) and the primary-filled [FabState.Share] (render 3), hidden entirely
 * while [FabState.Hidden].
 */
@Composable
private fun DayFab(state: FabState, onClick: () -> Unit) {
    // AnimatedVisibility owns hiding the FAB entirely; AnimatedContent is only ever fed a
    // non-Hidden target (the last one seen) so its own exit transition never has to render
    // FabState.Hidden's empty content while AnimatedVisibility is still animating it out.
    var lastVisibleState by remember { mutableStateOf<FabState>(FabState.SetAlarms(0)) }
    val visible = state != FabState.Hidden && state != FabState.Synced
    if (visible) lastVisibleState = state

    AnimatedVisibility(visible = visible) {
        // contentKey groups by class so a SetAlarms(1) -> SetAlarms(2) count change updates
        // the label in place instead of crossfading the whole FAB; only a SetAlarms <-> Share
        // transition (a different key) gets the crossfade.
        AnimatedContent(targetState = lastVisibleState, contentKey = { it::class }, label = "dayFabState") { target ->
            when (target) {
                FabState.Hidden, FabState.Synced -> Unit
                is FabState.SetAlarms -> if (target.count == 0) {
                    ExtendedFloatingActionButton(
                        onClick = onClick,
                        icon = { Icon(Icons.Outlined.AlarmOff, contentDescription = null) },
                        text = { Text(stringResource(R.string.day_fab_clear_alarms)) },
                    )
                } else {
                    ExtendedFloatingActionButton(
                        onClick = onClick,
                        icon = { Icon(Icons.Outlined.Alarm, contentDescription = null) },
                        text = { Text(stringResource(R.string.day_fab_set_alarms, target.count)) },
                    )
                }
                is FabState.Share -> ExtendedFloatingActionButton(
                    onClick = onClick,
                    // sync-only writes to a calendar and opens no share sheet, so no share glyph
                    icon = {
                        Icon(
                            if (target.mode == ShareMode.SYNC_ONLY) Icons.Outlined.Sync else Icons.Outlined.Share,
                            contentDescription = null,
                        )
                    },
                    text = {
                        Text(
                            stringResource(
                                when (target.mode) {
                                    ShareMode.TEXT -> R.string.day_fab_share
                                    ShareMode.SYNC_AND_TEXT -> R.string.day_fab_sync_share
                                    ShareMode.SYNC_ONLY -> R.string.day_fab_sync
                                },
                            ),
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * Scrolls [scrollState] to [firstVisibleHour] the first time it is known, once per screen
 * (saved across recreation), and only if the timeline is still at its default hour: a
 * user who scrolled before today's events loaded keeps their position.
 */
@Composable
private fun InitialScroll(firstVisibleHour: Float?, scrollState: ScrollState) {
    var applied by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    LaunchedEffect(firstVisibleHour) {
        if (firstVisibleHour == null || applied) return@LaunchedEffect
        applied = true
        val defaultOffset = with(density) { (DayViewDefaults.HourHeight * DayViewDefaults.DefaultFirstVisibleHour.toFloat()).roundToPx() }
        if (scrollState.value == defaultOffset) {
            scrollState.scrollTo(with(density) { (DayViewDefaults.HourHeight * firstVisibleHour).roundToPx() })
        }
    }
}

/**
 * "Share again" and "Mark as not shared" only show once [hasBeenShared] (render 3/4;
 * TODO.md §4.2's interaction rule: changing the selection after sharing keeps the share
 * affordance available from here even once the FAB has reverted to "Set alarms").
 */
@Composable
private fun OverflowMenu(
    hasBeenShared: Boolean,
    shareMode: ShareMode,
    onPermissionsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onCheckForUpdatesClick: () -> Unit,
    onShareAgainClick: () -> Unit,
    onMarkNotSharedClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        buildList {
            if (hasBeenShared) {
                val (again, markNot) = when (shareMode) {
                    ShareMode.TEXT -> R.string.menu_share_again to R.string.menu_mark_not_shared
                    ShareMode.SYNC_AND_TEXT -> R.string.menu_sync_share_again to R.string.menu_mark_not_shared
                    ShareMode.SYNC_ONLY -> R.string.menu_sync_again to R.string.menu_remove_busy_blocks
                }
                add(again to onShareAgainClick)
                add(markNot to onMarkNotSharedClick)
            }
            add(R.string.menu_permissions to onPermissionsClick)
            add(R.string.menu_settings to onSettingsClick)
            add(R.string.menu_check_for_updates to onCheckForUpdatesClick)
            add(R.string.menu_licenses to onLicensesClick)
        }.forEach { (label, onClick) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                onClick = {
                    expanded = false
                    onClick()
                },
            )
        }
    }
}

@Composable
private fun DayScreenPreviewFrame(state: DayUiState) {
    MeetingMinderTheme {
        DayScreen(
            state = state,
            onPageSettled = {},
            onRefreshClick = {},
            onPermissionsClick = {},
            onSettingsClick = {},
            onLicensesClick = {},
            onCheckForUpdatesClick = {},
            onShareAgainClick = {},
            onMarkNotSharedClick = {},
            onEventClick = { _, _ -> },
            onEventOpenClick = {},
            onEventRespond = { _, _, _ -> },
            onFabClick = {},
            scrollState = rememberTimelineScrollState(PreviewEvents.FIRST_VISIBLE_HOUR),
        )
    }
}

/** A loaded day with nothing on it. */
@Preview(showBackground = true)
@Composable
internal fun DayScreenEmptyPreview() {
    DayScreenPreviewFrame(
        DayUiState(anchorDate = PreviewDate, meetingCount = 0, days = mapOf(PreviewDate to DayTimelineState(PreviewDate))),
    )
}

/** Render 2's day loaded into the pager: "3 meetings" (the dentist and school pickup are solo blocks). */
@Preview(showBackground = true)
@Composable
internal fun DayScreenBusyPreview() {
    DayScreenPreviewFrame(
        DayUiState(anchorDate = PreviewDate, meetingCount = 3, days = mapOf(PreviewDate to PreviewEvents.busyDay)),
    )
}

/** Render 2 with two events selected: "3 meetings · 2 selected" and the "Set alarms (2)" FAB. */
@Preview(showBackground = true)
@Composable
internal fun DayScreenSelectingPreview() {
    DayScreenPreviewFrame(
        DayUiState(
            anchorDate = PreviewDate,
            meetingCount = 3,
            fabState = FabState.SetAlarms(2),
            days = mapOf(PreviewDate to PreviewEvents.selectingDay),
        ),
    )
}

/** Every selection removed after alarms were set: "3 meetings · 0 selected", chips back to outlined, and the "Clear alarms" FAB. */
@Preview(showBackground = true)
@Composable
internal fun DayScreenClearAlarmsPreview() {
    DayScreenPreviewFrame(
        DayUiState(
            anchorDate = PreviewDate,
            meetingCount = 3,
            fabState = FabState.SetAlarms(0),
            days = mapOf(PreviewDate to PreviewEvents.busyDay),
        ),
    )
}

/** Render 3: alarms set for three selected events, the orange bell subtitle "3 alarms set · not shared yet" and the primary-filled "Share schedule" FAB. */
@Preview(showBackground = true)
@Composable
internal fun DayScreenAlarmsSetPreview() {
    DayScreenPreviewFrame(
        DayUiState(
            anchorDate = PreviewDate,
            meetingCount = 3,
            fabState = FabState.Share(),
            armedCount = 3,
            days = mapOf(PreviewDate to PreviewEvents.alarmsSetDay),
        ),
    )
}

/** After "Share schedule": subtitle reads "shared 8:12 AM" and the overflow would offer "Share again"/"Mark as not shared". */
@Preview(showBackground = true)
@Composable
internal fun DayScreenSharedPreview() {
    DayScreenPreviewFrame(
        DayUiState(
            anchorDate = PreviewDate,
            meetingCount = 3,
            fabState = FabState.Share(),
            armedCount = 3,
            sharedAt = PreviewDate.atTime(8, 12),
            days = mapOf(PreviewDate to PreviewEvents.alarmsSetDay),
        ),
    )
}

/**
 * Render 6's in-app half: the shared day has changed since ("2 changes since you shared" with
 * the New and Moved lines and "Re-share") above the timeline.
 */
@Preview(showBackground = true)
@Composable
internal fun DayScreenScheduleChangedPreview() {
    DayScreenPreviewFrame(
        DayUiState(
            anchorDate = PreviewDate,
            meetingCount = 3,
            fabState = FabState.Share(),
            armedCount = 3,
            sharedAt = PreviewDate.atTime(8, 12),
            days = mapOf(PreviewDate to PreviewEvents.alarmsSetDay),
            changeBanner = ScheduleChangeBannerState(PreviewBannerLines),
        ),
    )
}

/** Dark theme over render 6's state: banner, armed chips, the orange bell subtitle and the primary Share FAB on the dark scheme. */
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun DayScreenDarkPreview() {
    DayScreenPreviewFrame(
        DayUiState(
            anchorDate = PreviewDate,
            meetingCount = 3,
            fabState = FabState.Share(),
            armedCount = 3,
            days = mapOf(PreviewDate to PreviewEvents.alarmsSetDay),
            changeBanner = ScheduleChangeBannerState(PreviewBannerLines),
        ),
    )
}

/** 1.5× font scale on the busiest app bar: the long date and "3 alarms set · not shared yet" ellipsise rather than clip, and the banner and FAB still fit. */
@Preview(showBackground = true, fontScale = 1.5f)
@Composable
internal fun DayScreenLargeFontPreview() {
    DayScreenPreviewFrame(
        DayUiState(
            anchorDate = PreviewDate,
            meetingCount = 3,
            fabState = FabState.Share(),
            armedCount = 3,
            days = mapOf(PreviewDate to PreviewEvents.alarmsSetDay),
            changeBanner = ScheduleChangeBannerState(PreviewBannerLines),
        ),
    )
}

/** Swiped to the next day, which hasn't loaded yet: blank subtitle, empty timeline, Today enabled. */
@Preview(showBackground = true)
@Composable
internal fun DayScreenLoadingPreview() {
    DayScreenPreviewFrame(DayUiState(anchorDate = PreviewDate, date = PreviewDate.plusDays(1)))
}

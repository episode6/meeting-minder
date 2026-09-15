package com.episode6.meetingminder.ui.day

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.episode6.meetingminder.R
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * What [DayScreen] renders: the app bar for the settled [date] and one [DayTimelineState]
 * per loaded day of the pager. Selections and the FAB state join in PR-7.
 */
@Immutable
data class DayUiState(
    /** The pager's anchor page date (today at launch). */
    val anchorDate: LocalDate,
    /** The settled page's date: what the app bar shows. */
    val date: LocalDate = anchorDate,
    val isToday: Boolean = date == anchorDate,
    /** [com.episode6.meetingminder.model.CalendarEvent.isMeeting] count on [date]; null until it has loaded. */
    val meetingCount: Int? = null,
    /** Every loaded day's timeline; days not in here haven't loaded yet. */
    val days: Map<LocalDate, DayTimelineState> = emptyMap(),
    /** Where the timeline should first open, once today's events have loaded; see [initialFirstVisibleHour]. */
    val initialFirstVisibleHour: Float? = null,
) {
    fun timelineFor(date: LocalDate): DayTimelineState = days[date] ?: DayTimelineState(date)
}

private val TitleFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")

/**
 * The day view (render 2): date + subtitle app bar with the Today action and the overflow
 * menu over the [DayPager]. Reports each settled page through [onPageSettled] (a fling
 * reports only where it stops); "Today" scrolls the pager back to its anchor page, which
 * then settles like any swipe. All pages share [scrollState], which jumps once to
 * [DayUiState.initialFirstVisibleHour] when it arrives, unless the user has already scrolled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    state: DayUiState,
    onPageSettled: (LocalDate) -> Unit,
    onPermissionsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onCheckForUpdatesClick: () -> Unit,
    onEventClick: (TimelineEvent) -> Unit,
    onEventLongClick: (TimelineEvent) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    pagerState: PagerState = rememberDayPagerState(state.anchorDate, state.date),
    scrollState: ScrollState = rememberTimelineScrollState(),
) {
    val scope = rememberCoroutineScope()
    val currentOnPageSettled by rememberUpdatedState(onPageSettled)
    LaunchedEffect(pagerState, state.anchorDate) {
        snapshotFlow { pagerState.settledPage }.collect { currentOnPageSettled(pageToDate(it, state.anchorDate)) }
    }
    InitialScroll(state.initialFirstVisibleHour, scrollState)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.date.format(TitleFormatter), style = MaterialTheme.typography.titleLarge)
                        Text(
                            subtitle(state.meetingCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(DayViewDefaults.PagerAnchorPage) } },
                        enabled = !state.isToday,
                    ) {
                        Icon(Icons.Outlined.Today, contentDescription = stringResource(R.string.day_today))
                    }
                    OverflowMenu(
                        onPermissionsClick = onPermissionsClick,
                        onSettingsClick = onSettingsClick,
                        onLicensesClick = onLicensesClick,
                        onCheckForUpdatesClick = onCheckForUpdatesClick,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        DayPager(
            state = state,
            pagerState = pagerState,
            scrollState = scrollState,
            onEventClick = onEventClick,
            onEventLongClick = onEventLongClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

/** "3 meetings" / "1 meeting" / "No meetings"; blank (but still a line tall) while the day loads. */
@Composable
private fun subtitle(meetingCount: Int?): String = when (meetingCount) {
    null -> ""
    0 -> stringResource(R.string.day_subtitle_no_meetings)
    else -> pluralStringResource(R.plurals.day_subtitle_meetings, meetingCount, meetingCount)
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

@Composable
private fun OverflowMenu(
    onPermissionsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onCheckForUpdatesClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        listOf(
            R.string.menu_permissions to onPermissionsClick,
            R.string.menu_settings to onSettingsClick,
            R.string.menu_check_for_updates to onCheckForUpdatesClick,
            R.string.menu_licenses to onLicensesClick,
        ).forEach { (label, onClick) ->
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
            onPermissionsClick = {},
            onSettingsClick = {},
            onLicensesClick = {},
            onCheckForUpdatesClick = {},
            onEventClick = {},
            onEventLongClick = {},
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

/** Swiped to the next day, which hasn't loaded yet: blank subtitle, empty timeline, Today enabled. */
@Preview(showBackground = true)
@Composable
internal fun DayScreenLoadingPreview() {
    DayScreenPreviewFrame(DayUiState(anchorDate = PreviewDate, date = PreviewDate.plusDays(1)))
}

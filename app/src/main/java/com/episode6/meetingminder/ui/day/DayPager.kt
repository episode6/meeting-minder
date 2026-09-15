package com.episode6.meetingminder.ui.day

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventResponse
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Test tag on the root of [DayPager]. */
const val DAY_PAGER_TEST_TAG = "day_pager"

/** The date shown on pager [page]: [DayViewDefaults.PagerAnchorPage] is [anchorDate]. */
fun pageToDate(page: Int, anchorDate: LocalDate): LocalDate =
    anchorDate.plusDays((page - DayViewDefaults.PagerAnchorPage).toLong())

/** The pager page showing [date], clamped to the pager's range (about 27 years either way). */
fun dateToPage(date: LocalDate, anchorDate: LocalDate): Int =
    (DayViewDefaults.PagerAnchorPage + ChronoUnit.DAYS.between(anchorDate, date))
        .coerceIn(0L, DayViewDefaults.PagerPageCount - 1L)
        .toInt()

/**
 * Where the timeline should open (TODO.md §3.5): an hour before the first meeting that
 * starts on [date] (solo blocks don't count, per [CalendarEvent.isMeeting]), else
 * [DayViewDefaults.DefaultFirstVisibleHour].
 */
fun initialFirstVisibleHour(date: LocalDate, events: List<CalendarEvent>, zone: ZoneId): Float {
    val firstMeeting = events
        .filter { it.isMeeting }
        .map { LocalDateTime.ofInstant(it.begin, zone) }
        .filter { it.toLocalDate() == date }
        .minOrNull()
        ?: return DayViewDefaults.DefaultFirstVisibleHour.toFloat()
    return (firstMeeting.toLocalTime().toSecondOfDay() / SECONDS_PER_HOUR - 1f).coerceAtLeast(0f)
}

private const val SECONDS_PER_HOUR = 3600f

/** A [PagerState] for [DayPager] that starts on [initialDate]. */
@Composable
fun rememberDayPagerState(anchorDate: LocalDate, initialDate: LocalDate = anchorDate): PagerState =
    rememberPagerState(initialPage = dateToPage(initialDate, anchorDate)) { DayViewDefaults.PagerPageCount }

/**
 * Horizontal swiping between days (TODO.md §3.5): one [DayTimeline] per page, keyed by
 * epoch day, with the neighbouring pages kept composed so a swipe reveals an already
 * loaded day. Every page shares [scrollState], so swiping keeps the same time of day in
 * view. Pages whose day hasn't loaded yet show an empty timeline.
 */
@Composable
fun DayPager(
    state: DayUiState,
    pagerState: PagerState,
    scrollState: ScrollState,
    onEventClick: (LocalDate, TimelineEvent) -> Unit,
    onEventOpenClick: (TimelineEvent) -> Unit,
    onEventRespond: (LocalDate, TimelineEvent, EventResponse) -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier.testTag(DAY_PAGER_TEST_TAG),
        beyondViewportPageCount = DayViewDefaults.PagerBeyondViewportPageCount,
        key = { pageToDate(it, state.anchorDate).toEpochDay() },
    ) { page ->
        val date = pageToDate(page, state.anchorDate)
        DayTimeline(
            state = state.timelineFor(date),
            scrollState = scrollState,
            onEventClick = { event -> onEventClick(date, event) },
            onEventOpenClick = onEventOpenClick,
            onEventRespond = { event, response -> onEventRespond(date, event, response) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

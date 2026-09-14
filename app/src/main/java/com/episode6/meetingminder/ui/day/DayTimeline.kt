package com.episode6.meetingminder.ui.day

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import com.episode6.meetingminder.R
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Test tag on the root of [DayTimeline]. */
const val DAY_TIMELINE_TEST_TAG = "day_timeline"

/** [DayViewDefaults.MinChipHeight] as a duration, so short events are packed by the space their chip takes. */
private val MinChipMinutes = ceil(DayViewDefaults.MinChipHeight / DayViewDefaults.HourHeight * 60).toInt()

/**
 * One day of the itinerary (TODO.md §3.5): the all-day row (not scrolled, hidden when
 * empty), then a vertically scrolled row of the hour gutter and the events column — the
 * hour grid, the [DayEventsLayout] of [EventChip]s packed by [layoutDay], and the
 * [NowLine] on today.
 *
 * Stateless and data-free: [scrollState] is hoisted so [DayPager] can share one across
 * its pages and the callbacks only report which event was tapped. All-day chips
 * aren't selectable (all-day events are never meetings, never alarmed and never shared);
 * they only answer long-press. Needs a bounded height.
 */
@Composable
fun DayTimeline(
    state: DayTimelineState,
    scrollState: ScrollState,
    onEventClick: (TimelineEvent) -> Unit,
    onEventLongClick: (TimelineEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFormat = rememberTimelineTimeFormat()
    Column(modifier.testTag(DAY_TIMELINE_TEST_TAG)) {
        if (state.allDayEvents.isNotEmpty()) {
            AllDayRow(state.allDayEvents, onEventLongClick, timeFormat)
            HorizontalDivider(thickness = DayViewDefaults.GridLineThickness, color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(top = DayViewDefaults.TimelineTopPadding, bottom = DayViewDefaults.TimelineBottomPadding),
        ) {
            HourGutter(timeFormat, Modifier.width(DayViewDefaults.GutterWidth))
            Box(Modifier.weight(1f)) {
                HourGrid(Modifier.matchParentSize())
                TimedEvents(state, timeFormat, onEventClick, onEventLongClick)
                state.now?.let { now ->
                    val nowHours = now.toSecondOfDay() / 3600f
                    NowLine(
                        Modifier.offset {
                            IntOffset(
                                x = 0,
                                y = (nowHours * DayViewDefaults.HourHeight.toPx() - DayViewDefaults.NowDotSize.toPx() / 2).roundToInt(),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** A scroll state for [DayTimeline] starting with [firstVisibleHour] at the top. */
@Composable
fun rememberTimelineScrollState(
    firstVisibleHour: Float = DayViewDefaults.DefaultFirstVisibleHour.toFloat(),
): ScrollState {
    val initial = with(LocalDensity.current) { (DayViewDefaults.HourHeight * firstVisibleHour).roundToPx() }
    return rememberScrollState(initial)
}

@Composable
private fun AllDayRow(
    events: List<TimelineEvent>,
    onEventLongClick: (TimelineEvent) -> Unit,
    timeFormat: TimelineTimeFormat,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = DayViewDefaults.AllDayRowVerticalPadding),
    ) {
        val style = MaterialTheme.typography.labelSmall
        Text(
            stringResource(R.string.day_all_day),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            autoSize = gutterLabelAutoSize(style),
            modifier = Modifier
                // each all-day chip says "all day" itself
                .clearAndSetSemantics {}
                .width(DayViewDefaults.GutterWidth)
                .padding(end = DayViewDefaults.GutterLabelEndPadding)
                .heightIn(min = DayViewDefaults.AllDayChipHeight)
                .wrapContentHeight(Alignment.CenterVertically),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = DayViewDefaults.EventsStartPadding, end = DayViewDefaults.EventsEndPadding),
            verticalArrangement = Arrangement.spacedBy(DayViewDefaults.AllDayChipSpacing),
        ) {
            events.forEach { event ->
                key(event.key) {
                    EventChip(
                        event = event,
                        onClick = null,
                        onLongClick = { onEventLongClick(event) },
                        contentLayout = ChipContentLayout.TitleOnly,
                        timeFormat = timeFormat,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = DayViewDefaults.AllDayChipHeight),
                    )
                }
            }
        }
    }
}

/** Gutter labels keep their style's size when they fit and shrink (then ellipsize) when they don't. */
private fun gutterLabelAutoSize(style: TextStyle): TextAutoSize =
    TextAutoSize.StepBased(minFontSize = DayViewDefaults.GutterLabelMinFontSize, maxFontSize = style.fontSize)

/**
 * Hour labels ("1 AM" … "11 PM"), each vertically centred on its grid line and end-aligned
 * to the grid; midnight has none. Labels are measured within the gutter and shrink to fit,
 * so none is pushed past its start edge.
 */
@Composable
private fun HourGutter(timeFormat: TimelineTimeFormat, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        content = {
            for (hour in 1 until 24) {
                Text(
                    timeFormat.hourLabel(hour),
                    style = style,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = gutterLabelAutoSize(style),
                )
            }
        },
        // 23 hour labels are noise to TalkBack: every chip reads its own times out
        modifier = modifier.clearAndSetSemantics {},
    ) { measurables, constraints ->
        val hourPx = DayViewDefaults.HourHeight.toPx()
        val width = constraints.maxWidth
        val endPadding = DayViewDefaults.GutterLabelEndPadding.roundToPx()
        val labelWidth = max(0, width - endPadding)
        val placeables = measurables.map { it.measure(Constraints(maxWidth = labelWidth)) }
        layout(width, (hourPx * 24).roundToInt()) {
            placeables.forEachIndexed { index, placeable ->
                val hour = index + 1
                placeable.place(
                    (labelWidth - placeable.width).coerceAtLeast(0),
                    (hour * hourPx - placeable.height / 2f).roundToInt(),
                )
            }
        }
    }
}

@Composable
private fun HourGrid(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val hourPx = DayViewDefaults.HourHeight.toPx()
        val stroke = DayViewDefaults.GridLineThickness.toPx()
        for (hour in 0..24) {
            val y = (hour * hourPx).coerceIn(stroke / 2, size.height - stroke / 2)
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        }
    }
}

@Composable
private fun TimedEvents(
    state: DayTimelineState,
    timeFormat: TimelineTimeFormat,
    onEventClick: (TimelineEvent) -> Unit,
    onEventLongClick: (TimelineEvent) -> Unit,
) {
    val spans = remember(state.date, state.timedEvents) {
        state.timedEvents.map { minuteSpanOn(state.date, it.begin, it.end) }
    }
    val positions = remember(spans) { layoutDay(spans, MinChipMinutes) }
    val nowMinute = state.now?.let { it.toSecondOfDay() / 60 }
    val density = LocalDensity.current

    DayEventsLayout(Modifier.fillMaxWidth()) {
        state.timedEvents.forEachIndexed { index, event ->
            val span = spans[index]
            key(event.key) {
                EventChip(
                    event = event,
                    onClick = { onEventClick(event) },
                    onLongClick = { onEventLongClick(event) },
                    past = nowMinute != null && span.end <= nowMinute,
                    contentLayout = chipContentLayout(DayViewDefaults.chipHeight(span), density),
                    timeFormat = timeFormat,
                    modifier = Modifier.eventSlot(span, positions[index]),
                )
            }
        }
    }
}

@Composable
private fun DayTimelinePreviewFrame(
    state: DayTimelineState,
    darkTheme: Boolean = false,
    firstVisibleHour: Float = PreviewEvents.FIRST_VISIBLE_HOUR,
) {
    MeetingMinderTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            DayTimeline(
                state = state,
                scrollState = rememberTimelineScrollState(firstVisibleHour),
                onEventClick = {},
                onEventLongClick = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Render 2's day with nothing selected yet. */
@Preview(showBackground = true)
@Composable
internal fun DayTimelineBusyPreview() {
    DayTimelinePreviewFrame(PreviewEvents.busyDay)
}

/** Render 2: two events selected, one declined, the now-line at 8:35. */
@Preview(showBackground = true)
@Composable
internal fun DayTimelineSelectingPreview() {
    DayTimelinePreviewFrame(PreviewEvents.selectingDay)
}

/** Render 3: three selected events armed with bells and alarm times. */
@Preview(showBackground = true)
@Composable
internal fun DayTimelineAlarmsSetPreview() {
    DayTimelinePreviewFrame(PreviewEvents.alarmsSetDay)
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun DayTimelineDarkPreview() {
    DayTimelinePreviewFrame(PreviewEvents.alarmsSetDay, darkTheme = true)
}

@Preview(showBackground = true, fontScale = 1.5f)
@Composable
internal fun DayTimelineLargeFontPreview() {
    DayTimelinePreviewFrame(PreviewEvents.alarmsSetDay)
}

/** The packing cases from LayoutDayTest, plus short chips and past-event dimming (now = 11:10). */
@Preview(showBackground = true)
@Composable
internal fun DayTimelineOverlapsPreview() {
    DayTimelinePreviewFrame(PreviewEvents.overlapsDay, firstVisibleHour = 8.75f)
}

/** Not today: no now-line, nothing dimmed. */
@Preview(showBackground = true)
@Composable
internal fun DayTimelineEmptyPreview() {
    DayTimelinePreviewFrame(DayTimelineState(date = PreviewDate))
}

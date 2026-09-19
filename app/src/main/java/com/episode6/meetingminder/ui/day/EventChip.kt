package com.episode6.meetingminder.ui.day

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import android.content.res.Configuration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.episode6.meetingminder.R
import com.episode6.meetingminder.model.EventResponse
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import java.time.LocalTime

/** How much of a chip's text fits its height; chosen by [chipContentLayout]. */
enum class ChipContentLayout {
    /** All-day row: just the title. */
    TitleOnly,

    /**
     * One line: title on the left, time (or bell + alarm time) on the right. The time range
     * gives way to the start time alone when the whole title wouldn't fit beside it; the
     * start time (or the alarm time) always stays, and the title ellipsizes beside it.
     */
    Compact,

    /** Title line, then "time range · location" (or "· bell alarm time"). */
    TwoLine,

    /** Like [TwoLine] but the title may wrap to two lines. */
    Tall,
}

/** Title text on chips. */
@Composable
internal fun chipTitleStyle(): TextStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)

/** Time / location text on chips. */
@Composable
internal fun chipDetailStyle(): TextStyle = MaterialTheme.typography.bodySmall

/**
 * Picks the richest [ChipContentLayout] whose lines fit [chipHeight] at the current font
 * scale ([titleLineHeight] / [detailLineHeight] are the chip text styles' line heights in dp).
 */
internal fun chipContentLayout(chipHeight: Dp, titleLineHeight: Dp, detailLineHeight: Dp): ChipContentLayout {
    val padding = DayViewDefaults.ChipVerticalPadding * 2
    return when {
        chipHeight >= padding + titleLineHeight * 2 + detailLineHeight -> ChipContentLayout.Tall
        chipHeight >= padding + titleLineHeight + detailLineHeight -> ChipContentLayout.TwoLine
        else -> ChipContentLayout.Compact
    }
}

/** [chipContentLayout] for a chip of [chipHeight] with the theme's chip text styles. */
@Composable
internal fun chipContentLayout(chipHeight: Dp, density: Density): ChipContentLayout {
    val title = chipTitleStyle().lineHeight
    val detail = chipDetailStyle().lineHeight
    return with(density) { chipContentLayout(chipHeight, title.toDp(), detail.toDp()) }
}

/**
 * One event on the timeline (TODO.md §3.5). Visual states:
 * - unselected: the calendar colour at 12% fill with a 1.5dp border in the calendar colour
 *   (40% fill while [ChipStatus.Tentative]; no border when not selectable, as in the all-day row);
 * - selected: solid calendar colour + a check, animated;
 * - armed ([TimelineEvent.armed]): selected plus a bell and the alarm time;
 * - RSVP ([TimelineEvent.rsvp]): a small tick after the alarm time once the "Yes, going"
 *   went through, or a subtle "couldn't RSVP" hint when it couldn't (TODO.md §4.6);
 * - declined / cancelled: dashed outline and strikethrough, taps ignored;
 * - [past] (ended, on today): the whole chip at 60% alpha.
 *
 * Tapping toggles selection with a haptic tick ([onClick]; null makes the chip
 * non-selectable, as in the all-day row) and long-press opens the [EventSheet]: the full
 * title, when and where, "Open in calendar" ([onOpenClick]) and — for a
 * [TimelineEvent.respondable] event — Yes / No / Maybe ([onRespond], TODO.md §4.6).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EventChip(
    event: TimelineEvent,
    onClick: (() -> Unit)?,
    onOpenClick: () -> Unit,
    onRespond: (EventResponse) -> Unit,
    modifier: Modifier = Modifier,
    past: Boolean = false,
    contentLayout: ChipContentLayout = ChipContentLayout.TwoLine,
    timeFormat: TimelineTimeFormat = rememberTimelineTimeFormat(),
) {
    val colors = MaterialTheme.colorScheme
    val declined = event.status == ChipStatus.Declined
    val selectable = onClick != null && event.toggleable
    val solid = selectable && event.selected
    val shape = RoundedCornerShape(DayViewDefaults.ChipCornerRadius)

    val fill by animateColorAsState(
        when {
            declined -> colors.background
            solid -> event.color
            event.status == ChipStatus.Tentative ->
                event.color.copy(alpha = DayViewDefaults.TentativeFillAlpha).compositeOver(colors.background)
            else -> event.color.copy(alpha = DayViewDefaults.UnselectedFillAlpha).compositeOver(colors.background)
        },
        label = "chipFill",
    )
    val contentColor by animateColorAsState(
        when {
            solid -> if (event.color.luminance() > DayViewDefaults.LightChipLuminance) Color.Black else Color.White
            declined -> colors.onSurfaceVariant
            else -> colors.onSurface
        },
        label = "chipContent",
    )
    val detailColor = if (solid || declined) contentColor else colors.onSurfaceVariant

    val haptics = LocalHapticFeedback.current
    val alarmText = event.alarmAt?.let(timeFormat::time)
    val rsvpText = when (event.rsvp) {
        ChipRsvp.None -> null
        ChipRsvp.Sent -> stringResource(R.string.event_rsvp_sent)
        ChipRsvp.Failed -> stringResource(R.string.event_rsvp_failed)
    }
    val stateDescription = when {
        declined -> stringResource(R.string.event_state_declined)
        !selectable -> null
        // spoken, so the full "9:00 AM" rather than the chip's "9a"
        event.armed -> stringResource(R.string.event_state_alarm_set, event.alarmAt?.let(timeFormat::timeWithPeriod).orEmpty())
        event.selected -> stringResource(R.string.event_state_selected)
        else -> stringResource(R.string.event_state_not_selected)
    }?.let { state -> if (selectable && rsvpText != null) stringResource(R.string.event_state_with_rsvp, state, rsvpText) else state }
    // what TalkBack reads instead of the visible text, whose dashes, separators and bell
    // don't read out well: "Design review, 10:00 AM to 11:00 AM, Meet"
    val a11yLabel = when {
        contentLayout == ChipContentLayout.TitleOnly -> stringResource(R.string.event_a11y_all_day, event.title)
        event.location != null -> stringResource(
            R.string.event_a11y_timed_with_location,
            event.title,
            timeFormat.timeWithPeriod(event.begin.toLocalTime()),
            timeFormat.timeWithPeriod(event.end.toLocalTime()),
            event.location,
        )
        else -> stringResource(
            R.string.event_a11y_timed,
            event.title,
            timeFormat.timeWithPeriod(event.begin.toLocalTime()),
            timeFormat.timeWithPeriod(event.end.toLocalTime()),
        )
    }
    val clickLabel = when {
        !selectable -> null
        event.selected -> stringResource(R.string.event_action_deselect)
        else -> stringResource(R.string.event_action_select)
    }
    val longClickLabel = stringResource(R.string.event_action_more)
    var sheetOpen by remember { mutableStateOf(false) }

    Box(
        modifier
            .alpha(if (past) DayViewDefaults.PastEventAlpha else 1f)
            .clip(shape)
            .background(fill)
            .then(
                when {
                    declined -> Modifier.dashedBorder(colors.onSurfaceVariant)
                    // the border marks "tap to select"; solid chips and the all-day row don't need it
                    solid || onClick == null -> Modifier
                    else -> Modifier.border(DayViewDefaults.ChipBorderWidth, event.color, shape)
                },
            )
            .combinedClickable(
                role = if (selectable) Role.Checkbox else null,
                onClickLabel = clickLabel,
                onLongClickLabel = longClickLabel,
                onClick = {
                    if (selectable) {
                        haptics.performHapticFeedback(
                            if (event.selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        onClick()
                    }
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    sheetOpen = true
                },
            )
            .semantics(mergeDescendants = true) {
                // TalkBack speaks this instead of the merged text, which stays in the tree for tests
                contentDescription = a11yLabel
                stateDescription?.let { this.stateDescription = it }
            }
            .padding(horizontal = DayViewDefaults.ChipHorizontalPadding),
    ) {
        val titleStyle = chipTitleStyle().copy(
            color = contentColor,
            textDecoration = if (declined) TextDecoration.LineThrough else null,
        )
        val detailStyle = chipDetailStyle().copy(
            color = detailColor,
            textDecoration = if (declined) TextDecoration.LineThrough else null,
        )
        val timeRange = stringResource(
            R.string.event_time_range,
            timeFormat.time(event.begin.toLocalTime()),
            timeFormat.time(event.end.toLocalTime()),
        )

        when (contentLayout) {
            ChipContentLayout.TitleOnly, ChipContentLayout.Compact -> Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (solid) CheckIcon(contentColor)
                val title: @Composable (Modifier) -> Unit = { titleModifier ->
                    Text(
                        event.title,
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = titleModifier,
                    )
                }
                when {
                    contentLayout == ChipContentLayout.TitleOnly -> title(Modifier.weight(1f))
                    // the alarm time is the point of the armed state: it stays, like on the two-line chip
                    event.armed -> {
                        title(Modifier.weight(1f))
                        AlarmTime(alarmText.orEmpty(), detailStyle, Modifier.padding(start = DayViewDefaults.ChipIconSpacing))
                    }
                    else -> TitleWithTime(
                        title = { title(Modifier) },
                        time = { ChipTime(timeRange, detailStyle) },
                        shorterTime = { ChipTime(timeFormat.time(event.begin.toLocalTime()), detailStyle) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (contentLayout == ChipContentLayout.Compact) RsvpMark(event.rsvp, detailStyle, withLabel = false)
            }

            ChipContentLayout.TwoLine, ChipContentLayout.Tall -> Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(vertical = DayViewDefaults.ChipVerticalPadding),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    if (solid) CheckIcon(contentColor, Modifier.padding(top = DayViewDefaults.ChipCheckIconTopPadding))
                    Text(
                        event.title,
                        style = titleStyle,
                        maxLines = if (contentLayout == ChipContentLayout.Tall) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val separator = stringResource(R.string.event_detail_separator)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.armed) {
                        // the alarm time is the point of the armed state: the range gives way first
                        Text(
                            timeRange + separator,
                            style = detailStyle,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        AlarmTime(alarmText.orEmpty(), detailStyle)
                    } else {
                        Text(
                            event.location?.let { timeRange + separator + it } ?: timeRange,
                            style = detailStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    RsvpMark(event.rsvp, detailStyle, withLabel = true)
                }
            }
        }
        if (sheetOpen) {
            EventSheet(
                event = event,
                allDay = contentLayout == ChipContentLayout.TitleOnly,
                timeFormat = timeFormat,
                onDismiss = { sheetOpen = false },
                onOpenClick = onOpenClick,
                onRespond = onRespond,
            )
        }
    }
}

@Composable
private fun ChipTime(text: String, style: TextStyle) {
    Text(
        text,
        style = style,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(start = DayViewDefaults.ChipIconSpacing),
    )
}

/**
 * The compact row's title with a time on the right: the full [time] range when the whole
 * title fits beside it, and the [shorterTime] (start only) otherwise. The start time never
 * gives way — every chip says at least when it starts — so a long title ellipsizes beside
 * it rather than pushing it off the line.
 */
@Composable
private fun TitleWithTime(
    title: @Composable () -> Unit,
    time: @Composable () -> Unit,
    shorterTime: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(title, time, shorterTime), modifier = modifier) { (titleMeasurables, timeMeasurables, shorterTimeMeasurables), constraints ->
        val titleMeasurable = titleMeasurables.single()
        val titleWidth = titleMeasurable.maxIntrinsicWidth(constraints.maxHeight)
        val fullTime = timeMeasurables.single()
        val fullTimeWidth = fullTime.maxIntrinsicWidth(constraints.maxHeight)
        // fills a bounded width (the chip row's weight), and takes only what it needs otherwise
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else titleWidth + fullTimeWidth
        val timePlaceable = (if (titleWidth + fullTimeWidth <= width) fullTime else shorterTimeMeasurables.single())
            .measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = width))
        val titlePlaceable = titleMeasurable.measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (width - timePlaceable.width).coerceAtLeast(0)),
        )
        val height = maxOf(titlePlaceable.height, timePlaceable.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
            timePlaceable.placeRelative(width - timePlaceable.width, (height - timePlaceable.height) / 2)
        }
    }
}

@Composable
private fun CheckIcon(tint: Color, modifier: Modifier = Modifier) {
    Icon(
        Icons.Outlined.CheckCircle,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .padding(end = DayViewDefaults.ChipIconSpacing)
            .size(DayViewDefaults.ChipIconSize),
    )
}

@Composable
private fun AlarmTime(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Notifications,
            contentDescription = null,
            tint = style.color,
            modifier = Modifier
                .padding(end = DayViewDefaults.ChipInlineIconSpacing)
                .size(DayViewDefaults.ChipInlineIconSize),
        )
        Text(text, style = style, maxLines = 1, softWrap = false)
    }
}

/**
 * The RSVP outcome after the alarm time: a tick for [ChipRsvp.Sent], a warning glyph
 * (plus "couldn't RSVP" when [withLabel]) for [ChipRsvp.Failed]. Icons carry no content
 * description; the chip's `stateDescription` reads the outcome out instead. Only the
 * labelled form takes a share of the row (and ellipsizes) — an icon alone must never make
 * the time range give way.
 */
@Composable
private fun RowScope.RsvpMark(rsvp: ChipRsvp, style: TextStyle, withLabel: Boolean) {
    if (rsvp == ChipRsvp.None) return
    val labelled = withLabel && rsvp == ChipRsvp.Failed
    Row(
        Modifier
            .then(if (labelled) Modifier.weight(1f, fill = false) else Modifier)
            .padding(start = DayViewDefaults.ChipIconSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (rsvp == ChipRsvp.Sent) Icons.Outlined.Done else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = style.color,
            modifier = Modifier.size(DayViewDefaults.ChipInlineIconSize),
        )
        if (labelled) {
            Text(
                stringResource(R.string.event_rsvp_failed),
                style = style,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = DayViewDefaults.ChipInlineIconSpacing),
            )
        }
    }
}

private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    val stroke = DayViewDefaults.ChipDeclinedBorderWidth.toPx()
    val radius = DayViewDefaults.ChipCornerRadius.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(DayViewDefaults.ChipDeclinedDashOn.toPx(), DayViewDefaults.ChipDeclinedDashOff.toPx()),
            ),
        ),
    )
}

/** Every chip state side by side, for reviewing the look against render 2 / 3. */
@Preview(showBackground = true, widthDp = 360)
@Composable
internal fun EventChipStatesPreview() {
    EventChipStates()
}

/** Every chip state on the dark background: 12% fills, dashed declined outline and dimmed past chip must all still read. */
@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun EventChipStatesDarkPreview() {
    EventChipStates()
}

@Composable
private fun EventChipStates() {
    val base = PreviewEvents.standup.copy(title = "Design review", location = "Meet")
    MeetingMinderTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    base to false,
                    base.copy(status = ChipStatus.Tentative, title = "Tentative") to false,
                    base.copy(selected = true, title = "Selected") to false,
                    base.copy(selected = true, alarmAt = LocalTime.of(9, 55), title = "Armed") to false,
                    base.copy(selected = true, alarmAt = LocalTime.of(9, 55), rsvp = ChipRsvp.Sent, title = "Armed, RSVP sent") to false,
                    base.copy(selected = true, alarmAt = LocalTime.of(9, 55), rsvp = ChipRsvp.Failed, title = "Armed, couldn't RSVP") to false,
                    base.copy(status = ChipStatus.Declined, title = "Declined") to false,
                    base.copy(title = "Past") to true,
                    PreviewEvents.dentist.copy(selected = true, title = "Selected, dark calendar colour") to false,
                    base.copy(color = Color(0xFFFFEB3B), selected = true, title = "Selected, light calendar colour") to false,
                ).forEach { (event, past) ->
                    EventChip(
                        event = event,
                        onClick = {},
                        onOpenClick = {},
                        onRespond = {},
                        past = past,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    )
                }
                listOf(
                    base.copy(title = "Compact"),
                    base.copy(title = "Compact armed", selected = true, alarmAt = LocalTime.of(8, 55)),
                    base.copy(title = "Compact armed, RSVP sent", selected = true, alarmAt = LocalTime.of(8, 55), rsvp = ChipRsvp.Sent),
                    base.copy(title = "Compact with room for the start time only"),
                    base.copy(title = "Compact with a title too long to share the line with even its start time"),
                ).forEach { event ->
                    EventChip(
                        event = event,
                        onClick = {},
                        onOpenClick = {},
                        onRespond = {},
                        contentLayout = ChipContentLayout.Compact,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                    )
                }
                EventChip(
                    event = PreviewEvents.planningWeek,
                    onClick = null,
                    onOpenClick = {},
                    onRespond = {},
                    contentLayout = ChipContentLayout.TitleOnly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DayViewDefaults.AllDayChipHeight),
                )
            }
        }
    }
}

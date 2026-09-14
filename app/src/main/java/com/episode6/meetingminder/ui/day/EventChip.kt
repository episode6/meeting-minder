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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import java.time.LocalTime

/** How much of a chip's text fits its height; chosen by [chipContentLayout]. */
enum class ChipContentLayout {
    /** All-day row: just the title. */
    TitleOnly,

    /** One line: title on the left, time (or bell + alarm time) on the right. */
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
 * - declined / cancelled: dashed outline and strikethrough, taps ignored;
 * - [past] (ended, on today): the whole chip at 60% alpha.
 *
 * Tapping toggles selection with a haptic tick ([onClick]; null makes the chip
 * non-selectable, as in the all-day row) and long-press opens the event ([onLongClick]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EventChip(
    event: TimelineEvent,
    onClick: (() -> Unit)?,
    onLongClick: () -> Unit,
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
    val stateDescription = when {
        declined -> stringResource(R.string.event_state_declined)
        !selectable -> null
        event.armed -> stringResource(R.string.event_state_alarm_set, alarmText.orEmpty())
        event.selected -> stringResource(R.string.event_state_selected)
        else -> stringResource(R.string.event_state_not_selected)
    }

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
                onClick = {
                    if (selectable) {
                        haptics.performHapticFeedback(
                            if (event.selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        onClick()
                    }
                },
                onLongClick = onLongClick,
            )
            .semantics(mergeDescendants = true) {
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
                Text(
                    event.title,
                    style = titleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (contentLayout == ChipContentLayout.Compact) {
                    if (event.armed) {
                        AlarmTime(alarmText.orEmpty(), detailStyle, Modifier.padding(start = DayViewDefaults.ChipIconSpacing))
                    } else {
                        Text(
                            timeRange,
                            style = detailStyle,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(start = DayViewDefaults.ChipIconSpacing),
                        )
                    }
                }
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
                        )
                    }
                }
            }
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
                    base.copy(status = ChipStatus.Declined, title = "Declined") to false,
                    base.copy(title = "Past") to true,
                    PreviewEvents.dentist.copy(selected = true, title = "Selected, dark calendar colour") to false,
                    base.copy(color = Color(0xFFFFEB3B), selected = true, title = "Selected, light calendar colour") to false,
                ).forEach { (event, past) ->
                    EventChip(
                        event = event,
                        onClick = {},
                        onLongClick = {},
                        past = past,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    )
                }
                listOf(
                    base.copy(title = "Compact"),
                    base.copy(title = "Compact armed", selected = true, alarmAt = LocalTime.of(8, 55)),
                ).forEach { event ->
                    EventChip(
                        event = event,
                        onClick = {},
                        onLongClick = {},
                        contentLayout = ChipContentLayout.Compact,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                    )
                }
                EventChip(
                    event = PreviewEvents.planningWeek,
                    onClick = null,
                    onLongClick = {},
                    contentLayout = ChipContentLayout.TitleOnly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DayViewDefaults.AllDayChipHeight),
                )
            }
        }
    }
}

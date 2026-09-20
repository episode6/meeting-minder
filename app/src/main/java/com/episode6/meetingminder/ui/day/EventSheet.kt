package com.episode6.meetingminder.ui.day

import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.episode6.meetingminder.R
import com.episode6.meetingminder.model.EventResponse
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Monday, Sep 14": the weekday, month and day the locale orders them in. Built per locale
 * like [TimelineTimeFormat], since a formatter held in a `val` would fix the JVM's default
 * locale at class load. No year — the sheet is opened from a day the user just navigated to,
 * and the app bar's own date doesn't carry one either.
 */
@Composable
private fun rememberSheetDateFormatter(): DateTimeFormatter {
    val locale = LocalResources.current.configuration.locales[0]
    return remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEMMMd"), locale)
    }
}

private object EventSheetDefaults {
    val HorizontalPadding = 24.dp
    val BottomPadding = 24.dp
    val SwatchSize = 16.dp
    val SwatchCornerRadius = 4.dp

    /** Drops the swatch to sit on the title's first line rather than above it. */
    val SwatchTopPadding = 6.dp
    val SwatchSpacing = 16.dp
    val DetailSpacing = 4.dp
    val DividerTopPadding = 16.dp
    val DividerBottomPadding = 8.dp

    /** The list item's own 16dp plus this lines "Open in calendar" up with the header. */
    val ActionHorizontalPadding = 8.dp
    val ResponseLabelVerticalPadding = 8.dp
    val ResponseRowMinHeight = 40.dp
}

/** When an event happens, as the sheet words it; see [eventWhen]. */
internal sealed interface EventWhen {
    /** A timed event inside one day (ending at the next midnight counts). */
    data class SameDay(val date: LocalDate, val begin: LocalTime, val end: LocalTime) : EventWhen

    /** An all-day event covering [first] through [last], inclusive. */
    data class AllDay(val first: LocalDate, val last: LocalDate) : EventWhen

    /** A timed event that crosses midnight. */
    data class Spanning(val begin: LocalDateTime, val end: LocalDateTime) : EventWhen
}

/**
 * Classifies an event's [begin]/[end] for the sheet. An all-day event's end is the exclusive
 * midnight after its last day, so a one-day event reads as that day alone.
 */
internal fun eventWhen(begin: LocalDateTime, end: LocalDateTime, allDay: Boolean): EventWhen {
    val firstDay = begin.toLocalDate()
    return when {
        allDay -> {
            val lastDay = if (end.toLocalTime() == LocalTime.MIDNIGHT) end.toLocalDate().minusDays(1) else end.toLocalDate()
            EventWhen.AllDay(firstDay, maxOf(firstDay, lastDay))
        }
        end.toLocalDate() == firstDay || end == firstDay.plusDays(1).atStartOfDay() ->
            EventWhen.SameDay(firstDay, begin.toLocalTime(), end.toLocalTime())
        else -> EventWhen.Spanning(begin, end)
    }
}

/**
 * A chip's long-press sheet (TODO.md §4.6): the event's full title — the chip ellipsizes it —
 * with when and where it is, then "Open in calendar" ([onOpenClick]) and, only for a
 * [TimelineEvent.respondable] invite, Yes / No / Maybe as one segmented row with the
 * calendar's current answer ([TimelineEvent.response]) selected ([onRespond]). Choosing
 * either reports it at once and slides the sheet away, then calls [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventSheet(
    event: TimelineEvent,
    timeFormat: TimelineTimeFormat,
    onDismiss: () -> Unit,
    onOpenClick: () -> Unit,
    onRespond: (EventResponse) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        EventSheetContent(
            event = event,
            timeFormat = timeFormat,
            onOpenClick = {
                onOpenClick()
                close()
            },
            onRespond = { response ->
                onRespond(response)
                close()
            },
        )
    }
}

@Composable
internal fun EventSheetContent(
    event: TimelineEvent,
    timeFormat: TimelineTimeFormat,
    onOpenClick: () -> Unit,
    onRespond: (EventResponse) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = EventSheetDefaults.BottomPadding)) {
        Row(Modifier.padding(horizontal = EventSheetDefaults.HorizontalPadding)) {
            Spacer(
                Modifier
                    .padding(top = EventSheetDefaults.SwatchTopPadding)
                    .size(EventSheetDefaults.SwatchSize)
                    .clip(RoundedCornerShape(EventSheetDefaults.SwatchCornerRadius))
                    .background(event.color),
            )
            Spacer(Modifier.width(EventSheetDefaults.SwatchSpacing))
            Column(verticalArrangement = Arrangement.spacedBy(EventSheetDefaults.DetailSpacing)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                (whenLines(eventWhen(event.begin, event.end, event.allDay), timeFormat) + listOfNotNull(event.location))
                    .forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
            }
        }
        HorizontalDivider(
            Modifier.padding(top = EventSheetDefaults.DividerTopPadding, bottom = EventSheetDefaults.DividerBottomPadding),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.event_sheet_open_in_calendar)) },
            leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clickable(onClick = onOpenClick)
                .padding(horizontal = EventSheetDefaults.ActionHorizontalPadding),
        )
        if (event.respondable) ResponseRow(event.response, onRespond)
    }
}

@Composable
private fun ResponseRow(response: EventResponse?, onRespond: (EventResponse) -> Unit) {
    Text(
        stringResource(R.string.event_sheet_your_response),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            horizontal = EventSheetDefaults.HorizontalPadding,
            vertical = EventSheetDefaults.ResponseLabelVerticalPadding,
        ),
    )
    val options = EventResponse.entries
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = EventSheetDefaults.HorizontalPadding)
            .heightIn(min = EventSheetDefaults.ResponseRowMinHeight),
    ) {
        options.forEachIndexed { index, candidate ->
            val label = stringResource(candidate.label)
            // the visible label is the bare "Yes"; TalkBack says what it answers, as the menu did
            val spoken = stringResource(R.string.event_sheet_respond_a11y, label)
            SegmentedButton(
                selected = candidate == response,
                onClick = { onRespond(candidate) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.semantics { contentDescription = spoken },
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun whenLines(time: EventWhen, format: TimelineTimeFormat): List<String> {
    val dates = rememberSheetDateFormatter()
    return when (time) {
        is EventWhen.SameDay -> listOf(
            time.date.format(dates),
            stringResource(R.string.event_time_range, format.timeWithPeriod(time.begin), format.timeWithPeriod(time.end)),
        )
        is EventWhen.AllDay -> listOf(
            if (time.first == time.last) {
                time.first.format(dates)
            } else {
                stringResource(R.string.event_time_range, time.first.format(dates), time.last.format(dates))
            },
            stringResource(R.string.event_sheet_all_day),
        )
        // like Google Calendar's own event view: each end on its own line
        is EventWhen.Spanning -> listOf(
            stringResource(R.string.event_sheet_spanning_begin, time.begin.format(dates), format.timeWithPeriod(time.begin.toLocalTime())),
            stringResource(R.string.event_sheet_spanning_end, time.end.format(dates), format.timeWithPeriod(time.end.toLocalTime())),
        )
    }
}

private val EventResponse.label: Int
    get() = when (this) {
        EventResponse.YES -> R.string.event_sheet_respond_yes
        EventResponse.NO -> R.string.event_sheet_respond_no
        EventResponse.MAYBE -> R.string.event_sheet_respond_maybe
    }

/** The sheet for a long title on an invite answered Yes: the title wraps in full, the answers show the current one. */
@Preview(showBackground = true, widthDp = 360)
@Composable
internal fun EventSheetContentPreview() {
    EventSheetPreviewFrame(
        PreviewEvents.designReview.copy(
            title = "Quarterly business review with the Northwind partnership team (legal + finance)",
            respondable = true,
            response = EventResponse.YES,
        ),
    )
}

@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun EventSheetContentDarkPreview() {
    EventSheetPreviewFrame(PreviewEvents.designReview.copy(respondable = true))
}

/** An all-day event over several days, which can't be answered: just the details and "Open in calendar". */
@Preview(showBackground = true, widthDp = 360)
@Composable
internal fun EventSheetContentAllDayPreview() {
    EventSheetPreviewFrame(PreviewEvents.planningWeek)
}

/** An event crossing midnight: each end takes its own line, with its own date. */
@Preview(showBackground = true, widthDp = 360)
@Composable
internal fun EventSheetContentSpanningPreview() {
    EventSheetPreviewFrame(
        PreviewEvents.designReview.copy(
            title = "Overnight deploy window",
            location = null,
            begin = PreviewDate.atTime(23, 0),
            end = PreviewDate.plusDays(1).atTime(1, 30),
        ),
    )
}

@Composable
private fun EventSheetPreviewFrame(event: TimelineEvent) {
    MeetingMinderTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            EventSheetContent(
                event = event,
                timeFormat = TimelineTimeFormat(is24Hour = false, locale = Locale.US),
                onOpenClick = {},
                onRespond = {},
                modifier = Modifier.padding(top = EventSheetDefaults.BottomPadding),
            )
        }
    }
}

package com.episode6.meetingminder.ui.day

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.episode6.meetingminder.R
import com.episode6.meetingminder.monitor.ScheduleChangeLine
import com.episode6.meetingminder.monitor.text
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme

/**
 * The day view's "changed since you shared" banner (render 6, TODO.md §4.3): shown for a
 * shared day until it is re-shared. [lines] are the changes the check recorded (the same
 * lines as the notification); empty means only the selection has changed since the share
 * (§2: changing selection after sharing shows the banner too).
 */
@Immutable
data class ScheduleChangeBannerState(val lines: List<ScheduleChangeLine>)

/** Error-container card with the change count, the change lines (two at most, ellipsised) and "Re-share". */
@Composable
fun ScheduleChangeBanner(state: ScheduleChangeBannerState, onReshareClick: () -> Unit, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DayViewDefaults.BannerOuterHorizontalPadding, vertical = DayViewDefaults.BannerOuterVerticalPadding),
        shape = RoundedCornerShape(DayViewDefaults.BannerCornerRadius),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(
                start = DayViewDefaults.BannerContentStartPadding,
                end = DayViewDefaults.BannerContentEndPadding,
                top = DayViewDefaults.BannerContentVerticalPadding,
                bottom = DayViewDefaults.BannerContentVerticalPadding,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DayViewDefaults.BannerContentSpacing),
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            // TalkBack announces the banner as it appears, without moving focus to it. The
            // live region sits on the node that merges the text, not on the Surface: the
            // content-change event is sent for the node carrying liveRegion, and only the
            // merged node has text of its own to speak.
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(
                    text = if (state.lines.isEmpty()) {
                        stringResource(R.string.day_banner_selection_changed)
                    } else {
                        pluralStringResource(R.plurals.day_banner_changes, state.lines.size, state.lines.size)
                    },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                if (state.lines.isNotEmpty()) {
                    Text(
                        text = state.lines.joinToString(stringResource(R.string.schedule_change_separator)) { resources.text(it) },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = DayViewDefaults.BannerDetailMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(
                onClick = onReshareClick,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.day_banner_reshare), fontWeight = FontWeight.Bold)
            }
        }
    }
}

internal val PreviewBannerLines = listOf(
    ScheduleChangeLine.New("3:00 – 3:30 PM"),
    ScheduleChangeLine.Moved("12:00 – 1:00 PM", "12:30 – 1:30 PM"),
)

/** Dark theme: render 6's two changes, four changes long enough to ellipsise, and the selection-only variant. */
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun ScheduleChangeBannersDarkPreview() {
    MeetingMinderTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                ScheduleChangeBanner(ScheduleChangeBannerState(PreviewBannerLines), onReshareClick = {})
                ScheduleChangeBanner(
                    ScheduleChangeBannerState(
                        PreviewBannerLines + listOf(
                            ScheduleChangeLine.Cancelled("4:00 – 4:30 PM"),
                            ScheduleChangeLine.Declined("5:00 – 6:00 PM"),
                        ),
                    ),
                    onReshareClick = {},
                )
                ScheduleChangeBanner(ScheduleChangeBannerState(emptyList()), onReshareClick = {})
            }
        }
    }
}

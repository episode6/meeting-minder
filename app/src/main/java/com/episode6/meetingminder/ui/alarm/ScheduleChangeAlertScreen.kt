package com.episode6.meetingminder.ui.alarm

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.episode6.meetingminder.R
import com.episode6.meetingminder.alarm.alertReshareLabel
import com.episode6.meetingminder.data.calendar.ShareMode
import com.episode6.meetingminder.monitor.ScheduleChangeLine
import com.episode6.meetingminder.monitor.text
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import java.time.LocalTime

/** What [ScheduleChangeAlertScreen] shows; built from the store's ringing schedule-change alert by [AlarmRingingViewModel]. */
data class ScheduleChangeAlertScreenState(
    /** The big clock. */
    val now: LocalTime,
    /** One line per change, times only, exactly what the quiet notification and the day view's banner say. */
    val lines: List<ScheduleChangeLine>,
    /** What a re-share does right now (TODO.md §4.7): "Re-share", "Sync & Re-share", or "Re-sync" with "since you synced". */
    val shareMode: ShareMode,
    val soundName: String?,
    val silenced: Boolean = false,
)

/**
 * The full-screen schedule-change alert (TODO.md §4.3), hosted by `AlarmActivity` like the
 * ringing alarm and in its shape: today changed after it was shared, here is how, and what
 * to do about it — re-share (syncing the busy calendar first when that is on), open today's
 * itinerary, silence it, or dismiss it. [animated] is off in previews.
 */
@Composable
fun ScheduleChangeAlertScreen(
    state: ScheduleChangeAlertScreenState,
    onReshare: () -> Unit,
    onOpenItinerary: () -> Unit,
    onSilence: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val resources = LocalResources.current
    RingingLayout(
        heading = stringResource(R.string.schedule_alert_heading),
        now = state.now,
        icon = Icons.Rounded.EventRepeat,
        animated = animated,
        modifier = modifier,
        details = {
            Text(
                // the banner's words: the heading above already says "schedule changed"
                text = pluralStringResource(
                    if (state.shareMode == ShareMode.SYNC_ONLY) R.plurals.day_banner_changes_synced else R.plurals.day_banner_changes,
                    state.lines.size,
                    state.lines.size,
                ),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            for (line in state.lines) {
                Text(
                    text = resources.text(line),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        buttons = {
            Button(onClick = onReshare, modifier = Modifier.fillMaxWidth().heightIn(min = AlarmRingingDefaults.ButtonHeight)) {
                Text(
                    stringResource(state.shareMode.alertReshareLabel()),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            SecondaryButton(stringResource(R.string.schedule_alert_open_itinerary), onOpenItinerary)
            if (!state.silenced) SecondaryButton(stringResource(R.string.alarm_silence), onSilence)
            DismissButton(onDismiss)
            SoundLine(state.soundName, state.silenced)
        },
    )
}

private val PreviewAlert = ScheduleChangeAlertScreenState(
    now = LocalTime.of(11, 42),
    lines = listOf(
        ScheduleChangeLine.New("3:00 – 3:30 PM"),
        ScheduleChangeLine.Moved("12:00 – 1:00 PM", "12:30 – 1:30 PM"),
    ),
    shareMode = ShareMode.SYNC_AND_TEXT,
    soundName = "Siren sweep",
)

/** A new meeting and a moved one, with busy-calendar sync on. */
@Preview(showBackground = true)
@Composable
internal fun ScheduleChangeAlertScreenPreview() {
    MeetingMinderTheme(darkTheme = true) {
        ScheduleChangeAlertScreen(PreviewAlert, onReshare = {}, onOpenItinerary = {}, onSilence = {}, onDismiss = {}, animated = false)
    }
}

/** Sync-only busy-calendar sync (TODO.md §4.7): "2 changes since you synced" and "Re-sync". */
@Preview(showBackground = true)
@Composable
internal fun ScheduleChangeAlertScreenSyncOnlyPreview() {
    MeetingMinderTheme(darkTheme = true) {
        ScheduleChangeAlertScreen(
            PreviewAlert.copy(shareMode = ShareMode.SYNC_ONLY),
            onReshare = {},
            onOpenItinerary = {},
            onSilence = {},
            onDismiss = {},
            animated = false,
        )
    }
}

/** Silenced, one cancellation, no busy-calendar sync: "Re-share", no Silence button. */
@Preview(showBackground = true)
@Composable
internal fun ScheduleChangeAlertScreenSilencedPreview() {
    MeetingMinderTheme(darkTheme = true) {
        ScheduleChangeAlertScreen(
            PreviewAlert.copy(lines = listOf(ScheduleChangeLine.Cancelled("2:00 – 2:45 PM")), shareMode = ShareMode.TEXT, silenced = true),
            onReshare = {},
            onOpenItinerary = {},
            onSilence = {},
            onDismiss = {},
            animated = false,
        )
    }
}

/** 1.5× font scale with every kind of change: it scrolls rather than clipping. */
@Preview(showBackground = true, fontScale = 1.5f)
@Composable
internal fun ScheduleChangeAlertScreenLargeFontPreview() {
    MeetingMinderTheme(darkTheme = true) {
        ScheduleChangeAlertScreen(
            PreviewAlert.copy(lines = PreviewAlert.lines + ScheduleChangeLine.Cancelled("2:00 – 2:45 PM") + ScheduleChangeLine.Declined("4:00 – 5:00 PM")),
            onReshare = {},
            onOpenItinerary = {},
            onSilence = {},
            onDismiss = {},
            animated = false,
        )
    }
}

package com.episode6.meetingminder.ui.alarm

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.episode6.meetingminder.R
import com.episode6.meetingminder.ui.day.rememberTimelineTimeFormat
import com.episode6.meetingminder.ui.theme.AlarmDismissContainer
import com.episode6.meetingminder.ui.theme.AlarmOnDismissContainer
import com.episode6.meetingminder.ui.theme.AlarmPulseCore
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import java.time.LocalTime

/** What [AlarmRingingScreen] shows (render 5); built from the store's ringing alarm by [AlarmRingingViewModel]. */
data class AlarmRingingScreenState(
    val title: String,
    val location: String?,
    val begin: LocalTime,
    val end: LocalTime,
    /** The big clock. */
    val now: LocalTime,
    /** Whole minutes until the meeting starts, rounded up; 0 while it is starting, negative once it has started. */
    val minutesUntilStart: Long,
    val snoozeMinutes: Long,
    /** The random sound playing, for the subtle debug line; null until one has started. */
    val soundName: String?,
    /** False for Settings' "Test alarm" (`TEST_ALARM_EVENT_ID`): there is no real event to open. */
    val canOpenMeeting: Boolean = true,
)

/** Sizes of the ringing screen, kept out of the layout code. */
object AlarmRingingDefaults {
    val HorizontalPadding = 24.dp
    val VerticalPadding = 32.dp
    val PulseSize = 240.dp
    val PulseMiddleSize = 200.dp
    val PulseCoreSize = 150.dp
    val IconSize = 72.dp
    val ButtonHeight = 56.dp
    val ButtonSpacing = 12.dp
    val SectionSpacing = 24.dp
    val CountdownLetterSpacing = 2.sp
    const val PULSE_OUTER_ALPHA = 0.07f
    const val PULSE_MIDDLE_ALPHA = 0.12f
    const val PULSE_SCALE = 1.08f
    const val PULSE_MILLIS = 900
    const val SOUND_LINE_ALPHA = 0.6f
}

/**
 * The full-screen ringing alarm (TODO.md §4.4, render 5), hosted by `AlarmActivity` over
 * the lock screen: the countdown to the meeting, a big clock, the pulsing alarm, the
 * meeting's title/time/place, Dismiss and Snooze, "Open meeting", and which random sound is
 * playing. Always dark. Scrolls when a large font scale doesn't fit. [animated] is off in
 * previews so screenshot tests capture a still frame.
 */
@Composable
fun AlarmRingingScreen(
    state: AlarmRingingScreenState,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onOpenMeeting: () -> Unit,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val timeFormat = rememberTimelineTimeFormat()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                // at least a screen tall so SpaceBetween spreads it out, scrolling when a
                // large font makes it taller than that
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = AlarmRingingDefaults.HorizontalPadding, vertical = AlarmRingingDefaults.VerticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = countdown(state.minutesUntilStart).toUpperCase(LocaleList.current),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = AlarmRingingDefaults.CountdownLetterSpacing,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = timeFormat.time(state.now),
                        style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.height(AlarmRingingDefaults.SectionSpacing))
                AlarmPulse(animated)
                Spacer(Modifier.height(AlarmRingingDefaults.SectionSpacing))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = detailLine(state, timeFormat.time(state.begin), timeFormat.time(state.end)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(AlarmRingingDefaults.SectionSpacing))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AlarmRingingDefaults.ButtonSpacing),
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().heightIn(min = AlarmRingingDefaults.ButtonHeight),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AlarmDismissContainer,
                            contentColor = AlarmOnDismissContainer,
                        ),
                    ) {
                        Text(stringResource(R.string.alarm_dismiss), style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedButton(
                        onClick = onSnooze,
                        modifier = Modifier.fillMaxWidth().heightIn(min = AlarmRingingDefaults.ButtonHeight),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                    ) {
                        Text(stringResource(R.string.alarm_snooze_minutes, state.snoozeMinutes), style = MaterialTheme.typography.titleMedium)
                    }
                    if (state.canOpenMeeting) {
                        TextButton(onClick = onOpenMeeting) {
                            Text(stringResource(R.string.alarm_open_meeting))
                        }
                    }
                    if (state.soundName != null) {
                        Text(
                            text = stringResource(R.string.alarm_sound_playing, state.soundName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlarmRingingDefaults.SOUND_LINE_ALPHA),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun countdown(minutesUntilStart: Long): String = when {
    minutesUntilStart > 0 -> pluralStringResource(R.plurals.alarm_meeting_in, minutesUntilStart.toInt(), minutesUntilStart)
    minutesUntilStart == 0L -> stringResource(R.string.alarm_meeting_now)
    else -> pluralStringResource(R.plurals.alarm_meeting_started, (-minutesUntilStart).toInt(), -minutesUntilStart)
}

@Composable
private fun detailLine(state: AlarmRingingScreenState, begin: String, end: String): String {
    val range = stringResource(R.string.event_time_range, begin, end)
    return if (state.location.isNullOrBlank()) range else range + stringResource(R.string.event_detail_separator) + state.location
}

/** Render 5's concentric rings around the alarm icon, breathing while it rings. */
@Composable
private fun AlarmPulse(animated: Boolean) {
    val scale = if (animated) {
        val transition = rememberInfiniteTransition(label = "alarm pulse")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = AlarmRingingDefaults.PULSE_SCALE,
            animationSpec = infiniteRepeatable(tween(AlarmRingingDefaults.PULSE_MILLIS, easing = LinearEasing), RepeatMode.Reverse),
            label = "alarm pulse scale",
        )
        value
    } else {
        1f
    }
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.size(AlarmRingingDefaults.PulseSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(AlarmRingingDefaults.PulseSize).scale(scale).clip(CircleShape)
                .background(primary.copy(alpha = AlarmRingingDefaults.PULSE_OUTER_ALPHA)),
        )
        Box(
            Modifier.size(AlarmRingingDefaults.PulseMiddleSize).clip(CircleShape)
                .background(primary.copy(alpha = AlarmRingingDefaults.PULSE_MIDDLE_ALPHA)),
        )
        Box(
            Modifier.size(AlarmRingingDefaults.PulseCoreSize).clip(CircleShape).background(AlarmPulseCore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Alarm,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(AlarmRingingDefaults.IconSize),
            )
        }
    }
}

private val PreviewRinging = AlarmRingingScreenState(
    title = "Design review: alarms flow",
    location = "Google Meet",
    begin = LocalTime.of(10, 0),
    end = LocalTime.of(11, 0),
    now = LocalTime.of(9, 55),
    minutesUntilStart = 5,
    snoozeMinutes = 2,
    soundName = "Siren sweep",
)

/** Render 5: five minutes out, with a location and the random sound named. */
@Preview(showBackground = true)
@Composable
internal fun AlarmRingingScreenPreview() {
    MeetingMinderTheme(darkTheme = true) {
        AlarmRingingScreen(PreviewRinging, onDismiss = {}, onSnooze = {}, onOpenMeeting = {}, animated = false)
    }
}

/** A snoozed alarm back after the meeting started: a long title that wraps, no location, no sound named yet. */
@Preview(showBackground = true)
@Composable
internal fun AlarmRingingScreenStartedPreview() {
    MeetingMinderTheme(darkTheme = true) {
        AlarmRingingScreen(
            PreviewRinging.copy(
                title = "Quarterly planning with the platform, design and alarms teams",
                location = null,
                now = LocalTime.of(10, 2),
                minutesUntilStart = -2,
                soundName = null,
            ),
            onDismiss = {},
            onSnooze = {},
            onOpenMeeting = {},
            animated = false,
        )
    }
}

/** 1.5× font scale: everything still reachable (the screen scrolls rather than clipping). */
@Preview(showBackground = true, fontScale = 1.5f)
@Composable
internal fun AlarmRingingScreenLargeFontPreview() {
    MeetingMinderTheme(darkTheme = true) {
        AlarmRingingScreen(PreviewRinging, onDismiss = {}, onSnooze = {}, onOpenMeeting = {}, animated = false)
    }
}

package com.episode6.meetingminder.ui.day

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Time labels on the timeline, following the device's 12/24-hour setting and locale:
 * gutter labels read "8 AM" / "08:00", chip times "9:30" / "09:30" (no AM/PM on chips,
 * like render 2 — the gutter already says which half of the day you're in). The 24-hour
 * forms are zero-padded in both places so the gutter and the chips read alike.
 */
@Immutable
class TimelineTimeFormat(is24Hour: Boolean, locale: Locale) {
    private val hourFormatter = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h a", locale)
    private val timeFormatter = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm", locale)
    private val timeWithPeriodFormatter = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)

    fun hourLabel(hour: Int): String = LocalTime.of(hour, 0).format(hourFormatter)

    fun time(time: LocalTime): String = time.format(timeFormatter)

    /** Like [time], but with AM/PM — for standalone times away from the gutter, e.g. the "shared 8:12 AM" subtitle. */
    fun timeWithPeriod(time: LocalTime): String = time.format(timeWithPeriodFormatter)
}

@Composable
fun rememberTimelineTimeFormat(): TimelineTimeFormat {
    val context = LocalContext.current
    val locale = LocalResources.current.configuration.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(is24Hour, locale) { TimelineTimeFormat(is24Hour, locale) }
}

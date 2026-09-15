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
 * like render 2 — the gutter already says which half of the day you're in). A 12-hour
 * chip time that falls on the hour drops its ":00" for a one-letter period, "9a" / "12p",
 * so it takes as little of the chip as possible. The 24-hour forms are zero-padded in
 * both places so the gutter and the chips read alike.
 */
@Immutable
class TimelineTimeFormat(is24Hour: Boolean, private val locale: Locale) {
    private val hourFormatter = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h a", locale)
    private val timeFormatter = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm", locale)
    private val onTheHourFormatter = if (is24Hour) null else DateTimeFormatter.ofPattern("h", locale)
    private val periodFormatter = DateTimeFormatter.ofPattern("a", locale)
    private val timeWithPeriodFormatter = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)

    fun hourLabel(hour: Int): String = LocalTime.of(hour, 0).format(hourFormatter)

    fun time(time: LocalTime): String = when {
        // "9a" / "12p": the hour plus the first letter of the locale's AM/PM marker
        onTheHourFormatter != null && time.minute == 0 ->
            time.format(onTheHourFormatter) + time.format(periodFormatter).lowercase(locale).take(1)
        else -> time.format(timeFormatter)
    }

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

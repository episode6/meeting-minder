package com.episode6.meetingminder.ui.day

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import java.time.LocalTime
import java.util.Locale

class TimelineTimeFormatTest {

    @Test
    fun twelve_hour_gutterHasAmPm_chipsDoNot() {
        val format = TimelineTimeFormat(is24Hour = false, locale = Locale.US)

        assertThat(format.hourLabel(8)).isEqualTo("8 AM")
        assertThat(format.hourLabel(13)).isEqualTo("1 PM")
        assertThat(format.time(LocalTime.of(9, 30))).isEqualTo("9:30")
        assertThat(format.time(LocalTime.of(21, 5))).isEqualTo("9:05")
    }

    @Test
    fun twenty_four_hour_isZeroPaddedInTheGutterAndOnChips() {
        val format = TimelineTimeFormat(is24Hour = true, locale = Locale.US)

        assertThat(format.hourLabel(8)).isEqualTo("08:00")
        assertThat(format.hourLabel(21)).isEqualTo("21:00")
        assertThat(format.time(LocalTime.of(9, 30))).isEqualTo("09:30")
        assertThat(format.time(LocalTime.of(21, 5))).isEqualTo("21:05")
    }

    @Test
    fun twenty_four_hour_inANonEnglishLocale() {
        val format = TimelineTimeFormat(is24Hour = true, locale = Locale.GERMANY)

        assertThat(format.hourLabel(8)).isEqualTo("08:00")
        assertThat(format.time(LocalTime.of(17, 45))).isEqualTo("17:45")
    }
}

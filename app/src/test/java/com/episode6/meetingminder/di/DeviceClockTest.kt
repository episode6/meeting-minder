package com.episode6.meetingminder.di

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.After
import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone

class DeviceClockTest {

    private val original = TimeZone.getDefault()

    @After
    fun tearDown() {
        TimeZone.setDefault(original)
    }

    @Test
    fun zone_followsTheDefaultTimeZoneAfterTheClockWasCreated() {
        val clock = DeviceClock
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertThat(clock.zone).isEqualTo(ZoneId.of("America/New_York"))

        // what Android does to a running process on TIMEZONE_CHANGED
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))

        assertThat(clock.zone).isEqualTo(ZoneId.of("Asia/Tokyo"))
    }
}

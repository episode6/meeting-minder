package com.episode6.meetingminder.ui.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ForegroundLogTest {

    private val newYork = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 9, 30)
    private val eightAm = today.atTime(8, 0).atZone(newYork).toInstant()

    @Test
    fun theFirstForegroundEver_isTheFirstOfItsDay() {
        assertThat(firstForegroundOfDay(previous = null, now = eightAm, zone = newYork)).isEqualTo(today)
    }

    @Test
    fun aForegroundLateYesterday_makesThisMorningsTheFirst() {
        val lateLastNight = today.minusDays(1).atTime(23, 59).atZone(newYork).toInstant()

        assertThat(firstForegroundOfDay(lateLastNight, eightAm, newYork)).isEqualTo(today)
    }

    @Test
    fun aForegroundEarlierToday_meansThisIsntTheFirst() {
        val justAfterMidnight = today.atTime(0, 1).atZone(newYork).toInstant()

        assertThat(firstForegroundOfDay(justAfterMidnight, eightAm, newYork)).isNull()
    }

    @Test
    fun bothTimes_areReadInTheZoneTheDeviceIsInNow() {
        // 2 AM in New York was still 11 PM yesterday in Los Angeles, where the phone is now
        val twoAmNewYork = today.atTime(2, 0).atZone(newYork).toInstant()
        val losAngeles = ZoneId.of("America/Los_Angeles")

        assertThat(firstForegroundOfDay(twoAmNewYork, eightAm, newYork)).isNull()
        assertThat(firstForegroundOfDay(twoAmNewYork, eightAm, losAngeles)).isEqualTo(today)
    }

    @Test
    fun record_storesEachForeground_andReportsOnlyTheDaysFirst() = runTest {
        val settings = FakeSettingsRepository()
        val later = eightAm.plusSeconds(3_600)

        assertThat(ForegroundLog(settings, Clock.fixed(eightAm, newYork)).record()).isEqualTo(today)
        assertThat(settings.lastForegroundedAt).isEqualTo(eightAm)
        assertThat(ForegroundLog(settings, Clock.fixed(later, newYork)).record()).isNull()
        assertThat(settings.lastForegroundedAt).isEqualTo(later)
    }

    @Test
    fun record_afterAFewDaysAway_reportsToday() = runTest {
        val settings = FakeSettingsRepository().apply { lastForegroundedAt = Instant.parse("2026-09-26T15:00:00Z") }

        assertThat(ForegroundLog(settings, Clock.fixed(eightAm, ZoneOffset.UTC)).record()).isEqualTo(today)
    }
}

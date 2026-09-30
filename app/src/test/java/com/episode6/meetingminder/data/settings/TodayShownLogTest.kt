package com.episode6.meetingminder.data.settings

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

class TodayShownLogTest {

    private val newYork = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 9, 30)
    private val eightAm = today.atTime(8, 0).atZone(newYork).toInstant()
    private val settings = FakeSettingsRepository()

    private fun logAt(now: java.time.Instant, zone: ZoneId = newYork) = TodayShownLog(settings, Clock.fixed(now, zone))

    @Test
    fun sameDate_readsBothTimesInTheZoneTheDeviceIsInNow() {
        val lateLastNight = today.minusDays(1).atTime(23, 59).atZone(newYork).toInstant()
        val justAfterMidnight = today.atTime(0, 1).atZone(newYork).toInstant()
        // 2 AM in New York was still 11 PM yesterday in Los Angeles, where the phone is now
        val twoAmNewYork = today.atTime(2, 0).atZone(newYork).toInstant()

        assertThat(sameDate(null, eightAm, newYork)).isFalse()
        assertThat(sameDate(lateLastNight, eightAm, newYork)).isFalse()
        assertThat(sameDate(justAfterMidnight, eightAm, newYork)).isTrue()
        assertThat(sameDate(twoAmNewYork, eightAm, newYork)).isTrue()
        assertThat(sameDate(twoAmNewYork, eightAm, ZoneId.of("America/Los_Angeles"))).isFalse()
    }

    @Test
    fun startsOpenOnToday_untilItsPageSettles() = runTest {
        assertThat(logAt(eightAm).todayIfNotYetShown()).isEqualTo(today)
        // a start that showed another day (a link to tomorrow) spends nothing
        logAt(eightAm).onPageSettled(today.plusDays(1))
        assertThat(logAt(eightAm.plusSeconds(60)).todayIfNotYetShown()).isEqualTo(today)

        logAt(eightAm.plusSeconds(120)).onPageSettled(today)

        assertThat(settings.todayShownAt).isEqualTo(eightAm.plusSeconds(120))
        assertThat(logAt(eightAm.plusSeconds(3_600)).todayIfNotYetShown()).isNull()
    }

    @Test
    fun todaySettlingAgain_theSameDay_writesNothingMore() = runTest {
        logAt(eightAm).onPageSettled(today)
        logAt(eightAm.plusSeconds(600)).onPageSettled(today)

        assertThat(settings.todayShownWrites).isEqualTo(1)
        assertThat(settings.todayShownAt).isEqualTo(eightAm)
    }

    @Test
    fun yesterdaysShowing_leavesTodayToOpenOn() = runTest {
        logAt(eightAm.minusSeconds(86_400)).onPageSettled(today.minusDays(1))

        assertThat(logAt(eightAm).todayIfNotYetShown()).isEqualTo(today)
    }

    @Test
    fun aPageSettlingOnYesterdaysDate_afterMidnight_isntToday() = runTest {
        // the pager still anchored on yesterday reports yesterday's date
        logAt(eightAm).onPageSettled(today.minusDays(1))

        assertThat(settings.todayShownAt).isNull()
    }
}

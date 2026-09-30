package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.TodayShownLog
import com.episode6.meetingminder.store.SetSettledDate
import com.episode6.meetingminder.store.ShowDay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

class TodayShownSideEffectsTest {

    private val today = LocalDate.of(2026, 9, 30)
    private val now = today.atTime(8, 0).toInstant(ZoneOffset.UTC)
    private val settings = FakeSettingsRepository()

    private fun todayShown() = object : TodayShownSideEffects {}.todayShown(TodayShownLog(settings, Clock.fixed(now, ZoneOffset.UTC)))

    @Test
    fun thePagerSettlingOnToday_recordsTodayShown_andEmitsNothing() = runTest {
        val output = todayShown().output(SetSettledDate(today)).toList()

        assertThat(output).isEmpty()
        assertThat(settings.todayShownAt).isEqualTo(now)
    }

    @Test
    fun anotherDay_orAJumpNotYetSettled_recordsNothing() = runTest {
        todayShown().output(SetSettledDate(today.plusDays(1)), ShowDay(today)).toList()

        assertThat(settings.todayShownAt).isNull()
    }
}

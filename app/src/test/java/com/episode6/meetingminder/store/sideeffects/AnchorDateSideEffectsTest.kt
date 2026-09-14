package com.episode6.meetingminder.store.sideeffects

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.store.SetAnchorDate
import com.episode6.redux.Action
import com.episode6.redux.subscriberaware.SubscriberStatusChanged
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** [TestAppState]'s anchor is 2026-09-14; the clock runs on the test's virtual time. */
class AnchorDateSideEffectsTest {

    private val actions = MutableSharedFlow<Action>()

    /** A clock starting at [start] that moves with virtual time, in [zone]. */
    private fun TestScope.virtualClock(start: String, zone: ZoneId = ZoneOffset.UTC): Clock = object : Clock() {
        private val origin = Instant.parse(start)
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = origin.plusMillis(testScheduler.currentTime)
    }

    @Test
    fun whileSubscribed_midnightPassing_movesTheAnchorToTheNewDate() = runTest {
        val sideEffect = object : AnchorDateSideEffects {}.anchorDate(virtualClock("2026-09-14T23:59:30Z"))

        sideEffect.output(actions, state = TestAppState).test {
            actions.emit(SubscriberStatusChanged(true))
            // still the anchor's day: nothing to do
            expectNoEvents()

            advanceTimeBy(31_000)

            assertThat(awaitItem()).isEqualTo(SetAnchorDate(TestAppState.anchorDate.plusDays(1)))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun becomingVisibleOnALaterDay_movesTheAnchorAtOnce() = runTest {
        // the process outlived the night in the background
        val sideEffect = object : AnchorDateSideEffects {}.anchorDate(virtualClock("2026-09-16T07:00:00Z"))

        sideEffect.output(actions, state = TestAppState).test {
            actions.emit(SubscriberStatusChanged(true))

            assertThat(awaitItem()).isEqualTo(SetAnchorDate(TestAppState.anchorDate.plusDays(2)))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aTimezoneThatPutsTheDeviceInTheNextDay_movesTheAnchor() = runTest {
        // 20:00 UTC on the anchor's day is already the 15th in Tokyo
        val sideEffect = object : AnchorDateSideEffects {}.anchorDate(virtualClock("2026-09-14T20:00:00Z", ZoneId.of("Asia/Tokyo")))

        sideEffect.output(actions, state = TestAppState).test {
            actions.emit(SubscriberStatusChanged(true))

            assertThat(awaitItem()).isEqualTo(SetAnchorDate(TestAppState.anchorDate.plusDays(1)))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun withoutSubscribers_nothingTicks() = runTest {
        val sideEffect = object : AnchorDateSideEffects {}.anchorDate(virtualClock("2026-09-14T23:59:30Z"))

        sideEffect.output(actions, state = TestAppState).test {
            actions.emit(SubscriberStatusChanged(true))
            actions.emit(SubscriberStatusChanged(false))

            advanceTimeBy(120_000)

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}

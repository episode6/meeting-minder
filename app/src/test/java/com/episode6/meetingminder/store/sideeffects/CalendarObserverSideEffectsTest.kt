package com.episode6.meetingminder.store.sideeffects

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.data.calendar.FakeCalendarChangeSource
import com.episode6.meetingminder.permissions.PermissionState
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.LoadDay
import com.episode6.meetingminder.store.SetPermissions
import com.episode6.redux.Action
import com.episode6.redux.subscriberaware.SubscriberStatusChanged
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

class CalendarObserverSideEffectsTest {

    private val changeSource = FakeCalendarChangeSource()
    private val sideEffect = object : CalendarObserverSideEffects {}.calendarObserver(changeSource)
    private val actions = MutableSharedFlow<Action>()

    private suspend fun awaitObservers(count: Int) = changeSource.observers.first { it == count }

    @Test
    fun whileSubscribedAndGranted_refreshesOnce_thenOnceperDebouncedBurst() = runTest {
        sideEffect.output(actions, state = CalendarGrantedAppState).test {
            actions.emit(SubscriberStatusChanged(true))

            // nothing observed while the UI was away, so becoming active refreshes straight away
            assertThat(awaitItem()).isEqualTo(CalendarContentChanged)
            awaitObservers(1)

            repeat(3) { changeSource.notifyChange() }

            assertThat(awaitItem()).isEqualTo(CalendarContentChanged)
            expectNoEvents()

            changeSource.notifyChange()
            assertThat(awaitItem()).isEqualTo(CalendarContentChanged)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun losingSubscribers_unregistersTheObserver() = runTest {
        sideEffect.output(actions, state = CalendarGrantedAppState).test {
            actions.emit(SubscriberStatusChanged(true))
            assertThat(awaitItem()).isEqualTo(CalendarContentChanged)
            awaitObservers(1)

            actions.emit(SubscriberStatusChanged(false))

            awaitObservers(0)
            changeSource.notifyChange()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun withoutCalendarAccess_nothingIsObserved_untilItIsGranted() = runTest {
        sideEffect.output(actions, state = TestAppState).test {
            actions.emit(SubscriberStatusChanged(true))
            expectNoEvents()
            assertThat(changeSource.observers.value).isEqualTo(0)

            actions.emit(SetPermissions(PermissionState(calendarGranted = true)))

            assertThat(awaitItem()).isEqualTo(CalendarContentChanged)
            awaitObservers(1)

            actions.emit(SetPermissions(PermissionState(calendarGranted = false)))

            awaitObservers(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun unrelatedActions_neitherRegisterNorRefresh() = runTest {
        sideEffect.output(actions, state = CalendarGrantedAppState).test {
            actions.emit(LoadDay(LocalDate.of(2026, 9, 14)))
            actions.emit(CalendarContentChanged)

            expectNoEvents()
            assertThat(changeSource.observers.value).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

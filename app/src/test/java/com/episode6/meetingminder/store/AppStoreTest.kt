package com.episode6.meetingminder.store

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.store.sideeffects.ActionLogSideEffects
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import com.episode6.redux.testsupport.runStoreTest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import org.junit.Test
import java.time.LocalDate

/** The production store wiring ([createAppStore]): reducer + side-effect middleware. */
class AppStoreTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val initial = AppState(anchorDate = today)

    /** Stands in for a real async request: a side effect turns it into a state update. */
    private data class JumpAhead(val days: Long) : Action

    private val jumpAhead = SideEffect<AppState> {
        actions.filterIsInstance<JumpAhead>().map { SetSettledDate(currentState().settledDate.plusDays(it.days)) }
    }

    private val sideEffects = setOf(jumpAhead, object : ActionLogSideEffects {}.actionLog())

    @Test
    fun updateStateActions_reachTheReducer() = runStoreTest({ createAppStore(this, initial, sideEffects) }) { store ->
        store.test {
            assertThat(awaitItem()).isEqualTo(initial)

            store.dispatch(SetSettledDate(today.plusDays(1)))

            assertThat(awaitItem().settledDate).isEqualTo(today.plusDays(1))
        }
    }

    @Test
    fun contributedSideEffects_allReceiveActions_andTheirOutputIsDispatched() =
        runStoreTest({ createAppStore(this, initial, sideEffects) }) { store ->
            store.test {
                assertThat(awaitItem()).isEqualTo(initial)

                // the middleware only relays once every effect is subscribed, so this also
                // proves the observe-only log effect doesn't starve the others
                store.dispatch(JumpAhead(days = 3))

                assertThat(awaitItem().settledDate).isEqualTo(today.plusDays(3))
            }
        }
}

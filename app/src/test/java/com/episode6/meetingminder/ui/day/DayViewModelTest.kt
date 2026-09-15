package com.episode6.meetingminder.ui.day

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.prop
import com.episode6.meetingminder.R
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.UiMessage
import com.episode6.meetingminder.store.createAppStore
import com.episode6.redux.testsupport.runStoreTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModelTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val tomorrow = today.plusDays(1)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun state_followsTheSettledDate() {
        assertThat(AppState(anchorDate = today).toDayUiState()).isEqualTo(DayUiState(today, isToday = true))
        assertThat(AppState(anchorDate = today, settledDate = tomorrow).toDayUiState())
            .isEqualTo(DayUiState(tomorrow, isToday = false))
    }

    @Test
    fun onTodayClick_settlesBackOnTheAnchorDate() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today, settledDate = tomorrow), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store)
        viewModel.state.test {
            assertThat(awaitItem()).isEqualTo(DayUiState(tomorrow, isToday = false))

            viewModel.onTodayClick()

            assertThat(awaitItem()).isEqualTo(DayUiState(today, isToday = true))
        }
    }

    @Test
    fun messages_emitEachMessageOnce_andShowingOneClearsIt() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store)
        val message = UiMessage(id = 1, text = 1)
        viewModel.messages.test {
            store.dispatch(ShowMessage(message))
            assertThat(awaitItem()).isEqualTo(message)

            viewModel.onMessageShown(message)

            expectNoEvents()
            assertThat(store.state.transientMessage).isNull()
        }
    }

    @Test
    fun onCheckForUpdatesFailed_showsTheNoBrowserMessage() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val viewModel = DayViewModel(store)
        viewModel.messages.test {
            viewModel.onCheckForUpdatesFailed()

            assertThat(awaitItem()).prop(UiMessage::text).isEqualTo(R.string.check_for_updates_no_browser)
        }
    }
}

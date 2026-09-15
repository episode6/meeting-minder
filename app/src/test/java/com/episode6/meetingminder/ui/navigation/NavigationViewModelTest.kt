package com.episode6.meetingminder.ui.navigation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.permissions.PermissionChecker
import com.episode6.meetingminder.permissions.PermissionState
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.createAppStore
import com.episode6.meetingminder.store.sideeffects.PermissionsSideEffects
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
class NavigationViewModelTest {

    private val today = LocalDate.of(2026, 9, 14)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun calendarGranted_seededFromInitialStoreState() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today, permissions = PermissionState(calendarGranted = true)), emptySet()) },
    ) { store ->
        val viewModel = NavigationViewModel(store)
        assertThat(viewModel.calendarGranted.value).isEqualTo(true)
    }

    @Test
    fun onResumed_reRunsTheStoresPermissionCheck() = runStoreTest(
        {
            val checker = object : PermissionChecker {
                override fun currentState() = PermissionState(calendarGranted = true)
            }
            val sideEffects = setOf(object : PermissionsSideEffects {}.permissions(checker))
            createAppStore(this, AppState(anchorDate = today), sideEffects)
        },
    ) { store ->
        val viewModel = NavigationViewModel(store)
        viewModel.calendarGranted.test {
            assertThat(awaitItem()).isEqualTo(false)

            viewModel.onResumed()

            assertThat(awaitItem()).isEqualTo(true)
        }
    }
}

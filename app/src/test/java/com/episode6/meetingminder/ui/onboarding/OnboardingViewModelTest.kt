package com.episode6.meetingminder.ui.onboarding

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
class OnboardingViewModelTest {

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
    fun state_followsPermissions() {
        assertThat(AppState(anchorDate = today).toOnboardingUiState())
            .isEqualTo(OnboardingUiState(calendarGranted = false, notificationsGranted = false, exactAlarmsGranted = false))
        val granted = PermissionState(calendarGranted = true, notificationsGranted = true, exactAlarmsGranted = false, fullScreenIntentGranted = true)
        assertThat(AppState(anchorDate = today, permissions = granted).toOnboardingUiState())
            .isEqualTo(
                OnboardingUiState(calendarGranted = true, notificationsGranted = true, exactAlarmsGranted = false, fullScreenAlarmsGranted = true),
            )
    }

    @Test
    fun canContinue_onlyOnceEveryRequiredRowIsGranted() {
        assertThat(OnboardingUiState(calendarGranted = false).canContinue).isEqualTo(false)
        assertThat(OnboardingUiState(calendarGranted = true).canContinue).isEqualTo(false)
        assertThat(OnboardingUiState(calendarGranted = true, notificationsGranted = true).canContinue).isEqualTo(false)
        assertThat(OnboardingUiState(calendarGranted = true, notificationsGranted = true, exactAlarmsGranted = true).canContinue)
            .isEqualTo(false)
        assertThat(
            OnboardingUiState(calendarGranted = true, notificationsGranted = true, exactAlarmsGranted = true, fullScreenAlarmsGranted = true)
                .canContinue,
        ).isEqualTo(true)
    }

    @Test
    fun onPermissionsMaybeChanged_reRunsTheStoresPermissionCheck() = runStoreTest(
        {
            val checker = object : PermissionChecker {
                override fun currentState() = PermissionState(calendarGranted = true)
            }
            val sideEffects = setOf(object : PermissionsSideEffects {}.permissions(checker))
            createAppStore(this, AppState(anchorDate = today), sideEffects)
        },
    ) { store ->
        val viewModel = OnboardingViewModel(store)
        viewModel.state.test {
            assertThat(awaitItem()).isEqualTo(OnboardingUiState(calendarGranted = false))

            viewModel.onPermissionsMaybeChanged()

            assertThat(awaitItem()).isEqualTo(OnboardingUiState(calendarGranted = true, notificationsGranted = false, exactAlarmsGranted = false))
        }
    }
}

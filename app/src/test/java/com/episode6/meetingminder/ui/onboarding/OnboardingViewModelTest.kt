package com.episode6.meetingminder.ui.onboarding

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.permissions.PermissionChecker
import com.episode6.meetingminder.permissions.PermissionState
import com.episode6.meetingminder.permissions.SleepyManufacturer
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
    fun state_carriesTheOptionalRowsAndThePhoneMaker() {
        val permissions = PermissionState(calendarGranted = true, ignoringBatteryOptimizations = true, backgroundRestricted = true)

        assertThat(AppState(anchorDate = today, permissions = permissions).toOnboardingUiState(SleepyManufacturer.Samsung))
            .isEqualTo(
                OnboardingUiState(
                    calendarGranted = true,
                    batteryOptimizationIgnored = true,
                    backgroundRestricted = true,
                    sleepyManufacturer = SleepyManufacturer.Samsung,
                ),
            )
    }

    @Test
    fun rows_offerBatteryOptimisationOnlyWhileNotIgnored_andTheRestrictedWarningOnlyWhileRestricted() {
        val required = listOf(OnboardingRow.Calendar, OnboardingRow.Notifications, OnboardingRow.ExactAlarms, OnboardingRow.FullScreenAlarms)

        assertThat(OnboardingUiState(calendarGranted = false).rows).isEqualTo(required + OnboardingRow.BatteryOptimization)
        assertThat(OnboardingUiState(calendarGranted = false, batteryOptimizationIgnored = true).rows).isEqualTo(required)
        assertThat(OnboardingUiState(calendarGranted = false, batteryOptimizationIgnored = true, backgroundRestricted = true).rows)
            .isEqualTo(required + OnboardingRow.BackgroundRestricted)
    }

    @Test
    fun optionalRows_neverGateContinue() {
        val allRequired = OnboardingUiState(calendarGranted = true, notificationsGranted = true, exactAlarmsGranted = true, fullScreenAlarmsGranted = true)

        assertThat(allRequired.canContinue).isEqualTo(true)
        assertThat(allRequired.copy(backgroundRestricted = true).canContinue).isEqualTo(true)
        assertThat(allRequired.granted(OnboardingRow.BackgroundRestricted)).isEqualTo(true)
        assertThat(allRequired.copy(backgroundRestricted = true).granted(OnboardingRow.BackgroundRestricted)).isEqualTo(false)
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
    fun onPermissionRequested_isRemembered_forTheNextRequestToReadBack() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val settings = FakeSettingsRepository()
        val viewModel = OnboardingViewModel(store, settings)
        viewModel.requestedPermissions.test {
            assertThat(awaitItem()).isEqualTo(emptySet())

            viewModel.onPermissionRequested("android.permission.READ_CALENDAR")

            assertThat(awaitItem()).isEqualTo(setOf("android.permission.READ_CALENDAR"))
            assertThat(settings.requestedPermissions.value).isEqualTo(setOf("android.permission.READ_CALENDAR"))
        }
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
        val viewModel = OnboardingViewModel(store, FakeSettingsRepository())
        viewModel.state.test {
            assertThat(awaitItem()).isEqualTo(OnboardingUiState(calendarGranted = false))

            viewModel.onPermissionsMaybeChanged()

            assertThat(awaitItem()).isEqualTo(OnboardingUiState(calendarGranted = true, notificationsGranted = false, exactAlarmsGranted = false))
        }
    }
}

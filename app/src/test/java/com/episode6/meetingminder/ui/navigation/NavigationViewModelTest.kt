package com.episode6.meetingminder.ui.navigation

import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.MutableSharedFlow
import com.episode6.redux.sideeffects.SideEffect
import com.episode6.redux.Action
import com.episode6.meetingminder.store.ShareDay
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

    private val allGranted = PermissionState(
        calendarGranted = true,
        notificationsGranted = true,
        exactAlarmsGranted = true,
        fullScreenIntentGranted = true,
    )

    @Test
    fun requiredPermissionsGranted_needsTheFullScreenAlarmsRowToo() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today, permissions = allGranted.copy(fullScreenIntentGranted = false)), emptySet()) },
    ) { store ->
        assertThat(NavigationViewModel(store).requiredPermissionsGranted.value).isEqualTo(false)
    }

    @Test
    fun requiredPermissionsGranted_seededFromInitialStoreState() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today, permissions = allGranted), emptySet()) },
    ) { store ->
        val viewModel = NavigationViewModel(store)
        assertThat(viewModel.requiredPermissionsGranted.value).isEqualTo(true)
    }

    @Test
    fun requiredPermissionsGranted_isFalseWhileAnyRequiredRowIsMissing() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today, permissions = allGranted.copy(notificationsGranted = false)), emptySet()) },
    ) { store ->
        assertThat(NavigationViewModel(store).requiredPermissionsGranted.value).isEqualTo(false)
    }

    @Test
    fun onResumed_reRunsTheStoresPermissionCheck() = runStoreTest(
        {
            val checker = object : PermissionChecker {
                override fun currentState() = allGranted
            }
            val sideEffects = setOf(object : PermissionsSideEffects {}.permissions(checker))
            createAppStore(this, AppState(anchorDate = today), sideEffects)
        },
    ) { store ->
        val viewModel = NavigationViewModel(store)
        viewModel.requiredPermissionsGranted.test {
            assertThat(awaitItem()).isEqualTo(false)

            viewModel.onResumed()

            assertThat(awaitItem()).isEqualTo(true)
        }
    }

    @Test
    fun onDeepLink_share_dispatchesTheShareForThatDay() {
        val shares = MutableSharedFlow<Action>(replay = 10)
        val recordShares = SideEffect<AppState> { actions.onEach { if (it is ShareDay) shares.emit(it) }.filter { false } }
        runStoreTest({ createAppStore(this, AppState(anchorDate = today, permissions = allGranted), setOf(recordShares)) }) { store ->
            val viewModel = NavigationViewModel(store)

            assertThat(viewModel.onDeepLink(DeepLink.Share(today.plusDays(1)))).isEqualTo(true)

            assertThat(shares.first()).isEqualTo(ShareDay(today.plusDays(1)))
        }
    }

    @Test
    fun onDeepLink_day_onlyAsksForTheDayToBeShown() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today, permissions = allGranted), emptySet()) },
    ) { store ->
        assertThat(NavigationViewModel(store).onDeepLink(DeepLink.Day(today))).isEqualTo(true)
    }

    @Test
    fun onDeepLink_whileARequiredGrantIsMissing_isDropped() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today, permissions = allGranted.copy(calendarGranted = false)), emptySet()) },
    ) { store ->
        assertThat(NavigationViewModel(store).onDeepLink(DeepLink.Share(today))).isEqualTo(false)
    }
}

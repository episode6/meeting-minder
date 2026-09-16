package com.episode6.meetingminder.ui.settings

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.Settings
import com.episode6.meetingminder.data.settings.BusySync
import com.episode6.meetingminder.data.settings.AlarmSoundPool
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.BusySyncSettingChanged
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.TestAlarm
import com.episode6.meetingminder.store.createAppStore
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import com.episode6.redux.testsupport.runStoreTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val today = LocalDate.of(2026, 9, 14)

    private val personal = CalendarInfo(
        id = 1,
        accountName = "me@personal.com",
        accountType = "com.google",
        displayName = "Personal",
        color = 0,
        visible = true,
        syncEvents = true,
        ownerAccount = "me@personal.com",
        isPrimary = true,
        accessLevel = 700,
        canOrganizerRespond = false,
    )
    private val hidden = personal.copy(id = 2, displayName = "Hidden", visible = false)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun state_combinesSettingsWithEveryCalendarAsARow() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today, calendars = listOf(personal, hidden)), emptySet()) },
    ) { store ->
        val settings = FakeSettingsRepository(Settings(leadTime = Duration.ofMinutes(10), calendarOverrides = mapOf(2L to true)))
        val viewModel = SettingsViewModel(store, settings)

        viewModel.state.test {
            val state = awaitItem()
            assertThat(state.leadTime).isEqualTo(Duration.ofMinutes(10))
            assertThat(state.calendars).isEqualTo(
                listOf(CalendarRow(personal, included = true), CalendarRow(hidden, included = true)),
            )
        }
    }

    @Test
    fun onLeadTimeSelected_writesThroughToSettings() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val settings = FakeSettingsRepository()
        val viewModel = SettingsViewModel(store, settings)

        viewModel.onLeadTimeSelected(Duration.ofMinutes(15))

        assertThat(settings.settings.value.leadTime).isEqualTo(Duration.ofMinutes(15))
    }

    @Test
    fun onCalendarToggle_setsTheOverride_whenItDisagreesWithTheProvider_andRequestsAReload() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), setOf(recordCalendarContentChanged)) },
    ) { store ->
        val settings = FakeSettingsRepository()
        val viewModel = SettingsViewModel(store, settings)

        viewModel.onCalendarToggle(hidden, included = true)

        assertThat(settings.settings.value.calendarOverrides).isEqualTo(mapOf(2L to true))
        assertThat(reloads.first()).isEqualTo(CalendarContentChanged)
    }

    @Test
    fun onCalendarToggle_clearsTheOverride_whenToggledBackToWhatTheProviderSays() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), setOf(recordCalendarContentChanged)) },
    ) { store ->
        val settings = FakeSettingsRepository(Settings(calendarOverrides = mapOf(2L to true)))
        val viewModel = SettingsViewModel(store, settings)

        viewModel.onCalendarToggle(hidden, included = false)

        assertThat(settings.settings.value.calendarOverrides).isEqualTo(emptyMap())
        assertThat(reloads.first()).isEqualTo(CalendarContentChanged)
    }

    @Test
    fun onShowDeclinedToggle_writesThroughToSettings_andRequestsAReload() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), setOf(recordCalendarContentChanged)) },
    ) { store ->
        val settings = FakeSettingsRepository()
        val viewModel = SettingsViewModel(store, settings)

        viewModel.onShowDeclinedToggle(false)

        assertThat(settings.settings.value.showDeclined).isEqualTo(false)
        assertThat(reloads.first()).isEqualTo(CalendarContentChanged)
    }

    @Test
    fun onSoundPoolSelected_writesThroughToSettings() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), emptySet()) },
    ) { store ->
        val settings = FakeSettingsRepository()
        val viewModel = SettingsViewModel(store, settings)

        viewModel.onSoundPoolSelected(AlarmSoundPool.BUNDLED_ONLY)

        assertThat(settings.settings.value.soundPool).isEqualTo(AlarmSoundPool.BUNDLED_ONLY)
    }

    @Test
    fun onTestAlarmClick_dispatchesTestAlarm() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), setOf(recordTestAlarm)) },
    ) { store ->
        val viewModel = SettingsViewModel(store, FakeSettingsRepository())

        viewModel.onTestAlarmClick()

        assertThat(testAlarms.first()).isEqualTo(TestAlarm)
    }

    @Test
    fun onBusySyncToggle_on_withNoCalendarChosen_appliesTheFamilyDefault_andDispatchesTheChange() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today, calendars = listOf(personal.copy(displayName = "Family"))), setOf(recordBusySyncSettingChanged)) },
    ) { store ->
        val settings = FakeSettingsRepository()
        val viewModel = SettingsViewModel(store, settings)

        viewModel.onBusySyncToggle(true)

        assertThat(settings.settings.value.busySync.enabled).isEqualTo(true)
        assertThat(settings.settings.value.busySync.calendarId).isEqualTo(personal.id)
        assertThat(busySyncChanges.first()).isEqualTo(BusySyncSettingChanged(previousCalendarId = null, enabledNow = true))
    }

    @Test
    fun onBusySyncToggle_on_withACalendarAlreadyChosen_leavesItAlone() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today, calendars = listOf(personal.copy(displayName = "Family"))), setOf(recordBusySyncSettingChanged)) },
    ) { store ->
        val settings = FakeSettingsRepository(Settings(busySync = BusySync(calendarId = 9L)))
        val viewModel = SettingsViewModel(store, settings)

        viewModel.onBusySyncToggle(true)

        assertThat(settings.settings.value.busySync.calendarId).isEqualTo(9L)
        assertThat(busySyncChanges.first()).isEqualTo(BusySyncSettingChanged(previousCalendarId = 9L, enabledNow = true))
    }

    @Test
    fun onBusySyncToggle_off_dispatchesTheChangeWithTheStoredCalendarId() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), setOf(recordBusySyncSettingChanged)) },
    ) { store ->
        val settings = FakeSettingsRepository(Settings(busySync = BusySync(enabled = true, calendarId = 9L)))
        val viewModel = SettingsViewModel(store, settings)

        viewModel.onBusySyncToggle(false)

        assertThat(settings.settings.value.busySync.enabled).isEqualTo(false)
        assertThat(busySyncChanges.first()).isEqualTo(BusySyncSettingChanged(previousCalendarId = 9L, enabledNow = false))
    }

    @Test
    fun onBusyCalendarSelected_writesTheIdAndDispatchesTheChange() = runStoreTest(
        { createAppStore(this, AppState(anchorDate = today), setOf(recordBusySyncSettingChanged)) },
    ) { store ->
        val settings = FakeSettingsRepository(Settings(busySync = BusySync(enabled = true, calendarId = 1L)))
        val viewModel = SettingsViewModel(store, settings)

        viewModel.onBusyCalendarSelected(hidden)

        assertThat(settings.settings.value.busySync.calendarId).isEqualTo(hidden.id)
        assertThat(busySyncChanges.first()).isEqualTo(BusySyncSettingChanged(previousCalendarId = 1L, enabledNow = true))
    }

    private val reloads = MutableSharedFlow<Action>(replay = 10)
    private val recordCalendarContentChanged = SideEffect<AppState> {
        actions.onEach { if (it is CalendarContentChanged) reloads.emit(it) }.filter { false }
    }

    private val testAlarms = MutableSharedFlow<Action>(replay = 10)
    private val recordTestAlarm = SideEffect<AppState> {
        actions.onEach { if (it is TestAlarm) testAlarms.emit(it) }.filter { false }
    }

    private val busySyncChanges = MutableSharedFlow<Action>(replay = 10)
    private val recordBusySyncSettingChanged = SideEffect<AppState> {
        actions.onEach { if (it is BusySyncSettingChanged) busySyncChanges.emit(it) }.filter { false }
    }
}

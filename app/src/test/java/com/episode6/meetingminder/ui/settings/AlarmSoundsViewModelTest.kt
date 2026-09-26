package com.episode6.meetingminder.ui.settings

import androidx.compose.ui.state.ToggleableState
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.episode6.meetingminder.alarm.AlarmSound
import com.episode6.meetingminder.alarm.FakeSoundCatalogSource
import com.episode6.meetingminder.alarm.SoundCatalog
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Settings → Alarm sounds (TODO.md §4.4): the catalog's rows against `Settings.disabledAlarmSounds`, and the two writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmSoundsViewModelTest {

    private val cesium = AlarmSound.System("content://media/internal/audio/media/1", "Cesium")
    private val krypton = AlarmSound.System("content://media/internal/audio/media/2", "Krypton")
    private val argon = AlarmSound.Bundled("Argon")
    private val carbon = AlarmSound.Bundled("Carbon")
    private val catalog = SoundCatalog(system = listOf(cesium, krypton), bundled = listOf(argon, carbon))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun state_listsEveryCatalogSound_checkedUnlessDisabled() = runTest {
        val settings = FakeSettingsRepository(Settings(disabledAlarmSounds = setOf(krypton.id, AlarmSound.SIREN_ID)))
        val viewModel = AlarmSoundsViewModel(settings, FakeSoundCatalogSource(catalog))

        viewModel.state.test {
            val state = awaitItem()
            assertThat(state.loaded).isTrue()
            assertThat(state.system).isEqualTo(
                listOf(SoundChoice(cesium.id, "Cesium", enabled = true), SoundChoice(krypton.id, "Krypton", enabled = false)),
            )
            assertThat(state.bundled).isEqualTo(
                listOf(SoundChoice(argon.id, "Argon", enabled = true), SoundChoice(carbon.id, "Carbon", enabled = true)),
            )
            assertThat(state.sirenEnabled).isFalse()
        }
    }

    @Test
    fun onSoundToggle_writesThatOneSound() = runTest {
        val settings = FakeSettingsRepository()
        val viewModel = AlarmSoundsViewModel(settings, FakeSoundCatalogSource(catalog))

        viewModel.onSoundToggle(argon.id, enabled = false)
        assertThat(settings.settings.value.disabledAlarmSounds).isEqualTo(setOf(argon.id))

        viewModel.onSoundToggle(argon.id, enabled = true)
        assertThat(settings.settings.value.disabledAlarmSounds).isEqualTo(emptySet())
    }

    /** A group's checkbox writes every id of the group, from the loaded catalog, and leaves the other groups alone. */
    @Test
    fun onGroupToggle_writesTheWholeGroup_andNothingElse() = runTest {
        val settings = FakeSettingsRepository(Settings(disabledAlarmSounds = setOf(cesium.id)))
        val viewModel = AlarmSoundsViewModel(settings, FakeSoundCatalogSource(catalog))

        viewModel.onGroupToggle(AlarmSoundGroup.BUNDLED, enabled = false)
        assertThat(settings.settings.value.disabledAlarmSounds).isEqualTo(setOf(cesium.id, argon.id, carbon.id))

        viewModel.onGroupToggle(AlarmSoundGroup.SIREN, enabled = false)
        assertThat(settings.settings.value.disabledAlarmSounds).isEqualTo(setOf(cesium.id, argon.id, carbon.id, AlarmSound.SIREN_ID))

        viewModel.onGroupToggle(AlarmSoundGroup.SYSTEM, enabled = true)
        assertThat(settings.settings.value.disabledAlarmSounds).isEqualTo(setOf(argon.id, carbon.id, AlarmSound.SIREN_ID))
    }

    @Test
    fun catalog_isReadOncePerViewModel() = runTest {
        val source = FakeSoundCatalogSource(catalog)
        val viewModel = AlarmSoundsViewModel(FakeSettingsRepository(), source)

        viewModel.state.test { awaitItem() }
        viewModel.onGroupToggle(AlarmSoundGroup.SYSTEM, enabled = false)
        viewModel.state.test { awaitItem() }

        assertThat(source.loads).isEqualTo(1)
    }

    @Test
    fun groupState_isOnOffOrIndeterminate_byItsRows() {
        val on = SoundChoice("a", "A", enabled = true)
        val off = SoundChoice("b", "B", enabled = false)

        assertThat(groupState(listOf(on, on))).isEqualTo(ToggleableState.On)
        assertThat(groupState(listOf(off, off))).isEqualTo(ToggleableState.Off)
        assertThat(groupState(listOf(on, off))).isEqualTo(ToggleableState.Indeterminate)
        assertThat(groupState(emptyList())).isEqualTo(ToggleableState.Off)
    }
}

package com.episode6.meetingminder.ui.settings

import androidx.compose.ui.state.ToggleableState
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.single
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.episode6.meetingminder.alarm.AlarmSound
import com.episode6.meetingminder.alarm.FakeSoundCatalogSource
import com.episode6.meetingminder.alarm.FakeSoundPreviewer
import com.episode6.meetingminder.alarm.SoundCatalog
import com.episode6.meetingminder.alarm.SoundCatalogSource
import com.episode6.meetingminder.data.settings.FakeSettingsRepository
import com.episode6.meetingminder.data.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Settings → Alarm sounds (TODO.md §4.4): the catalog's rows against `Settings.disabledAlarmSounds`, the two writes, and the previews. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmSoundsViewModelTest {

    private val cesium = AlarmSound.System("content://media/internal/audio/media/1", "Cesium")
    private val krypton = AlarmSound.System("content://media/internal/audio/media/2", "Krypton")
    private val argon = AlarmSound.Bundled("Argon")
    private val carbon = AlarmSound.Bundled("Carbon")
    private val catalog = SoundCatalog(system = listOf(cesium, krypton), bundled = listOf(argon, carbon))
    private val previewer = FakeSoundPreviewer()

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
        val viewModel = AlarmSoundsViewModel(settings, FakeSoundCatalogSource(catalog), previewer)

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
        val viewModel = AlarmSoundsViewModel(settings, FakeSoundCatalogSource(catalog), previewer)

        viewModel.onSoundToggle(argon.id, enabled = false)
        assertThat(settings.settings.value.disabledAlarmSounds).isEqualTo(setOf(argon.id))

        viewModel.onSoundToggle(argon.id, enabled = true)
        assertThat(settings.settings.value.disabledAlarmSounds).isEqualTo(emptySet())
    }

    /** A group's checkbox writes every id of the group, from the loaded catalog, and leaves the other groups alone. */
    @Test
    fun onGroupToggle_writesTheWholeGroup_andNothingElse() = runTest {
        val settings = FakeSettingsRepository(Settings(disabledAlarmSounds = setOf(cesium.id)))
        val viewModel = AlarmSoundsViewModel(settings, FakeSoundCatalogSource(catalog), previewer)

        viewModel.onGroupToggle(AlarmSoundGroup.BUNDLED, enabled = false)
        assertThat(settings.settings.value.disabledAlarmSounds).isEqualTo(setOf(cesium.id, argon.id, carbon.id))

        viewModel.onGroupToggle(AlarmSoundGroup.SIREN, enabled = false)
        assertThat(settings.settings.value.disabledAlarmSounds).isEqualTo(setOf(cesium.id, argon.id, carbon.id, AlarmSound.SIREN_ID))

        viewModel.onGroupToggle(AlarmSoundGroup.SYSTEM, enabled = true)
        assertThat(settings.settings.value.disabledAlarmSounds).isEqualTo(setOf(argon.id, carbon.id, AlarmSound.SIREN_ID))
    }

    /** A ringtone gone from the device can't be reached by any checkbox, so its id is dropped; catalog ids and the siren stay. */
    @Test
    fun onLoad_prunesIdsTheCatalogNoLongerHas_andLeavesTheRestAlone() = runTest {
        val gone = "system:content://media/internal/audio/media/99"
        val settings = FakeSettingsRepository(Settings(disabledAlarmSounds = setOf(gone, krypton.id, AlarmSound.SIREN_ID)))

        AlarmSoundsViewModel(settings, FakeSoundCatalogSource(catalog), previewer)

        assertThat(settings.settings.value.disabledAlarmSounds).isEqualTo(setOf(krypton.id, AlarmSound.SIREN_ID))
    }

    @Test
    fun whenTheCatalogCantBeRead_thePageShowsTheSirenAlone_andPrunesNothingItCantSee() = runTest {
        val settings = FakeSettingsRepository(Settings(disabledAlarmSounds = setOf(krypton.id)))
        val viewModel = AlarmSoundsViewModel(settings, object : SoundCatalogSource {
            override suspend fun load(): SoundCatalog = throw IllegalStateException("no resources")
        }, previewer)

        viewModel.state.test {
            val state = awaitItem()
            assertThat(state.loaded).isTrue()
            assertThat(state.system).isEqualTo(emptyList())
            assertThat(state.bundled).isEqualTo(emptyList())
            assertThat(state.sirenEnabled).isTrue()
        }
        assertThat(settings.settings.value.disabledAlarmSounds).isEqualTo(setOf(krypton.id))
    }

    @Test
    fun catalog_isReadOncePerViewModel() = runTest {
        val source = FakeSoundCatalogSource(catalog)
        val viewModel = AlarmSoundsViewModel(FakeSettingsRepository(), source, previewer)

        viewModel.state.test { awaitItem() }
        viewModel.onGroupToggle(AlarmSoundGroup.SYSTEM, enabled = false)
        viewModel.state.test { awaitItem() }

        assertThat(source.loads).isEqualTo(1)
    }

    @Test
    fun onSoundPreview_playsThatSound_andShowsItPlaying_untilItEnds() = runTest {
        val viewModel = AlarmSoundsViewModel(FakeSettingsRepository(), FakeSoundCatalogSource(catalog), previewer)

        viewModel.state.test {
            assertThat(awaitItem().previewing).isNull()

            viewModel.onSoundPreview(krypton.id)
            assertThat(awaitItem().previewing).isEqualTo(krypton.id)
            assertThat(previewer.played).containsExactly(krypton)

            previewer.finish()
            assertThat(awaitItem().previewing).isNull()
        }
        assertThat(previewer.stopped).isEmpty()
    }

    @Test
    fun onSoundPreview_ofTheSoundPlaying_stopsIt() = runTest {
        val viewModel = AlarmSoundsViewModel(FakeSettingsRepository(), FakeSoundCatalogSource(catalog), previewer)
        collectState(viewModel)

        viewModel.onSoundPreview(argon.id)
        viewModel.onSoundPreview(argon.id)

        assertThat(previewer.stopped).containsExactly(argon)
        assertThat(previewer.playing).isFalse()
        assertThat(viewModel.state.value.previewing).isNull()
    }

    /** One sound at a time: the one playing is stopped before the next starts. */
    @Test
    fun onSoundPreview_ofAnotherSound_stopsTheFirst_andPlaysIt() = runTest {
        val viewModel = AlarmSoundsViewModel(FakeSettingsRepository(), FakeSoundCatalogSource(catalog), previewer)

        viewModel.state.test {
            awaitItem()
            viewModel.onSoundPreview(cesium.id)
            assertThat(awaitItem().previewing).isEqualTo(cesium.id)
            viewModel.onSoundPreview(carbon.id)
            assertThat(awaitItem().previewing).isEqualTo(carbon.id)
            expectNoEvents()
        }
        assertThat(previewer.played).containsExactly(cesium, carbon)
        assertThat(previewer.stopped).containsExactly(cesium)
    }

    /** A stopped preview winding down mustn't clear the "playing" of the same sound tapped again. */
    @Test
    fun onSoundPreview_againAfterAStop_staysPlaying() = runTest {
        val viewModel = AlarmSoundsViewModel(FakeSettingsRepository(), FakeSoundCatalogSource(catalog), previewer)
        collectState(viewModel)

        viewModel.onSoundPreview(cesium.id)
        viewModel.onStopPreview()
        viewModel.onSoundPreview(cesium.id)

        assertThat(viewModel.state.value.previewing).isEqualTo(cesium.id)
        assertThat(previewer.played).containsExactly(cesium, cesium)
        assertThat(previewer.playing).isTrue()
    }

    @Test
    fun onSoundPreview_ofTheSiren_playsAFreshSiren() = runTest {
        val viewModel = AlarmSoundsViewModel(FakeSettingsRepository(), FakeSoundCatalogSource(catalog), previewer)

        viewModel.onSoundPreview(AlarmSound.SIREN_ID)

        assertThat(previewer.played).single().isInstanceOf(AlarmSound.Siren::class)
    }

    /** Leaving the screen (the wiring's ON_STOP) stops the sound. */
    @Test
    fun onStopPreview_stopsTheSoundPlaying() = runTest {
        val viewModel = AlarmSoundsViewModel(FakeSettingsRepository(), FakeSoundCatalogSource(catalog), previewer)
        collectState(viewModel)

        viewModel.onSoundPreview(krypton.id)
        viewModel.onStopPreview()

        assertThat(previewer.stopped).containsExactly(krypton)
        assertThat(viewModel.state.value.previewing).isNull()
    }

    /** Keeps [AlarmSoundsViewModel.state] subscribed (it is `WhileSubscribed`), so its `value` follows the ViewModel. */
    private fun TestScope.collectState(viewModel: AlarmSoundsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect() }
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

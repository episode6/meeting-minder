package com.episode6.meetingminder.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DataStoreSettingsRepositoryTest {

    private fun TestScope.dataStore(name: String) = PreferenceDataStoreFactory.create(scope = TestScope(UnconfinedTestDispatcher(testScheduler))) {
        ApplicationProvider.getApplicationContext<android.content.Context>().preferencesDataStoreFile(name).also { it.delete() }
    }

    @Test
    fun defaults_areTheSpecsNumbers() {
        assertThat(Settings()).isEqualTo(
            Settings(
                leadTime = Duration.ofMinutes(5),
                snoozeLength = Duration.ofMinutes(2),
                autoTimeout = Duration.ofMinutes(3),
                soundPool = SoundPool.ALL,
            ),
        )
    }

    @Test
    fun defaults_thenPersistsTheLeadTime() = runTest {
        val repository = DataStoreSettingsRepository(dataStore("settings-test"))

        assertThat(repository.current()).isEqualTo(Settings(leadTime = Duration.ofMinutes(5)))

        repository.setLeadTime(Duration.ofMinutes(15))

        assertThat(repository.settings.first()).isEqualTo(Settings(leadTime = Duration.ofMinutes(15)))
    }

    @Test
    fun readsTheRingingSettings_thatPr12WillWrite() = runTest {
        val dataStore = dataStore("settings-ringing-test")
        val repository = DataStoreSettingsRepository(dataStore)

        dataStore.edit {
            it[DataStoreSettingsRepository.Keys.SnoozeMinutes] = 4
            it[DataStoreSettingsRepository.Keys.AutoTimeoutMinutes] = 1
            it[DataStoreSettingsRepository.Keys.SoundPool] = "BUNDLED_ONLY"
        }

        assertThat(repository.current()).isEqualTo(
            Settings(snoozeLength = Duration.ofMinutes(4), autoTimeout = Duration.ofMinutes(1), soundPool = SoundPool.BUNDLED_ONLY),
        )
    }

    @Test
    fun unreadableValues_fallBackToTheDefaults() = runTest {
        val dataStore = dataStore("settings-garbage-test")
        val repository = DataStoreSettingsRepository(dataStore)

        dataStore.edit {
            it[DataStoreSettingsRepository.Keys.SnoozeMinutes] = 0
            it[DataStoreSettingsRepository.Keys.SoundPool] = "LOUDEST_ONLY"
        }

        assertThat(repository.current()).isEqualTo(Settings())
    }
}

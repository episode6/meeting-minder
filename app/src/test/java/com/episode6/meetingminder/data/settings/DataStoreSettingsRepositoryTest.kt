package com.episode6.meetingminder.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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

    @Test
    fun defaults_thenPersistsTheLeadTime() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val file = ApplicationProvider.getApplicationContext<android.content.Context>().preferencesDataStoreFile("settings-test")
        file.delete()
        val repository = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })

        assertThat(repository.current()).isEqualTo(Settings(leadTime = Duration.ofMinutes(5)))

        repository.setLeadTime(Duration.ofMinutes(15))

        assertThat(repository.settings.first()).isEqualTo(Settings(leadTime = Duration.ofMinutes(15)))
    }
}

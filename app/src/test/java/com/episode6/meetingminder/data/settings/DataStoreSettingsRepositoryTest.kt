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
                soundPool = AlarmSoundPool.ALL,
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
            it[DataStoreSettingsRepository.Keys.AlarmSoundPool] = "BUNDLED_ONLY"
        }

        assertThat(repository.current()).isEqualTo(
            Settings(snoozeLength = Duration.ofMinutes(4), autoTimeout = Duration.ofMinutes(1), soundPool = AlarmSoundPool.BUNDLED_ONLY),
        )
    }

    @Test
    fun setSnoozeLength_autoTimeout_soundPool_andShowDeclined_persist() = runTest {
        val repository = DataStoreSettingsRepository(dataStore("settings-pr12-test"))

        repository.setSnoozeLength(Duration.ofMinutes(10))
        repository.setAutoTimeout(Duration.ofMinutes(1))
        repository.setSoundPool(AlarmSoundPool.SYSTEM_ONLY)
        repository.setShowDeclined(false)

        assertThat(repository.current()).isEqualTo(
            Settings(
                snoozeLength = Duration.ofMinutes(10),
                autoTimeout = Duration.ofMinutes(1),
                soundPool = AlarmSoundPool.SYSTEM_ONLY,
                showDeclined = false,
            ),
        )
    }

    @Test
    fun setCalendarOverride_forcesIncludeOrExclude_andClearsWithNull() = runTest {
        val repository = DataStoreSettingsRepository(dataStore("settings-calendar-overrides-test"))

        repository.setCalendarOverride(1L, included = true)
        repository.setCalendarOverride(2L, included = false)

        assertThat(repository.current().calendarOverrides).isEqualTo(mapOf(1L to true, 2L to false))

        // flipping 1's override to excluded moves it out of "included", never leaves it in both
        repository.setCalendarOverride(1L, included = false)
        assertThat(repository.current().calendarOverrides).isEqualTo(mapOf(1L to false, 2L to false))

        repository.setCalendarOverride(1L, included = null)
        assertThat(repository.current().calendarOverrides).isEqualTo(mapOf(2L to false))
    }

    @Test
    fun setBusySyncEnabled_andCalendar_persistAndClearIndependently() = runTest {
        val repository = DataStoreSettingsRepository(dataStore("settings-busy-sync-test"))

        assertThat(repository.current().busySync).isEqualTo(BusySync())

        repository.setBusySyncEnabled(true)
        repository.setBusySyncCalendar(42L)

        assertThat(repository.current().busySync).isEqualTo(BusySync(enabled = true, calendarId = 42L))

        repository.setBusySyncCalendar(null)
        assertThat(repository.current().busySync).isEqualTo(BusySync(enabled = true, calendarId = null))

        repository.setBusySyncEnabled(false)
        assertThat(repository.current().busySync).isEqualTo(BusySync(enabled = false, calendarId = null))
    }

    @Test
    fun markPermissionRequested_persistsEveryPermissionAsked_withoutTouchingTheSettings() = runTest {
        val repository = DataStoreSettingsRepository(dataStore("settings-permissions-test"))

        assertThat(repository.requestedPermissions.first()).isEqualTo(emptySet())

        repository.markPermissionRequested("android.permission.READ_CALENDAR")
        repository.markPermissionRequested("android.permission.POST_NOTIFICATIONS")
        repository.markPermissionRequested("android.permission.READ_CALENDAR")

        assertThat(repository.requestedPermissions.first())
            .isEqualTo(setOf("android.permission.READ_CALENDAR", "android.permission.POST_NOTIFICATIONS"))
        assertThat(repository.current()).isEqualTo(Settings())
    }

    @Test
    fun unreadableValues_fallBackToTheDefaults() = runTest {
        val dataStore = dataStore("settings-garbage-test")
        val repository = DataStoreSettingsRepository(dataStore)

        dataStore.edit {
            it[DataStoreSettingsRepository.Keys.SnoozeMinutes] = 0
            it[DataStoreSettingsRepository.Keys.AlarmSoundPool] = "LOUDEST_ONLY"
        }

        assertThat(repository.current()).isEqualTo(Settings())
    }

    @Test
    fun setBusySyncFirstName_persistsTrimmed_andBlankClearsIt() = runTest {
        val repository = DataStoreSettingsRepository(dataStore("settings-busy-first-name-test"))

        repository.setBusySyncFirstName("  Geoff ")
        assertThat(repository.current().busySync).isEqualTo(BusySync(firstName = "Geoff"))

        repository.setBusySyncFirstName("   ")
        assertThat(repository.current().busySync).isEqualTo(BusySync())
    }
}

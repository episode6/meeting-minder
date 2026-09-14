package com.episode6.meetingminder.alarm

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The "recently used" ring buffer: per alarm, newest first, an alarm never avoiding its own sound. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecentAlarmSoundsTest {

    @Test
    fun recording_keepsOneEntryPerAlarm_newestFirst_capped() {
        val sounds = (1L..8L).fold(emptyList<RecentSound>()) { list, id -> list.recording(id, "sound:$id") }
            .recording(6, "sound:6-again")

        assertThat(sounds).containsExactly(
            RecentSound(6, "sound:6-again"),
            RecentSound(8, "sound:8"),
            RecentSound(7, "sound:7"),
            RecentSound(5, "sound:5"),
            RecentSound(4, "sound:4"),
            RecentSound(3, "sound:3"),
        )
    }

    @Test
    fun avoidFor_isTheLastFiveOtherAlarmsSounds() {
        val sounds = (1L..6L).fold(emptyList<RecentSound>()) { list, id -> list.recording(id, "sound:$id") }

        assertThat(sounds.avoidFor(6)).containsOnly("sound:5", "sound:4", "sound:3", "sound:2", "sound:1")
        assertThat(sounds.avoidFor(99)).containsOnly("sound:6", "sound:5", "sound:4", "sound:3", "sound:2")
    }

    @Test
    fun encoding_roundTrips_andSkipsGarbage() {
        val sounds = listOf(RecentSound(3, "system:content://media/internal/audio/media/12"), RecentSound(1, "bundled:Fire Drill"))

        assertThat(decodeRecentSounds(encodeRecentSounds(sounds))).isEqualTo(sounds)
        assertThat(decodeRecentSounds(null)).isEmpty()
        assertThat(decodeRecentSounds("nonsense\n7\tbundled:Argon\n\tmissing-id")).containsExactly(RecentSound(7, "bundled:Argon"))
    }

    @Test
    fun dataStore_persistsTheBuffer() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val file = ApplicationProvider.getApplicationContext<Context>().preferencesDataStoreFile("recent-sounds-test")
        file.delete()
        val recent = DataStoreRecentAlarmSounds(PreferenceDataStoreFactory.create(scope = scope) { file })

        assertThat(recent.avoidFor(1)).isEmpty()

        recent.record(1, "bundled:Argon")
        recent.record(2, "bundled:Helium")

        assertThat(recent.avoidFor(1)).containsOnly("bundled:Helium")
        assertThat(recent.avoidFor(3)).containsOnly("bundled:Argon", "bundled:Helium")
    }
}

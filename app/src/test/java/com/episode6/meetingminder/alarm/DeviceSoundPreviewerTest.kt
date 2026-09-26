package com.episode6.meetingminder.alarm

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowLog

/**
 * The preview's end conditions over a recording [SoundOutput] (Robolectric can't play a
 * static `AudioTrack`, so a real siren fails at `play()` and every preview would end at
 * once): losing audio focus ends it, a duckable loss doesn't, a stop ends it without being
 * logged as a playback failure, and every ending releases the sound and gives the focus back.
 * Real time throughout, since the previewer plays on `Dispatchers.IO`.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceSoundPreviewerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager = shadowOf(context.getSystemService(AudioManager::class.java))
    private val output = RecordingOutput()
    private val previewer = DeviceSoundPreviewer(context) { output }
    private val sound = AlarmSound.Bundled("Argon")

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    /** Starts a preview and waits until its sound is playing, returning its job and focus request. */
    private suspend fun CoroutineScope.startPreview(): Pair<Job, ShadowAudioManager.AudioFocusRequest> {
        val job = launch(Dispatchers.Default) { previewer.play(sound) }
        withTimeout(TIMEOUT_MILLIS) { output.started.await() }
        return job to audioManager.lastAudioFocusRequest
    }

    private fun ShadowAudioManager.AudioFocusRequest.lose(change: Int) {
        checkNotNull(listener) { "the preview asked for focus without a listener" }.onAudioFocusChange(change)
    }

    @Test
    fun losingFocus_endsThePreview_releasesIt_andGivesTheFocusBack() = runBlocking {
        val (job, request) = startPreview()

        request.lose(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        withTimeout(TIMEOUT_MILLIS) { job.join() }
        assertThat(job.isCancelled).isFalse()
        assertThat(output.released).isTrue()
        assertThat(audioManager.lastAbandonedAudioFocusRequest).isSameInstanceAs(request.audioFocusRequest)
    }

    /** The system ducks for a "can duck" loss (a navigation prompt), so the preview plays on. */
    @Test
    fun aDuckableLoss_leavesThePreviewPlaying() = runBlocking {
        val (job, request) = startPreview()

        request.lose(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        delay(200)

        assertThat(job.isActive).isTrue()
        assertThat(output.released).isFalse()
        job.cancelAndJoin()
    }

    @Test
    fun aStop_endsThePreview_withoutLoggingAFailure() = runBlocking {
        val (job, request) = startPreview()

        withTimeout(TIMEOUT_MILLIS) { job.cancelAndJoin() }

        assertThat(output.released).isTrue()
        assertThat(audioManager.lastAbandonedAudioFocusRequest).isSameInstanceAs(request.audioFocusRequest)
        assertThat(ShadowLog.getLogsForTag("SoundPreviewer").filter { it.type >= Log.WARN }).isEmpty()
    }

    @Test
    fun theSound_playsAtItsOwnSpeedAndPitch_pastTheRamp() = runBlocking {
        val (job, _) = startPreview()

        assertThat(output.segment).isEqualTo(SoundSegment(sound, speed = 1f, pitch = 1f, durationMillis = AlarmSoundDefaults.PREVIEW_MILLIS))
        assertThat(output.rampElapsedMillis).isEqualTo(AlarmSoundDefaults.RAMP_MILLIS)
        job.cancelAndJoin()
    }

    private class RecordingOutput : SoundOutput {
        override val failed = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()

        @Volatile var segment: SoundSegment? = null
        @Volatile var rampElapsedMillis: Long? = null
        @Volatile var released = false

        override fun play(segment: SoundSegment, rampElapsedMillis: Long) {
            this.segment = segment
            this.rampElapsedMillis = rampElapsedMillis
            started.complete(Unit)
        }

        override fun release() {
            released = true
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}

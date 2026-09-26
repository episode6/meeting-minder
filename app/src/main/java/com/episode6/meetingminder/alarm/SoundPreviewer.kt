package com.episode6.meetingminder.alarm

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val LOG_TAG = "SoundPreviewer"

/**
 * Plays one sound of the [SoundCatalog] so Settings → Alarm sounds can let the user hear
 * what they're checking. [play] suspends while the sound plays and returns when it ends;
 * cancelling it stops the sound.
 */
interface SoundPreviewer {
    suspend fun play(sound: AlarmSound)
}

/**
 * [SoundPreviewer] over the ringing player's own sources ([openSoundOutput]): the sound at
 * its natural speed and pitch, looped for up to [AlarmSoundDefaults.PREVIEW_MILLIS], on the
 * alarm stream at the user's alarm volume (no ramp, and the volume isn't raised as a real
 * ring's is), holding transient audio focus so music pauses for it. Losing that focus — a
 * call, or an alarm going off while the page is open — ends the preview, as does a sound
 * that can't be opened or errors out (bound in `di/AlarmModule.kt`).
 */
class DeviceSoundPreviewer internal constructor(
    context: Context,
    private val open: (AlarmSound) -> SoundOutput,
) : SoundPreviewer {
    constructor(context: Context) : this(context, { openSoundOutput(context, it) })

    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val audioThread = Dispatchers.IO.limitedParallelism(1)

    override suspend fun play(sound: AlarmSound) {
        withContext(audioThread) { preview(sound) }
    }

    private suspend fun preview(sound: AlarmSound) {
        val output = try {
            open(sound)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "couldn't open ${sound.id}", e)
            return
        }
        val focusLost = CompletableDeferred<Unit>()
        val focus = audioManager.requestAlarmFocus(onLoss = { focusLost.complete(Unit) })
        try {
            try {
                output.play(
                    SoundSegment(sound, speed = 1f, pitch = 1f, durationMillis = AlarmSoundDefaults.PREVIEW_MILLIS),
                    // past the ramp: a preview plays at full volume from its first second
                    rampElapsedMillis = AlarmSoundDefaults.RAMP_MILLIS,
                )
            } catch (e: RuntimeException) {
                Log.w(LOG_TAG, "couldn't play ${sound.id}", e)
                return
            }
            // outside the catch: a stop is a cancellation, which is a RuntimeException on the JVM
            withTimeoutOrNull(AlarmSoundDefaults.PREVIEW_MILLIS) {
                select {
                    output.failed.onAwait {}
                    focusLost.onAwait {}
                }
            }
        } finally {
            withContext(NonCancellable) {
                output.release()
                focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            }
        }
    }
}

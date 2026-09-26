package com.episode6.meetingminder.alarm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

/**
 * A [SoundPreviewer] whose [play] records the sound and then plays until [finish] or until
 * it is cancelled, which it records in [stopped].
 */
internal class FakeSoundPreviewer : SoundPreviewer {
    val played = mutableListOf<AlarmSound>()
    val stopped = mutableListOf<AlarmSound>()

    /** True while a [play] is suspended, i.e. a sound is audible. */
    var playing = false
        private set

    private var current: CompletableDeferred<Unit>? = null

    override suspend fun play(sound: AlarmSound) {
        check(!playing) { "two previews at once" }
        played += sound
        playing = true
        val done = CompletableDeferred<Unit>().also { current = it }
        try {
            done.await()
        } catch (e: CancellationException) {
            stopped += sound
            throw e
        } finally {
            playing = false
        }
    }

    /** Ends the sound playing as if it ran its course. */
    fun finish() {
        current?.complete(Unit)
    }
}

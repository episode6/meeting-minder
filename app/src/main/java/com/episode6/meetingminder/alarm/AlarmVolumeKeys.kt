package com.episode6.meetingminder.alarm

import android.content.Context
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState

private const val TAG = "MeetingMinderRinging"

/**
 * Hears the volume keys while an alarm makes a sound, wherever the user is — the ringing
 * screen handles them itself (`AlarmActivity.onKeyDown`), but an alarm that rings while the
 * phone is in use is only a heads-up over someone else's window. An active, "playing"
 * framework `MediaSession` with a remote [VolumeProvider] is where the system sends volume
 * keys nothing else consumed, so a press arrives as [VolumeProvider.onAdjustVolume] instead
 * of moving a stream's volume: the first press silences, as for an incoming call, and the
 * session is released with the sound so the next press is an ordinary volume key again.
 * Nothing is published on it: no metadata, no media notification, no transport controls.
 */
internal class AlarmVolumeKeys(private val context: Context) {
    private var session: MediaSession? = null

    fun start(onVolumeKey: () -> Unit) {
        stop()
        session = MediaSession(context, TAG).apply {
            // an empty callback, but not optional: the framework delivers a volume
            // adjustment through the session's callback handler and drops it without one
            setCallback(object : MediaSession.Callback() {})
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
            setPlaybackToRemote(
                object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 1, 1) {
                    override fun onAdjustVolume(direction: Int) {
                        // 0 is the key going back up
                        if (direction != 0) onVolumeKey()
                    }
                },
            )
            isActive = true
        }
    }

    fun stop() {
        session?.release()
        session = null
    }
}

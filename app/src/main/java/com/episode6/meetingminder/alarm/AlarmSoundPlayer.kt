package com.episode6.meetingminder.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.RingtoneManager
import android.media.VolumeShaper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.settings.SettingsRepository
import com.episode6.meetingminder.model.RingingAlarm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.ContinuationInterceptor

/**
 * Every sound, focus request and vibration of a ringing alarm carries these: the alarm
 * stream plays independently of media volume and through DND's default policy, and Android
 * 17's background-audio hardening only lets an exact-alarm app play from the background
 * when it sticks to `USAGE_ALARM` (TODO.md §4.4).
 */
internal val AlarmAudioAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ALARM)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

/** A source that fails to open or errors out is skipped after this pause, so a device with no audio can't spin. */
private const val FAILED_SOUND_BACKOFF_MILLIS = 500L

private const val LOG_TAG = "AlarmSoundPlayer"

/**
 * Plays a ringing alarm's randomised obnoxious alert (TODO.md §4.4): the segments of its
 * [AlarmSoundRecipe] — device alarm ringtones and bundled OGGs through [MediaPlayer], the
 * siren through an [AudioTrack] — each looped at its random speed and pitch until the next
 * re-roll, with a [VolumeShaper] ramp from 25% to 100% over the first 15 s of the ring.
 * Holds transient audio focus (a failed request is ignored: an alarm never stays silent
 * because another app holds focus) and raises the alarm stream to at least half volume,
 * restoring both when it stops. All media calls run on one background thread.
 */
class AlarmSoundPlayer(
    private val context: Context,
    private val recentSounds: RecentAlarmSounds,
    private val settings: SettingsRepository,
) {
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val audioThread = Dispatchers.IO.limitedParallelism(1)

    private var job: Job? = null

    /** Rings [alarm] on [scope] (replacing anything playing); [onSound] hears each sound's name as it starts, on [scope]'s thread. */
    fun start(scope: CoroutineScope, alarm: RingingAlarm, onSound: (String) -> Unit) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            val caller = currentCoroutineContext()[ContinuationInterceptor] ?: Dispatchers.Main
            withContext(audioThread) {
                ring(alarm) { name -> withContext(caller) { onSound(name) } }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun ring(alarm: RingingAlarm, onSound: suspend (String) -> Unit) {
        val focus = requestFocus()
        val volume = raiseVolume()
        try {
            val recipe = AlarmSoundRecipe(
                seed = alarm.soundIndex,
                catalog = SoundCatalog(system = systemSounds(), bundled = BundledAlarmSounds.all.map { AlarmSound.Bundled(it.name) }),
                pool = settings.current().soundPool,
                recentlyUsed = recentSounds.avoidFor(alarm.alarmId),
            )
            val startedAt = SystemClock.elapsedRealtime()
            var recorded = false
            for (segment in recipe.segments()) {
                val output = try {
                    open(segment.sound)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "couldn't open ${segment.sound.id}", e)
                    null
                }
                if (output == null) {
                    delay(FAILED_SOUND_BACKOFF_MILLIS)
                    continue
                }
                // an opened output is ours to release whatever happens next: a play() that
                // throws (createVolumeShaper/start can) must not drop a live native player
                val started = try {
                    output.play(segment, rampElapsedMillis = SystemClock.elapsedRealtime() - startedAt)
                    true
                } catch (e: RuntimeException) {
                    Log.w(LOG_TAG, "couldn't play ${segment.sound.id}", e)
                    output.release()
                    false
                }
                if (!started) {
                    delay(FAILED_SOUND_BACKOFF_MILLIS)
                    continue
                }
                try {
                    if (!recorded && segment.sound !is AlarmSound.Siren) {
                        recentSounds.record(alarm.alarmId, segment.sound.id)
                        recorded = true
                    }
                    onSound(nameOf(segment.sound))
                    withTimeoutOrNull(segment.durationMillis) { output.failed.await() }
                } finally {
                    withContext(NonCancellable) { output.release() }
                }
            }
        } finally {
            withContext(NonCancellable) {
                focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
                volume?.let(::restoreVolume)
            }
        }
    }

    private fun open(sound: AlarmSound): SoundOutput = when (sound) {
        is AlarmSound.System -> mediaOutput { setDataSource(context, sound.uri.toUri()) }
        is AlarmSound.Bundled -> mediaOutput {
            val resId = BundledAlarmSounds.byName.getValue(sound.name).resId
            context.resources.openRawResourceFd(resId).use { setDataSource(it) }
        }
        is AlarmSound.Siren -> TrackOutput(renderSiren(sound.params))
    }

    /** A prepared [MediaOutput] over [source]; the player is released if any step of opening it fails. */
    private fun mediaOutput(source: MediaPlayer.() -> Unit): MediaOutput {
        val player = MediaPlayer()
        try {
            player.source()
            return MediaOutput(player)
        } catch (e: Exception) {
            player.release()
            throw e
        }
    }

    private fun nameOf(sound: AlarmSound): String = when (sound) {
        is AlarmSound.System -> sound.title
        is AlarmSound.Bundled -> sound.name
        is AlarmSound.Siren -> context.getString(R.string.alarm_sound_siren)
    }

    /**
     * The device's alarm ringtones; the default alarm sound if the list can't be read or is
     * empty. A ringtone the user added from storage sits on the external volume and needs
     * `READ_MEDIA_AUDIO` to open, which this app doesn't hold: it fails in [open], costs the
     * backoff's silence, and the recipe may well draw it again — accepted rather than
     * asking for a media permission for the sake of a re-roll.
     */
    private fun systemSounds(): List<AlarmSound.System> {
        val sounds = try {
            val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_ALARM) }
            // the manager is throwaway, so its cursor is ours to close (a CursorLeak on every ring otherwise)
            manager.cursor.use { cursor ->
                List(cursor.count) { position ->
                    cursor.moveToPosition(position)
                    AlarmSound.System(manager.getRingtoneUri(position).toString(), cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX))
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "couldn't list alarm ringtones", e)
            emptyList()
        }
        return sounds.ifEmpty {
            listOf(AlarmSound.System(Settings.System.DEFAULT_ALARM_ALERT_URI.toString(), context.getString(R.string.alarm_sound_default)))
        }
    }

    private fun requestFocus(): AudioFocusRequest? {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AlarmAudioAttributes).build()
        return runCatching { audioManager.requestAudioFocus(request) }.map { request }.getOrNull()
    }

    private data class RaisedVolume(val original: Int, val raisedTo: Int)

    private fun raiseVolume(): RaisedVolume? {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val raisedTo = raisedAlarmVolume(current, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)) ?: return null
        return try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, raisedTo, 0)
            RaisedVolume(current, raisedTo)
        } catch (_: SecurityException) {
            // DND policy access: carry on at the user's volume
            null
        }
    }

    /** Puts the alarm volume back, unless the user changed it while it rang. */
    private fun restoreVolume(volume: RaisedVolume) {
        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != volume.raisedTo) return
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volume.original, 0) }
    }
}

/**
 * The rest of the 25% → 100% ramp for a source starting [elapsedMillis] into the ring (so a
 * re-roll continues the ramp rather than restarting it); null once the ramp is over.
 */
private fun rampFrom(elapsedMillis: Long): VolumeShaper.Configuration? {
    if (elapsedMillis >= AlarmSoundDefaults.RAMP_MILLIS) return null
    return VolumeShaper.Configuration.Builder()
        .setDuration(AlarmSoundDefaults.RAMP_MILLIS - elapsedMillis.coerceAtLeast(0))
        .setCurve(floatArrayOf(0f, 1f), floatArrayOf(rampVolumeAt(elapsedMillis), 1f))
        .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
        .build()
}

private sealed interface SoundOutput {
    /** Completes if the source errors out mid-play, so the player re-rolls early. */
    val failed: CompletableDeferred<Unit>

    fun play(segment: SoundSegment, rampElapsedMillis: Long)

    fun release()
}

private class MediaOutput(private val player: MediaPlayer) : SoundOutput {
    override val failed = CompletableDeferred<Unit>()

    init {
        player.setAudioAttributes(AlarmAudioAttributes)
        player.isLooping = true
        player.setOnErrorListener { _, _, _ ->
            failed.complete(Unit)
            true
        }
        player.prepare()
    }

    override fun play(segment: SoundSegment, rampElapsedMillis: Long) {
        // created before playback starts, a shaper begins at the start of its curve
        val shaper = rampFrom(rampElapsedMillis)?.let(player::createVolumeShaper)
        try {
            // non-zero speed on a prepared player is a start()
            player.playbackParams = PlaybackParams().setSpeed(segment.speed).setPitch(segment.pitch)
        } catch (_: RuntimeException) {
            // a source that can't be time-stretched still rings, at its own pitch
        }
        if (!player.isPlaying) player.start()
        shaper?.apply(VolumeShaper.Operation.PLAY)
    }

    override fun release() {
        runCatching { player.stop() }
        player.release()
    }
}

private class TrackOutput(pcm: ShortArray) : SoundOutput {
    override val failed = CompletableDeferred<Unit>()

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AlarmAudioAttributes)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SIREN_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
        .build()

    init {
        try {
            track.write(pcm, 0, pcm.size)
            track.setLoopPoints(0, pcm.size, -1)
        } catch (e: RuntimeException) {
            track.release()
            throw e
        }
    }

    override fun play(segment: SoundSegment, rampElapsedMillis: Long) {
        val shaper = rampFrom(rampElapsedMillis)?.let(track::createVolumeShaper)
        runCatching { track.playbackParams = PlaybackParams().setSpeed(segment.speed).setPitch(segment.pitch) }
        track.play()
        shaper?.apply(VolumeShaper.Operation.PLAY)
    }

    override fun release() {
        runCatching { track.stop() }
        track.release()
    }
}

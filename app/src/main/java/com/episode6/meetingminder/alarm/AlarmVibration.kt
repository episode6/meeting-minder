package com.episode6.meetingminder.alarm

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The ringing alarm's vibration (TODO.md §4.4): a random waveform ([alarmVibrationTimings])
 * repeated on the default vibrator, tagged as an alarm so it vibrates through the ringer
 * mode the way the alarm stream plays through it.
 */
class AlarmVibration(context: Context) {

    private val vibrator: Vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    fun start(timings: LongArray) {
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(timings, 1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AlarmAudioAttributes)
        }
    }

    fun stop() {
        vibrator.cancel()
    }
}

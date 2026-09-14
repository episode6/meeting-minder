package com.episode6.meetingminder.alarm

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the CPU awake from the moment an alarm broadcast arrives until the ringing is
 * over. The broadcast's own wake lock ends when `AlarmReceiver.onReceive` returns, before
 * `AlarmRingingService` has even been created, so the receiver takes this first and the
 * service holds it (re-acquired per ring, with a timeout as a backstop) until it stops.
 * Not reference counted: every acquire just extends it, one release ends it.
 */
internal object AlarmWakeLock {
    private const val TAG = "MeetingMinder:AlarmRinging"

    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context, timeoutMillis: Long) {
        val lock = wakeLock ?: context.applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(timeoutMillis)
    }

    val isHeld: Boolean
        @Synchronized get() = wakeLock?.isHeld == true

    @Synchronized
    fun release() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }
}

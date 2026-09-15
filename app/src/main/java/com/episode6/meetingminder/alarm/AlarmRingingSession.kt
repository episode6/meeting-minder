package com.episode6.meetingminder.alarm

import com.episode6.meetingminder.model.RingingAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How long a fired alarm may take to load before the service posts a bare foreground notification to meet the OS deadline. */
internal const val FOREGROUND_WATCHDOG_MILLIS = 2_000L

/** What [AlarmRingingSession] needs [AlarmRingingService] to do; the service is a thin Android adapter over it. */
interface RingingOutputs {
    /**
     * `startForeground` with the ringing notification (full-screen intent, Snooze/Dismiss)
     * for [alarm]. [alert] is true when [alarm] has just started ringing (heads-up and
     * full-screen intent again), false for a re-post of the same alarm.
     */
    fun showRinging(alarm: RingingAlarm, alert: Boolean)

    /** `startForeground` with a bare notification: answers a `startForegroundService` when there is nothing (yet) to ring. */
    fun showPlaceholder()

    fun startSound(alarm: RingingAlarm)

    fun stopSound()

    /** `SetRinging` into the store, for `AlarmActivity`. */
    fun publish(ringing: RingingAlarm?)

    /** The "missed alarm" notification: [alarm] gave up unanswered, or its snooze couldn't be armed. */
    fun postMissed(alarm: RingingAlarm)

    /** `stopForeground(REMOVE)` and `stopSelf` (unless a newer start command has arrived since). */
    fun stop()
}

/**
 * The ringing state machine behind [AlarmRingingService] (TODO.md §4.4), free of Android
 * so its rules are unit-tested:
 *
 * - **Fire**: the row is marked `FIRED` ([AlarmRinger.fire]) and the alarm rings — published
 *   to the store, the foreground notification posted, sound and vibration started, the
 *   auto-timeout armed. An alarm that fires while another is ringing (back-to-back or
 *   overlapping meetings) waits in a queue and rings as soon as the current one is
 *   snoozed or dismissed, so every alarm gets answered.
 * - **The foreground deadline**: every `startForegroundService` must be answered with
 *   `startForeground` within seconds or the OS kills the app, and that includes a fire that
 *   turns out to have nothing to ring (cancelled after the OS queued it). So a fire while
 *   already in the foreground re-posts at once; otherwise a watchdog posts a placeholder if
 *   loading the row takes longer than [FOREGROUND_WATCHDOG_MILLIS], and a fire with nothing
 *   to ring posts one before stopping.
 * - **Snooze / Dismiss** (the notification's actions, or the ringing screen through the
 *   store): the sound stops first, then the row is written and awaited — a snooze's new
 *   `setAlarmClock` included — and only then does the service move on or let go of the
 *   foreground, so the process can't be reclaimed with a snooze half written.
 * - **Auto-timeout**: unanswered for the auto-timeout, the alarm snoozes itself once; the
 *   next time it goes unanswered it gives up with a "missed alarm" notification
 *   ([AlarmRinger.timeOut]). A snooze the OS refuses to arm is reported the same way.
 *
 * Everything runs on [scope] (the service's main-thread scope); commands are serialised
 * in arrival order.
 */
internal class AlarmRingingSession(
    private val scope: CoroutineScope,
    private val ringer: AlarmRinger,
    private val outputs: RingingOutputs,
) {
    private val mutex = Mutex()
    private val queue = ArrayDeque<RingingAlarm>()
    private var inForeground = false
    private var pendingCommands = 0
    private var watchdog: Job? = null
    private var timeout: Job? = null

    /** The alarm ringing now, if any. */
    val ringing: RingingAlarm? get() = queue.firstOrNull()

    /** A `startForegroundService` fire command for [alarmId]. */
    fun fire(alarmId: Long) {
        if (inForeground) {
            repostForeground()
        } else if (watchdog?.isActive != true) {
            watchdog = scope.launch {
                delay(FOREGROUND_WATCHDOG_MILLIS)
                if (!inForeground) showPlaceholder()
            }
        }
        serialized {
            val alarm = ringer.fire(alarmId)
            when {
                alarm != null -> {
                    queue += alarm
                    if (queue.size == 1) ringCurrent()
                }
                queue.isEmpty() -> {
                    if (!inForeground) showPlaceholder()
                    finish()
                }
            }
        }
    }

    fun snooze(alarmId: Long) = serialized {
        val alarm = queue.firstOrNull { it.alarmId == alarmId }
        settle(alarmId) {
            if (ringer.snooze(alarmId) == SnoozeResult.REFUSED && alarm != null) outputs.postMissed(alarm)
        }
    }

    fun dismiss(alarmId: Long) = serialized {
        settle(alarmId) { ringer.dismiss(alarmId) }
    }

    /** The player started a (re-rolled) sound for [alarmId]; publishes its name if that alarm is still the one ringing. */
    fun onSoundStarted(alarmId: Long, soundName: String) {
        val current = queue.firstOrNull()?.takeIf { it.alarmId == alarmId } ?: return
        queue[0] = current.copy(soundName = soundName)
        outputs.publish(queue[0])
    }

    /** The service is being destroyed: silence everything and tell the screen nothing rings any more. */
    fun release() {
        watchdog?.cancel()
        timeout?.cancel()
        if (queue.isNotEmpty()) {
            queue.clear()
            outputs.stopSound()
            outputs.publish(null)
        }
    }

    private suspend fun ringCurrent() {
        val alarm = queue.first()
        watchdog?.cancel()
        outputs.publish(alarm)
        outputs.showRinging(alarm, alert = true)
        inForeground = true
        outputs.startSound(alarm)
        timeout?.cancel()
        val after = ringer.autoTimeout().toMillis()
        timeout = scope.launch {
            delay(after)
            serialized { timedOut(alarm) }
        }
    }

    private suspend fun timedOut(alarm: RingingAlarm) {
        if (queue.firstOrNull()?.alarmId != alarm.alarmId) {
            // Settled (dismissed or snoozed) while this command was already queued behind
            // the mutex: that settle's finish() saw it pending and left the service running
            // for it, so an empty queue is now this command's to let go of. A redundant
            // finish() is harmless.
            if (queue.isEmpty()) finish()
            return
        }
        settle(alarm.alarmId) {
            when (ringer.timeOut(alarm.alarmId)) {
                TimeoutResult.GAVE_UP, TimeoutResult.REFUSED -> outputs.postMissed(alarm)
                TimeoutResult.SNOOZED, TimeoutResult.NOT_RINGING -> Unit
            }
        }
    }

    /** Silences [alarmId] if it's the one ringing, awaits [write], then rings the next queued alarm or finishes. */
    private suspend fun settle(alarmId: Long, write: suspend () -> Unit) {
        val wasRinging = queue.firstOrNull()?.alarmId == alarmId
        if (wasRinging) {
            timeout?.cancel()
            outputs.stopSound()
        }
        write()
        queue.removeAll { it.alarmId == alarmId }
        when {
            queue.isEmpty() -> finish()
            wasRinging -> ringCurrent()
        }
    }

    private fun finish() {
        timeout?.cancel()
        outputs.publish(null)
        // a command still waiting its turn (a fire that arrived meanwhile) keeps the service
        if (pendingCommands <= 1) {
            watchdog?.cancel()
            inForeground = false
            outputs.stop()
        }
    }

    private fun repostForeground() {
        val current = queue.firstOrNull()
        if (current != null) outputs.showRinging(current, alert = false) else outputs.showPlaceholder()
    }

    private fun showPlaceholder() {
        outputs.showPlaceholder()
        inForeground = true
    }

    private fun serialized(block: suspend () -> Unit) {
        pendingCommands++
        scope.launch {
            try {
                mutex.withLock { block() }
            } finally {
                pendingCommands--
            }
        }
    }
}

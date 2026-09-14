package com.episode6.meetingminder.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The alarm that is ringing right now ([com.episode6.meetingminder.store.AppState.ringing]):
 * what `AlarmRingingService` is playing and the full-screen `AlarmActivity` shows (render
 * 5). Built from the denormalised `scheduled_alarm` row alone, never the provider, so it
 * works in a process the alarm itself just started.
 */
data class RingingAlarm(
    /** The `scheduled_alarm` row, and the identity every Snooze/Dismiss command is addressed by. */
    val alarmId: Long,
    val date: LocalDate,
    val key: EventKey,
    val title: String,
    val location: String?,
    val begin: Instant,
    val end: Instant,
    /** Seeds the randomised sound, so a snoozed alarm comes back sounding the same. */
    val soundIndex: Int,
    /** How long Snooze silences it for (a setting, read when the alarm fired). */
    val snoozeLength: Duration,
    /**
     * The name of the sound playing right now, for the ringing screen's subtle "Sound: …"
     * line (a debug aid); null until the player has started one. Changes with every
     * re-roll.
     */
    val soundName: String? = null,
)

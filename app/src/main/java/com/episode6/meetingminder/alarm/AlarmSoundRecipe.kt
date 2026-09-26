package com.episode6.meetingminder.alarm

import kotlin.math.ceil
import kotlin.random.Random

/** A sound a ringing alarm can play (TODO.md §4.4). [id] is what the recently-used buffer stores. */
sealed interface AlarmSound {
    val id: String

    /** One of the device's alarm ringtones (`RingtoneManager.TYPE_ALARM`), by content [uri]. */
    data class System(val uri: String, val title: String) : AlarmSound {
        override val id: String get() = "system:$uri"
    }

    /** One of the OGGs bundled in `res/raw` (see `BundledAlarmSounds`), by [name]. */
    data class Bundled(val name: String) : AlarmSound {
        override val id: String get() = "bundled:$name"
    }

    /** The synthesised siren. Every draw gets its own [params], so it is never "recently used". */
    data class Siren(val params: SirenParams) : AlarmSound {
        override val id: String get() = SIREN_ID
    }

    companion object {
        const val SIREN_ID = "siren"
    }
}

/** One stretch of ringing: [sound] at [speed]/[pitch] for [durationMillis], after which the player re-rolls. */
data class SoundSegment(val sound: AlarmSound, val speed: Float, val pitch: Float, val durationMillis: Long)

/**
 * What there is to choose from on this device: its alarm ringtones, the bundled OGGs and
 * whether the synthesised [siren] is on the table. [SoundCatalogSource] loads the whole
 * device's; [without] takes the user's unchecked sounds out of it.
 */
data class SoundCatalog(val system: List<AlarmSound.System>, val bundled: List<AlarmSound.Bundled>, val siren: Boolean = true)

/** This catalog less the sounds with an [AlarmSound.id] in [disabledIds] (`Settings.disabledAlarmSounds`). */
fun SoundCatalog.without(disabledIds: Set<String>): SoundCatalog = SoundCatalog(
    system = system.filter { it.id !in disabledIds },
    bundled = bundled.filter { it.id !in disabledIds },
    siren = siren && AlarmSound.SIREN_ID !in disabledIds,
)

/** The §4.4 numbers behind the randomised alert, in one place. */
object AlarmSoundDefaults {
    const val SYSTEM_WEIGHT = 60
    const val BUNDLED_WEIGHT = 30
    const val SIREN_WEIGHT = 10

    const val MIN_SPEED = 0.85f
    const val MAX_SPEED = 1.35f
    const val MIN_PITCH = 0.8f
    const val MAX_PITCH = 1.5f

    /** A re-roll of source and pitch "every ~10 s". */
    const val MIN_SEGMENT_MILLIS = 8_000L
    const val MAX_SEGMENT_MILLIS = 12_000L

    /** The volume ramp every alarm starts with, so a fast dismiss isn't deafening. */
    const val RAMP_START_VOLUME = 0.25f
    const val RAMP_MILLIS = 15_000L

    /** How many *other* alarms' first sounds a new alarm avoids repeating. */
    const val RECENT_ALARMS = 5

    const val MIN_VIBRATION_SEGMENTS = 4
    const val MAX_VIBRATION_SEGMENTS = 8
    const val MIN_VIBRATION_SEGMENT_MILLIS = 80L
    const val MAX_VIBRATION_SEGMENT_MILLIS = 700L

    const val MIN_SIREN_LOW_HZ = 500f
    const val MAX_SIREN_LOW_HZ = 900f
    const val MIN_SIREN_HIGH_HZ = 1_200f
    const val MAX_SIREN_HIGH_HZ = 2_400f
    const val MIN_SIREN_SWEEP_MILLIS = 300
    const val MAX_SIREN_SWEEP_MILLIS = 1_600
    const val MIN_SIREN_PULSE_MILLIS = 150
    const val MAX_SIREN_PULSE_MILLIS = 600
    const val MIN_SIREN_DUTY = 0.4f
    const val MAX_SIREN_DUTY = 0.85f
    const val MAX_SIREN_HARMONIC = 0.45f
}

/**
 * The randomised obnoxious alert of TODO.md §4.4, as a pure, deterministic draw: the same
 * [seed] (the alarm's `sound_index`), [catalog] and [recentlyUsed] always produce the same
 * [segments], so a snoozed alarm comes back sounding the same and tests can pin the output.
 *
 * Each segment picks a source — a device alarm ringtone 60% of the time, a bundled OGG 30%,
 * the synthesised siren 10% (a source with nothing to offer — empty on the device, or every
 * sound of it unchecked in Settings — is skipped and its weight redistributed; with nothing
 * at all to offer, the siren rings) — plus a random playback speed and pitch. The first segment
 * avoids every sound in [recentlyUsed] (the first sounds of the last few *other* alarms);
 * each later one avoids the sound just played. Either rule gives way when it would leave
 * nothing to pick.
 */
class AlarmSoundRecipe(
    private val seed: Int,
    private val catalog: SoundCatalog,
    private val recentlyUsed: Set<String>,
) {
    /** Endless: the player takes one per re-roll for as long as the alarm rings. */
    fun segments(): Sequence<SoundSegment> = sequence {
        val random = Random(seed)
        var avoid = recentlyUsed
        while (true) {
            val segment = random.nextSegment(avoid)
            yield(segment)
            avoid = setOf(segment.sound.id)
        }
    }

    private fun Random.nextSegment(avoid: Set<String>): SoundSegment = SoundSegment(
        sound = nextSound(avoid),
        speed = nextFloat(AlarmSoundDefaults.MIN_SPEED, AlarmSoundDefaults.MAX_SPEED),
        pitch = nextFloat(AlarmSoundDefaults.MIN_PITCH, AlarmSoundDefaults.MAX_PITCH),
        durationMillis = nextLong(AlarmSoundDefaults.MIN_SEGMENT_MILLIS, AlarmSoundDefaults.MAX_SEGMENT_MILLIS + 1),
    )

    private fun Random.nextSound(avoid: Set<String>): AlarmSound {
        val sources = buildList {
            if (catalog.system.isNotEmpty()) add(Source.SYSTEM)
            if (catalog.bundled.isNotEmpty()) add(Source.BUNDLED)
            if (catalog.siren) add(Source.SIREN)
        }.ifEmpty { listOf(Source.SIREN) }
        var roll = nextInt(sources.sumOf { it.weight })
        val source = sources.first { roll < it.weight || run { roll -= it.weight; false } }
        return when (source) {
            Source.SYSTEM -> pick(catalog.system, avoid)
            Source.BUNDLED -> pick(catalog.bundled, avoid)
            Source.SIREN -> AlarmSound.Siren(nextSirenParams())
        }
    }

    private fun <T : AlarmSound> Random.pick(candidates: List<T>, avoid: Set<String>): T =
        candidates.filter { it.id !in avoid }.ifEmpty { candidates }.random(this)

    private enum class Source(val weight: Int) {
        SYSTEM(AlarmSoundDefaults.SYSTEM_WEIGHT),
        BUNDLED(AlarmSoundDefaults.BUNDLED_WEIGHT),
        SIREN(AlarmSoundDefaults.SIREN_WEIGHT),
    }
}

/**
 * A random vibration waveform for `VibrationEffect.createWaveform(timings, repeat = 1)`: a
 * leading 0 ms "off" so it starts at once, then an even number (4–8) of alternating on/off
 * segments of 80–700 ms each, repeated from index 1. Seeded like [AlarmSoundRecipe] but
 * from its own stream, so the pattern doesn't change when the sound draws do.
 */
fun alarmVibrationTimings(seed: Int): LongArray {
    val random = Random(seed.inv())
    val pairs = random.nextInt(AlarmSoundDefaults.MIN_VIBRATION_SEGMENTS / 2, AlarmSoundDefaults.MAX_VIBRATION_SEGMENTS / 2 + 1)
    return LongArray(pairs * 2 + 1) { index ->
        if (index == 0) 0L else random.nextLong(AlarmSoundDefaults.MIN_VIBRATION_SEGMENT_MILLIS, AlarmSoundDefaults.MAX_VIBRATION_SEGMENT_MILLIS + 1)
    }
}

/**
 * The volume a ring that started [elapsedMillis] ago should be at on the 25% → 100% ramp,
 * so a re-rolled source picks the ramp up where the last one left it instead of dropping
 * back to 25%.
 */
fun rampVolumeAt(elapsedMillis: Long): Float {
    if (elapsedMillis >= AlarmSoundDefaults.RAMP_MILLIS) return 1f
    val progress = elapsedMillis.coerceAtLeast(0).toFloat() / AlarmSoundDefaults.RAMP_MILLIS
    return AlarmSoundDefaults.RAMP_START_VOLUME + (1f - AlarmSoundDefaults.RAMP_START_VOLUME) * progress
}

/** The alarm stream is raised to at least this share of its maximum while ringing (and put back after). */
private const val MIN_ALARM_VOLUME_FRACTION = 0.5

/**
 * The alarm stream volume to raise to so an alarm is never near-silent (TODO.md §4.4): at
 * least half of [max]. Null when [current] is already loud enough (nothing to change or
 * restore).
 */
internal fun raisedAlarmVolume(current: Int, max: Int): Int? {
    val floor = ceil(max * MIN_ALARM_VOLUME_FRACTION).toInt()
    return if (current < floor) floor else null
}

internal fun Random.nextFloat(from: Float, until: Float): Float = from + nextFloat() * (until - from)

private fun Random.nextSirenParams(): SirenParams {
    val sweepMillis = nextInt(AlarmSoundDefaults.MIN_SIREN_SWEEP_MILLIS, AlarmSoundDefaults.MAX_SIREN_SWEEP_MILLIS + 1)
    val pulseMillis = nextInt(AlarmSoundDefaults.MIN_SIREN_PULSE_MILLIS, AlarmSoundDefaults.MAX_SIREN_PULSE_MILLIS + 1)
    return SirenParams(
        lowHz = nextFloat(AlarmSoundDefaults.MIN_SIREN_LOW_HZ, AlarmSoundDefaults.MAX_SIREN_LOW_HZ),
        highHz = nextFloat(AlarmSoundDefaults.MIN_SIREN_HIGH_HZ, AlarmSoundDefaults.MAX_SIREN_HIGH_HZ),
        sweepMillis = sweepMillis,
        pulsesPerSweep = (sweepMillis / pulseMillis).coerceAtLeast(1),
        duty = nextFloat(AlarmSoundDefaults.MIN_SIREN_DUTY, AlarmSoundDefaults.MAX_SIREN_DUTY),
        harmonic = nextFloat(0f, AlarmSoundDefaults.MAX_SIREN_HARMONIC),
    )
}

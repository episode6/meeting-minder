package com.episode6.meetingminder.alarm

import assertk.all
import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.each
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import org.junit.Test
import kotlin.math.abs

/** The randomised obnoxious alert of TODO.md §4.4, as a deterministic draw. */
class AlarmSoundRecipeTest {

    private val system = List(10) { AlarmSound.System("content://media/internal/audio/media/$it", "Ringtone $it") }
    private val bundled = List(8) { AlarmSound.Bundled("Bundled $it") }
    private val catalog = SoundCatalog(system, bundled)

    private fun recipe(
        seed: Int,
        recentlyUsed: Set<String> = emptySet(),
        catalog: SoundCatalog = this.catalog,
    ) = AlarmSoundRecipe(seed, catalog, recentlyUsed)

    private fun firstSounds(catalog: SoundCatalog = this.catalog, seeds: Int = 20_000) =
        (0 until seeds).map { recipe(it, catalog = catalog).segments().first().sound }

    private fun List<AlarmSound>.share(predicate: (AlarmSound) -> Boolean): Double = count(predicate).toDouble() / size

    @Test
    fun sameSeed_drawsTheSameSegments_soASnoozedAlarmSoundsTheSame() {
        assertThat(recipe(42).segments().take(30).toList()).isEqualTo(recipe(42).segments().take(30).toList())
    }

    @Test
    fun differentSeeds_drawDifferentAlarms() {
        val openings = (1..20).map { recipe(it).segments().take(3).toList() }.toSet()

        assertThat(openings.size > 15).isTrue()
    }

    @Test
    fun sources_areWeightedSixtyThirtyTen() {
        val sounds = firstSounds()

        assertThat(abs(sounds.share { it is AlarmSound.System } - 0.6) < 0.02).isTrue()
        assertThat(abs(sounds.share { it is AlarmSound.Bundled } - 0.3) < 0.02).isTrue()
        assertThat(abs(sounds.share { it is AlarmSound.Siren } - 0.1) < 0.02).isTrue()
    }

    @Test
    fun reRolls_keepTheWeights() {
        val sounds = recipe(7).segments().drop(1).take(20_000).map { it.sound }.toList()

        assertThat(abs(sounds.share { it is AlarmSound.System } - 0.6) < 0.02).isTrue()
        assertThat(abs(sounds.share { it is AlarmSound.Siren } - 0.1) < 0.02).isTrue()
    }

    /** Settings → Alarm sounds with the bundled group and the siren unchecked: device ringtones alone. */
    @Test
    fun withBundledAndSirenUnchecked_onlySystemSoundsPlay() {
        val systemOnly = catalog.without((bundled.map { it.id } + AlarmSound.SIREN_ID).toSet())

        assertThat(firstSounds(catalog = systemOnly, seeds = 2_000)).each { it.isInstanceOf(AlarmSound.System::class) }
        assertThat(recipe(3, catalog = systemOnly).segments().take(200).map { it.sound }.toList())
            .each { it.isInstanceOf(AlarmSound.System::class) }
    }

    /** The system group unchecked: the bundled OGGs and the siren share its weight. */
    @Test
    fun withSystemUnchecked_theInAppSoundsPlay_theSirenIncluded() {
        val sounds = firstSounds(catalog = catalog.without(system.map { it.id }.toSet()))

        assertThat(sounds.none { it is AlarmSound.System }).isTrue()
        assertThat(abs(sounds.share { it is AlarmSound.Siren } - 0.25) < 0.02).isTrue()
    }

    /** One sound unchecked: never drawn, first or re-roll, and the rest of its group still is. */
    @Test
    fun anUncheckedSound_isNeverDrawn() {
        val unchecked = catalog.without(setOf(system[3].id, bundled[5].id))

        val sounds = (0 until 300).flatMap { recipe(it, catalog = unchecked).segments().take(10).map { segment -> segment.sound }.toList() }

        assertThat(sounds.none { it == system[3] || it == bundled[5] }).isTrue()
        assertThat(sounds.any { it == system[2] }).isTrue()
        assertThat(sounds.any { it == bundled[4] }).isTrue()
    }

    @Test
    fun everySoundUnchecked_stillRings_withTheSiren() {
        val nothing = catalog.without((system + bundled).map { it.id }.toSet() + AlarmSound.SIREN_ID)

        assertThat(recipe(1, catalog = nothing).segments().take(20).map { it.sound }.toList())
            .each { it.isInstanceOf(AlarmSound.Siren::class) }
    }

    @Test
    fun aSourceWithNothingToOffer_isSkipped() {
        val noRingtones = SoundCatalog(system = emptyList(), bundled = bundled)

        val sounds = firstSounds(catalog = noRingtones)

        assertThat(sounds.none { it is AlarmSound.System }).isTrue()
        assertThat(abs(sounds.share { it is AlarmSound.Bundled } - 0.75) < 0.02).isTrue()
    }

    @Test
    fun nothingAtAllToOffer_fallsBackToTheSiren() {
        val empty = SoundCatalog(emptyList(), emptyList())

        assertThat(recipe(1, catalog = empty.copy(siren = false)).segments().take(20).map { it.sound }.toList())
            .each { it.isInstanceOf(AlarmSound.Siren::class) }
    }

    @Test
    fun firstSound_avoidsTheRecentlyUsedOnes() {
        val keptSystem = system[4]
        val keptBundled = bundled[2]
        val recent = (system - keptSystem + bundled - keptBundled).map { it.id }.toSet()

        val openings = (0 until 500).map { recipe(it, recentlyUsed = recent).segments().first().sound }

        assertThat(openings.filterNot { it is AlarmSound.Siren }.toSet()).containsOnly(keptSystem, keptBundled)
    }

    @Test
    fun whenEverySoundWasRecentlyUsed_itStillRings() {
        val recent = (system + bundled).map { it.id }.toSet()

        val openings = (0 until 200).map { recipe(it, recentlyUsed = recent).segments().first().sound }

        assertThat(openings.any { it is AlarmSound.System }).isTrue()
    }

    @Test
    fun reRolls_neverRepeatTheSoundThatJustPlayed() {
        for (seed in 0 until 50) {
            recipe(seed).segments().take(40).zipWithNext().forEach { (previous, next) ->
                if (previous.sound !is AlarmSound.Siren) assertThat(next.sound.id).isNotEqualTo(previous.sound.id)
            }
        }
    }

    @Test
    fun playbackParamsAndSegmentLengths_stayInTheirRanges() {
        val segments = (0 until 200).flatMap { recipe(it).segments().take(10).toList() }

        assertThat(segments).each {
            it.transform { segment -> segment.speed }.isBetween(AlarmSoundDefaults.MIN_SPEED, AlarmSoundDefaults.MAX_SPEED)
            it.transform { segment -> segment.pitch }.isBetween(AlarmSoundDefaults.MIN_PITCH, AlarmSoundDefaults.MAX_PITCH)
            it.transform { segment -> segment.durationMillis }.isBetween(8_000L, 12_000L)
        }
    }

    @Test
    fun sirenParams_stayInTheirRanges() {
        val sirens = (0 until 5_000).map { recipe(it).segments().first().sound }.filterIsInstance<AlarmSound.Siren>().map { it.params }

        assertThat(sirens.size > 300).isTrue()
        assertThat(sirens).each {
            it.all {
                transform { params -> params.lowHz }.isBetween(500f, 900f)
                transform { params -> params.highHz }.isBetween(1_200f, 2_400f)
                transform { params -> params.sweepMillis }.isBetween(300, 1_600)
                transform { params -> params.sweepMillis / params.pulsesPerSweep }.isBetween(150, 1_600)
                transform { params -> params.duty }.isBetween(0.4f, 0.85f)
                transform { params -> params.harmonic }.isBetween(0f, 0.45f)
            }
        }
    }

    @Test
    fun vibration_isFourToEightSegmentsOf80To700Millis_startingAtOnce() {
        for (seed in 0 until 500) {
            val timings = alarmVibrationTimings(seed)

            assertThat(timings[0]).isEqualTo(0L)
            assertThat(timings.size - 1 in setOf(4, 6, 8)).isTrue()
            assertThat(timings.drop(1)).each { it.isBetween(80L, 700L) }
        }
        assertThat(alarmVibrationTimings(9).toList()).isEqualTo(alarmVibrationTimings(9).toList())
    }

    @Test
    fun volumeRamp_goesFromAQuarterToFullOverFifteenSeconds() {
        assertThat(rampVolumeAt(0)).isEqualTo(0.25f)
        assertThat(rampVolumeAt(7_500)).isEqualTo(0.625f)
        assertThat(rampVolumeAt(15_000)).isEqualTo(1f)
        assertThat(rampVolumeAt(60_000)).isEqualTo(1f)
    }

    @Test
    fun alarmVolume_isRaisedToAtLeastHalf_andLeftAloneWhenLoudEnough() {
        assertThat(raisedAlarmVolume(current = 1, max = 7)).isEqualTo(4)
        assertThat(raisedAlarmVolume(current = 0, max = 16)).isEqualTo(8)
        assertThat(raisedAlarmVolume(current = 4, max = 7)).isEqualTo(null)
        assertThat(raisedAlarmVolume(current = 7, max = 7)).isEqualTo(null)
    }
}

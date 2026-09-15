package com.episode6.meetingminder.alarm

import assertk.assertThat
import assertk.assertions.each
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Test
import kotlin.math.abs

/** The synthesised siren: its sweep, its gating, and that it loops without a click. */
class AlarmSirenTest {

    private val sampleRate = 22_050
    private val params = SirenParams(lowHz = 600f, highHz = 1_800f, sweepMillis = 1_200, pulsesPerSweep = 4, duty = 0.8f, harmonic = 0.3f)
    private val pcm = renderSiren(params, sampleRate)
    private val pulseFrames = 1_200 * sampleRate / 1000 / 4
    private val onFrames = (pulseFrames * 0.8f).toInt()

    /** Frequency from zero crossings over [millis] starting at [startMillis]. */
    private fun frequencyAt(startMillis: Int, millis: Int = 20): Double {
        val from = startMillis * sampleRate / 1000
        val until = from + millis * sampleRate / 1000
        val crossings = (from + 1 until until).count { (pcm[it - 1] < 0) != (pcm[it] < 0) }
        return crossings / 2.0 / (millis / 1000.0)
    }

    @Test
    fun rendersExactlyOneSweepOfWholePulses() {
        assertThat(pcm.size).isEqualTo(pulseFrames * 4)
    }

    @Test
    fun everyPulseEndsSilent_soTheBufferLoopsWithoutAClick() {
        for (pulse in 0 until 4) {
            val start = pulse * pulseFrames
            assertThat((start + onFrames until start + pulseFrames).map { pcm[it].toInt() }).each { it.isEqualTo(0) }
        }
        assertThat(pcm.last().toInt()).isEqualTo(0)
        assertThat(pcm.first().toInt()).isEqualTo(0)
    }

    @Test
    fun sweepsFromTheLowFrequencyUpToTheHighOneAndBack() {
        // pulse 0 starts the sweep at 600 Hz; halfway (pulse 2) it peaks at 1.8 kHz
        assertThat(frequencyAt(startMillis = 6)).isBetween(560.0, 720.0)
        assertThat(frequencyAt(startMillis = 606)).isBetween(1_650.0, 1_900.0)
        assertThat(frequencyAt(startMillis = 906)).isBetween(1_050.0, 1_300.0)
    }

    @Test
    fun neverClips() {
        assertThat(pcm.all { abs(it.toInt()) <= (0.9 * Short.MAX_VALUE).toInt() + 1 }).isTrue()
        assertThat(pcm.maxOf { abs(it.toInt()) } > Short.MAX_VALUE / 2).isTrue()
    }

    @Test
    fun aSinglePulseSweep_stillLoopsSilent() {
        val single = renderSiren(params.copy(pulsesPerSweep = 1, duty = 0.4f), sampleRate)

        assertThat(single.size).isEqualTo(1_200 * sampleRate / 1000)
        assertThat(single.last().toInt()).isEqualTo(0)
    }
}

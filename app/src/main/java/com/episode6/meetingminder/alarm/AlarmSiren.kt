package com.episode6.meetingminder.alarm

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The siren's PCM sample rate: plenty for a 2.4 kHz sweep and its second harmonic. */
const val SIREN_SAMPLE_RATE = 22_050

/** Peak level of the rendered siren, just under full scale so the harmonic can't clip. */
private const val SIREN_AMPLITUDE = 0.9

/** Fade at every pulse edge; a hard gate would click. */
private const val SIREN_EDGE_MILLIS = 5

/**
 * One draw of the synthesised siren (TODO.md §4.4): the tone sweeps [lowHz] → [highHz] and
 * back over [sweepMillis], chopped into [pulsesPerSweep] pulses that each sound for [duty]
 * of their length, with a [harmonic] (0–0.45) of its octave mixed in.
 */
data class SirenParams(
    val lowHz: Float,
    val highHz: Float,
    val sweepMillis: Int,
    val pulsesPerSweep: Int,
    val duty: Float,
    val harmonic: Float,
)

/**
 * Renders exactly one sweep of [params] as 16-bit mono PCM, sized to a whole number of
 * pulses. Every pulse ends silent (duty < 1, faded edges), so the buffer loops seamlessly
 * with `AudioTrack.setLoopPoints` even though the oscillator's phase doesn't line up at
 * the loop point. Pure, so the sweep and the gating are unit-tested.
 */
fun renderSiren(params: SirenParams, sampleRate: Int = SIREN_SAMPLE_RATE): ShortArray {
    val pulseFrames = params.sweepMillis * sampleRate / 1000 / params.pulsesPerSweep
    val frames = pulseFrames * params.pulsesPerSweep
    val onFrames = (pulseFrames * params.duty).toInt()
    val edgeFrames = min(SIREN_EDGE_MILLIS * sampleRate / 1000, onFrames / 2).coerceAtLeast(1)
    val out = ShortArray(frames)
    var phase = 0.0
    for (i in 0 until frames) {
        val progress = i.toDouble() / frames
        val triangle = if (progress < 0.5) progress * 2 else (1 - progress) * 2
        val frequency = params.lowHz + (params.highHz - params.lowHz) * triangle
        phase += 2 * PI * frequency / sampleRate
        val inPulse = i % pulseFrames
        val gate = when {
            inPulse >= onFrames -> 0.0
            inPulse < edgeFrames -> inPulse.toDouble() / edgeFrames
            onFrames - inPulse < edgeFrames -> (onFrames - inPulse).toDouble() / edgeFrames
            else -> 1.0
        }
        val tone = (sin(phase) + params.harmonic * sin(2 * phase)) / (1 + params.harmonic)
        out[i] = (tone * gate * SIREN_AMPLITUDE * Short.MAX_VALUE).roundToInt().toShort()
    }
    return out
}

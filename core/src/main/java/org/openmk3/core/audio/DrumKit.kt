package org.openmk3.core.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A one-shot sample at 48 kHz: [data] is mono, or interleaved L/R when [channels] is 2.
 * [path] is the WAV it came from (null for the generated sounds).
 */
class Sample(
    val name: String,
    val data: FloatArray,
    val chokeGroup: Int = 0,
    val channels: Int = 1,
    val path: String? = null,
    val info: String = "built-in",
) {
    val frames get() = data.size / channels
    fun withChoke(group: Int) = Sample(name, data, group, channels, path, info)
}

/**
 * 16 procedurally generated drum sounds, so the engine can be tested before sample
 * loading exists. Slot order follows the pads: slot 0 = bottom-left.
 */
object DrumKit {

    fun build(sr: Int): Array<Sample> {
        val rnd = Random(1234)
        fun noise() = rnd.nextFloat() * 2f - 1f
        fun len(sec: Double) = (sec * sr).toInt()

        /** Sine with an exponential pitch drop, like an analogue drum. */
        fun drum(name: String, f0: Double, f1: Double, pitchDecay: Double, ampDecay: Double, dur: Double, click: Float = 0f): Sample {
            val n = len(dur)
            val out = FloatArray(n)
            var ph = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val f = f1 + (f0 - f1) * exp(-t / pitchDecay)
                ph += 2 * PI * f / sr
                var s = sin(ph) * exp(-t / ampDecay)
                if (i < sr / 500) s += click * noise() * (1 - i * 500.0 / sr)
                out[i] = s.toFloat()
            }
            return Sample(name, out)
        }

        /** High-passed noise burst. */
        fun hat(name: String, decay: Double, dur: Double, choke: Int, hp: Float = 0.85f): Sample {
            val n = len(dur)
            val out = FloatArray(n)
            var y = 0f
            var xp = 0f
            for (i in 0 until n) {
                val x = noise()
                y = hp * (y + x - xp)
                xp = x
                out[i] = (y * exp(-i.toDouble() / sr / decay)).toFloat() * 0.7f
            }
            return Sample(name, out, choke)
        }

        fun snare(): Sample {
            val n = len(0.35)
            val out = FloatArray(n)
            var ph = 0.0
            var y = 0f
            var xp = 0f
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                ph += 2 * PI * (180 + 60 * exp(-t / 0.01)) / sr
                val x = noise()
                y = 0.6f * (y + x - xp); xp = x
                out[i] = (0.55 * sin(ph) * exp(-t / 0.06) + 0.7 * y * exp(-t / 0.09)).toFloat()
            }
            return Sample("SNARE", out)
        }

        fun clap(): Sample {
            val n = len(0.4)
            val out = FloatArray(n)
            var lp = 0f
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val burst = when {
                    t < 0.01 -> exp(-t / 0.003)
                    t < 0.02 -> exp(-(t - 0.01) / 0.003)
                    t < 0.03 -> exp(-(t - 0.02) / 0.003)
                    else -> exp(-(t - 0.03) / 0.11)
                }
                lp += 0.35f * (noise() - lp)
                out[i] = (lp * burst * 2.2).toFloat()
            }
            return Sample("CLAP", out)
        }

        fun tone(name: String, freqs: DoubleArray, decay: Double, dur: Double, square: Boolean = false): Sample {
            val n = len(dur)
            val out = FloatArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                var s = 0.0
                for (f in freqs) {
                    val v = sin(2 * PI * f * t)
                    s += if (square) (if (v >= 0) 1.0 else -1.0) * 0.5 else v
                }
                out[i] = (s / freqs.size * exp(-t / decay)).toFloat()
            }
            return Sample(name, out)
        }

        fun stab(): Sample {
            val n = len(0.6)
            val out = FloatArray(n)
            val freqs = doubleArrayOf(261.6, 311.1, 392.0, 523.3)
            var lp = 0f
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                var s = 0.0
                for (f in freqs) s += 2 * ((f * t) % 1.0) - 1 // saw
                val cutoff = (0.05 + 0.5 * exp(-t / 0.08)).toFloat()
                lp += cutoff * ((s / freqs.size).toFloat() - lp)
                out[i] = (lp * exp(-t / 0.25)).toFloat()
            }
            return Sample("STAB", out)
        }

        fun zap(): Sample {
            val n = len(0.2)
            val out = FloatArray(n)
            var ph = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                ph += 2 * PI * (100 + 2500 * exp(-t / 0.025)) / sr
                out[i] = (sin(ph) * exp(-t / 0.06)).toFloat()
            }
            return Sample("ZAP", out)
        }

        val kit = arrayOf(
            // bottom row
            drum("KICK", 180.0, 48.0, 0.03, 0.28, 0.6, click = 0.45f),
            snare(),
            hat("CL HAT", 0.035, 0.12, choke = 1),
            hat("OP HAT", 0.30, 0.8, choke = 1),
            // second row
            clap(),
            drum("LO TOM", 140.0, 85.0, 0.05, 0.25, 0.6),
            drum("MID TOM", 200.0, 125.0, 0.05, 0.22, 0.5),
            drum("HI TOM", 280.0, 180.0, 0.05, 0.2, 0.45),
            // third row
            tone("RIM", doubleArrayOf(820.0, 1640.0), 0.012, 0.06),
            tone("COWBELL", doubleArrayOf(540.0, 800.0), 0.12, 0.4, square = true),
            hat("CRASH", 0.6, 2.0, choke = 0, hp = 0.75f),
            hat("SHAKER", 0.04, 0.12, choke = 0, hp = 0.95f),
            // top row
            drum("SUB", 70.0, 42.0, 0.08, 0.6, 1.4),
            zap(),
            tone("BLIP", doubleArrayOf(880.0), 0.05, 0.2),
            stab(),
        )
        // Fade the last 15 ms (or quarter) of every sound to zero: several sounds are still
        // audible when their data ends, and an abrupt stop is heard as a click.
        for (s in kit) {
            val d = s.data
            val fade = minOf(sr * 15 / 1000, d.size / 4)
            for (k in 0 until fade) d[d.size - 1 - k] *= k.toFloat() / fade
        }
        // Normalise every sound to the same peak so velocity is the only loudness control.
        for (s in kit) {
            var peak = 0f
            for (v in s.data) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
            if (peak > 0) {
                val g = 0.8f / peak
                for (i in s.data.indices) s.data[i] *= g
            }
        }
        return kit
    }
}

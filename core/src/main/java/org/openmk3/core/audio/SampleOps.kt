package org.openmk3.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Destructive sample edits on stereo interleaved audio, applied to a selection [a, b) in frames.
 * Every function returns a new array (the input is never changed), so the old version can be
 * kept for undo and a pad that still plays the old one is not disturbed.
 */
object SampleOps {

    enum class Fn(val label: String, val help: String) {
        TRUNCATE("TRUNCATE", "Keep only the selection"),
        NORMALIZE("NORMALIZE", "Raise the selection to full level"),
        REVERSE("REVERSE", "Play the selection backwards"),
        FADE_IN("FADE IN", "Fade the selection up from silence"),
        FADE_OUT("FADE OUT", "Fade the selection down to silence"),
        DC_FIX("DC FIX", "Remove a DC offset from the selection"),
        SILENCE("SILENCE", "Mute the selection"),
        CUT("CUT", "Remove the selection, keep it to paste"),
        COPY("COPY", "Keep the selection to paste"),
        PASTE("PASTE", "Insert the copied audio at the selection start"),
        DUPLICATE("DUPLICATE", "Repeat the selection right after itself"),
        STRETCH("STRETCH", "Change the selection's length, same pitch"),
    }

    /** What CUT / COPY kept (shared by all pages and pads). */
    @Volatile var clipboard: FloatArray? = null

    class Result(val data: FloatArray, val selA: Int, val selB: Int, val changed: Boolean = true)

    /** Applies [fn] to frames [a, b) of [d]. [amount] is the STRETCH ratio (0.25..4). */
    fun apply(fn: Fn, d: FloatArray, a0: Int, b0: Int, amount: Float = 1f): Result {
        val frames = d.size / 2
        val a = a0.coerceIn(0, frames)
        val b = b0.coerceIn(a, frames)
        val len = b - a
        return when (fn) {
            Fn.TRUNCATE -> Result(d.copyOfRange(a * 2, b * 2).also { fadeEdges(it) }, 0, len)
            Fn.NORMALIZE -> {
                var peak = 0f
                for (i in a * 2 until b * 2) peak = max(peak, abs(d[i]))
                if (peak < 1e-6f) return Result(d, a, b, false)
                val g = 0.977f / peak  // -0.2 dBFS
                val o = d.copyOf()
                for (i in a * 2 until b * 2) o[i] *= g
                Result(o, a, b)
            }
            Fn.REVERSE -> {
                val o = d.copyOf()
                for (f in 0 until len) { val s = (b - 1 - f) * 2; o[(a + f) * 2] = d[s]; o[(a + f) * 2 + 1] = d[s + 1] }
                Result(o, a, b)
            }
            Fn.FADE_IN, Fn.FADE_OUT -> {
                val o = d.copyOf()
                for (f in 0 until len) {
                    val t = f.toFloat() / max(1, len - 1)
                    // equal-power curve: sounds even, no dip in the middle
                    val g = if (fn == Fn.FADE_IN) sin(t * PI / 2).toFloat() else cos(t * PI / 2).toFloat()
                    o[(a + f) * 2] *= g; o[(a + f) * 2 + 1] *= g
                }
                Result(o, a, b)
            }
            Fn.DC_FIX -> {
                if (len == 0) return Result(d, a, b, false)
                var m0 = 0.0; var m1 = 0.0
                for (f in a until b) { m0 += d[f * 2]; m1 += d[f * 2 + 1] }
                m0 /= len; m1 /= len
                val o = d.copyOf()
                for (f in a until b) { o[f * 2] -= m0.toFloat(); o[f * 2 + 1] -= m1.toFloat() }
                Result(o, a, b)
            }
            Fn.SILENCE -> {
                val o = d.copyOf()
                java.util.Arrays.fill(o, a * 2, b * 2, 0f)
                Result(o, a, b)
            }
            Fn.COPY -> {
                clipboard = d.copyOfRange(a * 2, b * 2)
                Result(d, a, b, false)
            }
            Fn.CUT -> {
                if (len >= frames) return Result(d, a, b, false)  // never empty the sample
                clipboard = d.copyOfRange(a * 2, b * 2)
                val o = FloatArray((frames - len) * 2)
                System.arraycopy(d, 0, o, 0, a * 2)
                System.arraycopy(d, b * 2, o, a * 2, (frames - b) * 2)
                declick(o, a)
                Result(o, a, a)
            }
            Fn.PASTE -> {
                val c = clipboard ?: return Result(d, a, b, false)
                val o = insert(d, a, c)
                Result(o, a, a + c.size / 2)
            }
            Fn.DUPLICATE -> {
                if (len == 0) return Result(d, a, b, false)
                val o = insert(d, b, d.copyOfRange(a * 2, b * 2))
                Result(o, b, b + len)
            }
            Fn.STRETCH -> {
                if (len < 4800 || abs(amount - 1f) < 0.005f) return Result(d, a, b, false)
                val s = stretch(d.copyOfRange(a * 2, b * 2), amount)
                val n = s.size / 2
                val o = FloatArray((frames - len + n) * 2)
                System.arraycopy(d, 0, o, 0, a * 2)
                System.arraycopy(s, 0, o, a * 2, s.size)
                System.arraycopy(d, b * 2, o, (a + n) * 2, (frames - b) * 2)
                Result(o, a, a + n)
            }
        }
    }

    private fun insert(d: FloatArray, at: Int, c: FloatArray): FloatArray {
        val o = FloatArray(d.size + c.size)
        System.arraycopy(d, 0, o, 0, at * 2)
        System.arraycopy(c, 0, o, at * 2, c.size)
        System.arraycopy(d, at * 2, o, at * 2 + c.size, d.size - at * 2)
        declick(o, at)
        declick(o, at + c.size / 2)
        return o
    }

    /** A 1 ms crossfade-like dip around a splice point removes the click of a jump. */
    private fun declick(o: FloatArray, at: Int) {
        val frames = o.size / 2
        val w = 48
        for (k in -w until w) {
            val f = at + k
            if (f < 0 || f >= frames) continue
            val g = abs(k + 0.5f) / w
            o[f * 2] *= g; o[f * 2 + 1] *= g
        }
    }

    /** Short fades at both ends so a cut-out piece starts and ends without a click. */
    private fun fadeEdges(o: FloatArray) {
        val frames = o.size / 2
        val w = min(64, frames / 2)
        for (k in 0 until w) {
            val g = k.toFloat() / w
            o[k * 2] *= g; o[k * 2 + 1] *= g
            val e = frames - 1 - k
            o[e * 2] *= g; o[e * 2 + 1] *= g
        }
    }

    /**
     * Time stretch by [ratio] (2 = twice as long) keeping the pitch: WSOLA (overlap-add of
     * Hann-windowed grains, each placed where it best continues the previous one, so the
     * waveform stays coherent and transients don't smear into echoes).
     */
    fun stretch(x: FloatArray, ratio: Float): FloatArray {
        val r = ratio.coerceIn(0.25f, 4f)
        val inN = x.size / 2
        val n = 2048                 // grain: ~43 ms
        val hs = n / 2               // output hop
        val ha = hs / r              // analysis hop
        val tol = 512                // search window, frames
        val outN = (inN * r).toInt()
        val out = FloatArray((outN + n) * 2)
        val norm = FloatArray(outN + n)
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val mono = FloatArray(inN) { (x[it * 2] + x[it * 2 + 1]) * 0.5f }
        var prev = 0                 // analysis position of the last grain
        var k = 0
        while (k * hs < outN) {
            val nominal = (k * ha).toInt()
            var best = nominal
            if (k > 0) {
                val target = prev + hs   // what naturally follows the last grain
                var bestC = -Float.MAX_VALUE
                fun corr(p: Int): Float {
                    var c = 0f
                    var i = 0
                    while (i < n / 2) {
                        val pi = p + i; val ti = target + i
                        if (pi in 0 until inN && ti in 0 until inN) c += mono[pi] * mono[ti]
                        i += 4
                    }
                    return c
                }
                // coarse search, then refine around the best
                var d = -tol
                while (d <= tol) {
                    val p = nominal + d
                    if (p in 0 until inN) { val c = corr(p); if (c > bestC) { bestC = c; best = p } }
                    d += 8
                }
                val c0 = best
                for (dd in -8..8) {
                    val p = c0 + dd
                    if (p in 0 until inN) { val c = corr(p); if (c > bestC) { bestC = c; best = p } }
                }
            }
            val o = k * hs
            for (i in 0 until n) {
                val s = best + i
                if (s >= inN) break
                val w = win[i]
                out[(o + i) * 2] += x[s * 2] * w
                out[(o + i) * 2 + 1] += x[s * 2 + 1] * w
                norm[o + i] += w
            }
            prev = best
            k++
        }
        val res = FloatArray(outN * 2)
        for (f in 0 until outN) {
            val g = if (norm[f] > 1e-3f) 1f / norm[f] else 0f
            res[f * 2] = out[f * 2] * g
            res[f * 2 + 1] = out[f * 2 + 1] * g
        }
        return res
    }
}

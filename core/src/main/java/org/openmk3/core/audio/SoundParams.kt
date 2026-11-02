package org.openmk3.core.audio

import android.content.Context
import org.json.JSONArray
import org.openmk3.core.AppLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Per-sound playback parameters, shared with the audio thread through a direct buffer
 * (layout must match P_* in engine.cpp). Saved as sound_params.json.
 */
class SoundParams {

    /** One parameter: how it's stored, edited with a knob, and shown on screen. */
    class Def(
        val index: Int,
        val name: String,
        val min: Float,
        val max: Float,
        val default: Float,
        /** Value change per knob unit (linear), or null for [curve] editing. */
        val step: Float,
        val format: (Float) -> String,
        /** Exponential parameters (time, frequency) are edited in log space. */
        val log: Boolean = false,
        /** Discrete choices shown by name (value = index). */
        val choices: List<String>? = null,
    )

    companion object {
        const val SLOTS = 17
        const val COUNT = 16

        const val TUNE = 0
        const val VOL = 1
        const val PAN = 2
        const val START = 3
        const val END = 4
        const val REV = 5
        const val ENV = 6
        const val ATT = 7
        const val HOLD = 8
        const val DEC = 9
        const val FTYPE = 10
        const val CUT = 11
        const val RES = 12

        private fun secs(v: Float) = if (v < 1f) "${(v * 1000).roundToInt()} ms" else "%.2f s".format(v)
        private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

        val DEFS: Map<Int, Def> = listOf(
            Def(TUNE, "TUNE", -36f, 36f, 0f, 0.01f, { v -> "%+.2f st".format(v) }),
            Def(VOL, "VOLUME", -60f, 12f, 0f, 0.05f, { v -> if (v <= -59.9f) "-∞ dB" else "%+.1f dB".format(v) }),
            Def(PAN, "PAN", -1f, 1f, 0f, 0.002f, { v ->
                val p = (v * 100).roundToInt()
                when { p == 0 -> "C"; p < 0 -> "L${-p}"; else -> "R$p" }
            }),
            Def(START, "START", 0f, 1f, 0f, 0.0005f, ::pct),
            Def(END, "END", 0f, 1f, 1f, 0.0005f, ::pct),
            Def(REV, "REVERSE", 0f, 1f, 0f, 1f, { "" }, choices = listOf("OFF", "ON")),
            Def(ENV, "MODE", 0f, 1f, 0f, 1f, { "" }, choices = listOf("ONE-SHOT", "AHD")),
            Def(ATT, "ATTACK", 0.0005f, 5f, 0.001f, 0.004f, ::secs, log = true),
            Def(HOLD, "HOLD", 0.0005f, 5f, 0.05f, 0.004f, ::secs, log = true),
            Def(DEC, "DECAY", 0.005f, 10f, 0.5f, 0.004f, ::secs, log = true),
            Def(FTYPE, "FILTER", 0f, 3f, 0f, 1f, { "" }, choices = listOf("OFF", "LOW-PASS", "BAND-PASS", "HIGH-PASS")),
            Def(CUT, "CUTOFF", 20f, 20000f, 20000f, 0.004f, { v -> if (v >= 1000f) "%.1f kHz".format(v / 1000f) else "${v.roundToInt()} Hz" }, log = true),
            Def(RES, "RESONANCE", 0f, 1f, 0f, 0.002f, ::pct),
        ).associateBy { it.index }

        private fun file(ctx: Context) = File(ctx.filesDir, "sound_params.json")

        fun load(ctx: Context): SoundParams {
            val p = SoundParams()
            val f = file(ctx)
            if (!f.exists()) return p
            try {
                val a = JSONArray(f.readText())
                for (s in 0 until minOf(16, a.length())) {
                    val row = a.getJSONArray(s)
                    for (i in 0 until minOf(COUNT, row.length())) {
                        val d = DEFS[i] ?: continue
                        p[s, i] = row.getDouble(i).toFloat().coerceIn(d.min, d.max)
                    }
                }
            } catch (e: Exception) {
                AppLog.i("sound_params.json unreadable: $e")
            }
            return p
        }
    }

    val buffer: ByteBuffer = ByteBuffer.allocateDirect(SLOTS * COUNT * 4).order(ByteOrder.nativeOrder())
    private val f = buffer.asFloatBuffer()

    init {
        for (s in 0 until SLOTS) reset(s)
    }

    operator fun get(slot: Int, i: Int): Float = f.get(slot * COUNT + i)
    operator fun set(slot: Int, i: Int, v: Float) { f.put(slot * COUNT + i, v) }

    fun reset(slot: Int) {
        for ((i, d) in DEFS) this[slot, i] = d.default
    }

    /** Start/end belong to a particular sample: reset them when the pad gets a new one. */
    fun resetRange(slot: Int) {
        this[slot, START] = 0f
        this[slot, END] = 1f
    }

    /** Applies [delta] knob units to parameter [i] of [slot]; [fine] = 10x finer. */
    fun nudge(slot: Int, i: Int, delta: Int, fine: Boolean) {
        val d = DEFS[i] ?: return
        val cur = this[slot, i]
        val k = if (fine) 0.1f else 1f
        val next = when {
            d.choices != null -> (cur.roundToInt() + if (delta > 0) 1 else -1).toFloat()
            d.log -> cur * 2f.pow(delta * d.step * k * 2.5f)
            else -> cur + delta * d.step * k
        }
        this[slot, i] = next.coerceIn(d.min, d.max)
        if (i == START && this[slot, START] > this[slot, END] - 0.001f) this[slot, START] = (this[slot, END] - 0.001f).coerceAtLeast(0f)
        if (i == END && this[slot, END] < this[slot, START] + 0.001f) this[slot, END] = (this[slot, START] + 0.001f).coerceAtMost(1f)
    }

    fun display(slot: Int, i: Int): String {
        val d = DEFS[i] ?: return ""
        val v = this[slot, i]
        return d.choices?.getOrNull(v.roundToInt()) ?: d.format(v)
    }

    fun save(ctx: Context) {
        val a = JSONArray()
        for (s in 0 until 16) {
            val row = JSONArray()
            for (i in 0 until COUNT) row.put(this[s, i].toDouble())
            a.put(row)
        }
        file(ctx).writeText(a.toString())
    }
}

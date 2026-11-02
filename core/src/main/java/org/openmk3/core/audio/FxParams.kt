package org.openmk3.core.audio

import android.content.Context
import org.json.JSONArray
import org.openmk3.core.AppLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Effect slots of every chain (128 sounds, 8 groups, master; 4 slots each), shared with the
 * audio thread through a direct buffer (layout must match engine.cpp / fx.h):
 * per slot 16 floats: [0] type, [1] bypass, [2..15] the effect's parameters.
 */
class FxParams {

    /** One effect parameter (index 2..15 in its slot). */
    class Def(
        val index: Int,
        val name: String,
        val min: Float,
        val max: Float,
        val default: Float,
        val step: Float,
        val format: (Float) -> String,
        val log: Boolean = false,
        val choices: List<String>? = null,
    )

    companion object {
        const val SLOTS = 4
        const val PER_SLOT = 16
        const val CHAINS = Pattern.SOUNDS + Pattern.GROUPS + 1
        const val GROUP0 = Pattern.SOUNDS
        const val MASTER = Pattern.SOUNDS + Pattern.GROUPS
        /** Sounds use slots 2..4 (slot 1 is the sound source, shown as the sampler / synth). */
        const val SOUND_FX_SLOTS = 3

        const val NONE = 0
        const val COMPRESSOR = 1
        const val CHORUS = 2
        const val DELAY = 3
        const val REVERB = 4
        val TYPES = listOf("EMPTY", "COMPRESSOR", "CHORUS", "DELAY", "REVERB")

        private fun ms(v: Float) = if (v < 1000f) "${v.roundToInt()} ms" else "%.2f s".format(v / 1000f)
        private fun secs(v: Float) = if (v < 10f) "%.1f s".format(v) else "${v.roundToInt()} s"
        private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
        private fun db(v: Float) = "%+.1f dB".format(v)
        private fun hz(v: Float) = if (v >= 1000f) "%.1f kHz".format(v / 1000f) else "${v.roundToInt()} Hz"
        private val OFF_ON = listOf("OFF", "ON")
        val DIVISIONS = listOf("1/32", "1/16T", "1/16", "1/16D", "1/8T", "1/8", "1/8D", "1/4T", "1/4", "1/4D", "1/2", "1/1")

        /** Parameters per effect type, in knob order. */
        val DEFS: Map<Int, List<Def>> = mapOf(
            COMPRESSOR to listOf(
                Def(2, "THRESHOLD", -60f, 0f, -18f, 0.05f, ::db),
                Def(3, "RATIO", 1f, 20f, 4f, 0.02f, { v -> "%.1f:1".format(v) }),
                Def(4, "ATTACK", 0.05f, 200f, 10f, 0.004f, { v -> if (v < 10f) "%.1f ms".format(v) else "${v.roundToInt()} ms" }, log = true),
                Def(5, "RELEASE", 5f, 3000f, 150f, 0.004f, ::ms, log = true),
                Def(6, "KNEE", 0f, 24f, 6f, 0.02f, { v -> "%.1f dB".format(v) }),
                Def(7, "MAKEUP", 0f, 24f, 0f, 0.02f, ::db),
                Def(8, "MIX", 0f, 1f, 1f, 0.002f, ::pct),
            ),
            CHORUS to listOf(
                Def(2, "RATE", 0.02f, 10f, 0.6f, 0.004f, { v -> "%.2f Hz".format(v) }, log = true),
                Def(3, "DEPTH", 0f, 1f, 0.5f, 0.002f, ::pct),
                Def(4, "MIX", 0f, 1f, 0.5f, 0.002f, ::pct),
                Def(5, "DELAY", 2f, 25f, 7f, 0.02f, { v -> "%.1f ms".format(v) }),
                Def(6, "FEEDBACK", -0.9f, 0.9f, 0f, 0.002f, ::pct),
                Def(7, "WIDTH", 0f, 1f, 1f, 0.002f, ::pct),
            ),
            DELAY to listOf(
                Def(2, "TIME", 0f, 2000f, 5f, 1f, ::ms),
                Def(3, "SYNC", 0f, 1f, 1f, 1f, { "" }, choices = OFF_ON),
                Def(4, "FEEDBACK", 0f, 1.1f, 0.45f, 0.002f, ::pct),
                Def(5, "MIX", 0f, 1f, 0.35f, 0.002f, ::pct),
                Def(6, "PING-PONG", 0f, 1f, 0f, 1f, { "" }, choices = OFF_ON),
                Def(7, "LOW CUT", 10f, 2000f, 80f, 0.004f, ::hz, log = true),
                Def(8, "HIGH CUT", 500f, 20000f, 9000f, 0.004f, ::hz, log = true),
                Def(9, "MODULATION", 0f, 1f, 0.1f, 0.002f, ::pct),
            ),
            REVERB to listOf(
                Def(2, "CHARACTER", 0f, 2f, 1f, 1f, { "" }, choices = listOf("DENSE", "LUSH", "VAST")),
                Def(3, "PRE-DELAY", 0f, 500f, 20f, 0.5f, ::ms),
                Def(4, "SIZE", 0f, 1f, 0.6f, 0.002f, ::pct),
                Def(5, "DECAY", 0.2f, 30f, 3f, 0.004f, ::secs, log = true),
                Def(6, "HIGH CUT", 500f, 20000f, 8000f, 0.004f, ::hz, log = true),
                Def(7, "LOW CUT", 10f, 1000f, 80f, 0.004f, ::hz, log = true),
                Def(8, "DIFFUSION", 0f, 1f, 0.7f, 0.002f, ::pct),
                Def(9, "MODULATION", 0f, 1f, 0.35f, 0.002f, ::pct),
                Def(10, "MIX", 0f, 1f, 0.3f, 0.002f, ::pct),
                Def(11, "FREEZE", 0f, 1f, 0f, 1f, { "" }, choices = OFF_ON),
                Def(12, "WIDTH", 0f, 2f, 1f, 0.004f, ::pct),
            ),
        )

        private fun file(ctx: Context) = File(ctx.filesDir, "fx.json")

        fun load(ctx: Context): FxParams {
            val p = FxParams()
            val f = file(ctx)
            if (!f.exists()) return p
            try {
                val a = JSONArray(f.readText())
                for (i in 0 until minOf(a.length(), CHAINS * SLOTS * PER_SLOT)) p.f.put(i, a.getDouble(i).toFloat())
            } catch (e: Exception) {
                AppLog.i("fx.json unreadable: $e")
            }
            return p
        }
    }

    val buffer: ByteBuffer = ByteBuffer.allocateDirect(CHAINS * SLOTS * PER_SLOT * 4).order(ByteOrder.nativeOrder())
    private val f = buffer.asFloatBuffer()
    private var acc = 0

    private fun base(chain: Int, slot: Int) = (chain * SLOTS + slot) * PER_SLOT
    operator fun get(chain: Int, slot: Int, i: Int): Float = f.get(base(chain, slot) + i)
    operator fun set(chain: Int, slot: Int, i: Int, v: Float) { f.put(base(chain, slot) + i, v) }

    fun type(chain: Int, slot: Int) = this[chain, slot, 0].roundToInt()
    fun bypassed(chain: Int, slot: Int) = this[chain, slot, 1] > 0.5f

    /** Sets the type and its default parameters (the caller tells the engine). */
    fun setType(chain: Int, slot: Int, type: Int) {
        for (i in 0 until PER_SLOT) this[chain, slot, i] = 0f
        this[chain, slot, 0] = type.toFloat()
        DEFS[type]?.forEach { d -> this[chain, slot, d.index] = d.default }
    }

    fun nudge(chain: Int, slot: Int, d: Def, delta: Int, fine: Boolean) {
        val cur = this[chain, slot, d.index]
        val k = if (fine) 0.1f else 1f
        // delay time is a division index while synced
        val synced = type(chain, slot) == DELAY && d.index == 2 && this[chain, slot, 3] > 0.5f
        val next = when {
            d.choices != null || synced -> {
                acc += delta
                if (abs(acc) < 25) return
                val dir = if (acc > 0) 1 else -1
                acc = 0
                val max = if (synced) (DIVISIONS.size - 1).toFloat() else d.max
                (cur.roundToInt() + dir).toFloat().coerceIn(d.min, max)
            }
            d.log -> cur * 2f.pow(delta * d.step * k * 2.5f)
            else -> cur + delta * d.step * k
        }
        this[chain, slot, d.index] = next.coerceIn(d.min, d.max)
    }

    fun display(chain: Int, slot: Int, d: Def): String {
        val v = this[chain, slot, d.index]
        if (type(chain, slot) == DELAY && d.index == 2 && this[chain, slot, 3] > 0.5f)
            return DIVISIONS[v.roundToInt().coerceIn(0, DIVISIONS.size - 1)]
        return d.choices?.getOrNull(v.roundToInt()) ?: d.format(v)
    }

    fun save(ctx: Context) {
        val a = JSONArray()
        for (i in 0 until CHAINS * SLOTS * PER_SLOT) a.put(f.get(i).toDouble())
        file(ctx).writeText(a.toString())
    }
}

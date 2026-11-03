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
        const val SLOTS = Pattern.SOUNDS + 1
        const val COUNT = 64

        // Indices match P_* in engine.cpp and are stable (saved settings depend on them).
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
        const val SUS = 13
        const val REL = 14
        const val POLY = 15
        const val ENGINE = 16
        const val LOOP = 17
        const val LSTART = 18
        const val LEND = 19
        const val XFADE = 20
        const val DRIVE = 21
        const val MATT = 22
        const val MDEC = 23
        const val MPITCH = 24
        const val MCUT = 25
        const val LWAVE = 26
        const val LRATE = 27
        const val LPITCH = 28
        const val LCUT = 29
        const val LPAN = 30
        const val LVOL = 31
        const val VVOL = 32
        const val VCUT = 33
        const val GATE = 34
        const val SRC = 35
        const val SMODEL = 36
        const val SNOTE = 37
        const val SHARM = 38
        const val STIMB = 39
        const val SMORPH = 40
        const val SDEC = 41
        const val SCOL = 42
        const val SLPG = 43
        const val SAUX = 44
        const val SFM = 45
        const val STMOD = 46
        const val SMMOD = 47
        const val COLOUR = 48
        const val BEND = 49     // live: touch strip pitch bend (semitones)
        const val MODW = 50     // live: touch strip modulation (0..1)
        const val FMBANK = 51
        const val FMPROG = 52
        const val FMBRIGHT = 53
        const val FMATT = 54
        const val FMREL = 55
        const val FMFB = 56
        const val CHOKE = 57
        const val LINK = 58

        const val SRC_SAMPLER = 0
        const val SRC_SYNTH = 1
        const val SRC_FM = 2
        const val SRC_POLY = 3
        /** FM banks: 3 built-in (96 voices), then the user's .syx cartridges. */
        val FM_BANKS = listOf("ROM 1", "ROM 2", "ROM 3") + (1..16).map { "USER $it" }

        /** Synth models (Mutable Instruments Plaits engines), described in our own words. */
        val MODELS = listOf(
            "VA + FILTER", "PHASE DIST", "6-OP FM 1", "6-OP FM 2", "6-OP FM 3", "WAVE TERRAIN",
            "STRING MACH", "CHIPTUNE", "ANALOG", "WAVESHAPE", "2-OP FM", "GRANULAR",
            "ADDITIVE", "WAVETABLE", "CHORDS", "SPEECH", "SWARM", "NOISE",
            "PARTICLES", "STRING", "MODAL", "BASS DRUM", "SNARE", "HI-HAT",
        )
        private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        /** Note name with 60 = C3 (the same as the keyboard pages). */
        fun noteName(n: Int) = NOTE_NAMES[((n % 12) + 12) % 12] + (n / 12 - 2)

        private fun secs(v: Float) = if (v < 1f) "${(v * 1000).roundToInt()} ms" else "%.2f s".format(v)
        private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
        private fun pctFine(v: Float) = "%.1f%%".format(v * 100)
        private fun semis(v: Float) = "%+.2f st".format(v)
        private fun octs(v: Float) = "%+.2f oct".format(v)
        private val OFF_ON = listOf("OFF", "ON")

        val DEFS: Map<Int, Def> = listOf(
            // voice
            Def(TUNE, "TUNE", -36f, 36f, 0f, 0.01f, ::semis),
            Def(VOL, "VOLUME", -60f, 12f, 0f, 0.05f, { v -> if (v <= -59.9f) "-∞ dB" else "%+.1f dB".format(v) }),
            Def(PAN, "PAN", -1f, 1f, 0f, 0.002f, { v ->
                val p = (v * 100).roundToInt()
                when { p == 0 -> "C"; p < 0 -> "L${-p}"; else -> "R$p" }
            }),
            Def(POLY, "POLYPHONY", 1f, 16f, 1f, 0.02f, { v -> "${v.roundToInt()}" }),
            Def(ENGINE, "ENGINE", 0f, 2f, 0f, 1f, { "" }, choices = listOf("STANDARD", "VINTAGE A", "VINTAGE B")),
            Def(REV, "REVERSE", 0f, 1f, 0f, 1f, { "" }, choices = OFF_ON),
            // sample region and loop
            Def(START, "START", 0f, 1f, 0f, 0.0005f, ::pctFine),
            Def(END, "END", 0f, 1f, 1f, 0.0005f, ::pctFine),
            Def(LOOP, "LOOP", 0f, 1f, 0f, 1f, { "" }, choices = OFF_ON),
            Def(LSTART, "LOOP START", 0f, 1f, 0f, 0.0005f, ::pctFine),
            Def(LEND, "LOOP END", 0f, 1f, 1f, 0.0005f, ::pctFine),
            Def(XFADE, "CROSSFADE", 0f, 1f, 0f, 0.002f, ::pct),
            // amplitude envelope
            Def(ENV, "TYPE", 0f, 2f, 0f, 1f, { "" }, choices = listOf("ONE-SHOT", "AHD", "ADSR")),
            Def(ATT, "ATTACK", 0.0005f, 10f, 0.001f, 0.004f, ::secs, log = true),
            Def(HOLD, "HOLD", 0.0005f, 10f, 0.05f, 0.004f, ::secs, log = true),
            Def(DEC, "DECAY", 0.005f, 20f, 0.5f, 0.004f, ::secs, log = true),
            Def(SUS, "SUSTAIN", 0f, 1f, 0.7f, 0.002f, ::pct),
            Def(REL, "RELEASE", 0.005f, 20f, 0.2f, 0.004f, ::secs, log = true),
            // filter
            Def(FTYPE, "FILTER", 0f, 4f, 0f, 1f, { "" }, choices = listOf("OFF", "LP 12", "BP", "HP", "LP 24")),
            Def(CUT, "CUTOFF", 20f, 20000f, 20000f, 0.004f, { v -> if (v >= 1000f) "%.1f kHz".format(v / 1000f) else "${v.roundToInt()} Hz" }, log = true),
            Def(RES, "RESONANCE", 0f, 1f, 0f, 0.002f, ::pct),
            Def(DRIVE, "DRIVE", 0f, 1f, 0f, 0.002f, ::pct),
            // modulation envelope
            Def(MATT, "ATTACK", 0.0005f, 10f, 0.001f, 0.004f, ::secs, log = true),
            Def(MDEC, "DECAY", 0.005f, 20f, 0.2f, 0.004f, ::secs, log = true),
            Def(MPITCH, "→ PITCH", -48f, 48f, 0f, 0.05f, ::semis),
            Def(MCUT, "→ CUTOFF", -8f, 8f, 0f, 0.01f, ::octs),
            // LFO
            Def(LWAVE, "WAVE", 0f, 4f, 0f, 1f, { "" }, choices = listOf("SINE", "TRIANGLE", "SAW", "SQUARE", "RANDOM")),
            Def(LRATE, "RATE", 0.01f, 40f, 4f, 0.004f, { v -> if (v < 1f) "%.2f Hz".format(v) else "%.1f Hz".format(v) }, log = true),
            Def(LPITCH, "→ PITCH", -12f, 12f, 0f, 0.01f, ::semis),
            Def(LCUT, "→ CUTOFF", -4f, 4f, 0f, 0.005f, ::octs),
            Def(LPAN, "→ PAN", 0f, 1f, 0f, 0.002f, ::pct),
            Def(LVOL, "→ VOLUME", 0f, 1f, 0f, 0.002f, ::pct),
            // velocity
            Def(VVOL, "→ VOLUME", 0f, 1f, 1f, 0.002f, ::pct),
            Def(VCUT, "→ CUTOFF", -4f, 4f, 0f, 0.005f, ::octs),
            // trigger mode and source
            Def(GATE, "TRIGGER", 0f, 1f, 0f, 1f, { "" }, choices = listOf("ONE-SHOT", "GATE")),
            Def(SRC, "SOURCE", 0f, 3f, 0f, 1f, { "" }, choices = listOf("SAMPLER", "SYNTH", "FM", "POLY")),
            // FM (6-operator, Dexed engine)
            Def(FMBANK, "BANK", 0f, 18f, 0f, 1f, { "" }, choices = FM_BANKS),
            Def(FMPROG, "PATCH", 0f, 31f, 0f, 1f, { v -> "${v.roundToInt() + 1}" }, choices = (1..32).map { "$it" }),
            Def(FMBRIGHT, "BRIGHTNESS", -1f, 1f, 0f, 0.002f, ::pct),
            Def(FMATT, "ATTACK", -1f, 1f, 0f, 0.002f, ::pct),
            Def(FMREL, "RELEASE", -1f, 1f, 0f, 0.002f, ::pct),
            Def(CHOKE, "CHOKE", 0f, 16f, 0f, 1f, { "" }, choices = listOf("OFF") + (1..16).map { "$it" }),
            Def(LINK, "LINK", 0f, 16f, 0f, 1f, { "" }, choices = listOf("OFF") + (1..16).map { "$it" }),
            Def(FMFB, "FEEDBACK", -1f, 7f, -1f, 0.02f, { v -> if (v < -0.5f) "PATCH" else "${v.roundToInt()}" }),
            // synth
            Def(SMODEL, "MODEL", 0f, 23f, 8f, 1f, { "" }, choices = MODELS),
            Def(SNOTE, "BASE NOTE", 0f, 127f, 60f, 0.02f, { v -> noteName(v.roundToInt()) }),
            Def(SHARM, "HARMONICS", 0f, 1f, 0.5f, 0.002f, ::pct),
            Def(STIMB, "TIMBRE", 0f, 1f, 0.5f, 0.002f, ::pct),
            Def(SMORPH, "MORPH", 0f, 1f, 0.5f, 0.002f, ::pct),
            Def(SDEC, "DECAY", 0f, 1f, 0.5f, 0.002f, ::pct),
            Def(SCOL, "COLOUR", 0f, 1f, 0.5f, 0.002f, ::pct),
            Def(SLPG, "SYNTH GATE", 0f, 1f, 1f, 1f, { "" }, choices = listOf("OFF", "ON")),
            Def(SAUX, "OUTPUT", 0f, 1f, 0f, 0.002f, { v -> if (v < 0.01f) "MAIN" else if (v > 0.99f) "AUX" else "${(v * 100).roundToInt()}% AUX" }),
            Def(SFM, "ENV → PITCH", -1f, 1f, 0f, 0.002f, ::pct),
            Def(STMOD, "ENV → TIMBRE", -1f, 1f, 0f, 0.002f, ::pct),
            Def(SMMOD, "ENV → MORPH", -1f, 1f, 0f, 0.002f, ::pct),
            Def(COLOUR, "COLOUR", 0f, 16f, 0f, 1f, { "" }, choices = listOf("AUTO") + (1..16).map { "$it" }),
        ).associateBy { it.index }

        private fun file(ctx: Context) = File(ctx.filesDir, "sound_params.json")

        fun load(ctx: Context): SoundParams {
            val p = SoundParams()
            val f = file(ctx)
            if (!f.exists()) return p
            try {
                val a = JSONArray(f.readText())
                for (s in 0 until minOf(Pattern.SOUNDS, a.length())) {
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

    /** Name of an FM voice (bank, program), from the engine. */
    var fmPatchName: ((Int, Int) -> String)? = null

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
        this[slot, LSTART] = 0f
        this[slot, LEND] = 1f
    }

    private var acc = 0

    /** Applies [delta] knob units to parameter [i] of [slot]; [fine] = 10x finer. */
    /** Switches the sound source; a sound moving to SYNTH or FM gets 8 voices if it had 1. */
    fun setSource(slot: Int, src: Int) {
        val before = this[slot, SRC].roundToInt()
        this[slot, SRC] = src.toFloat()
        if (src != before && (src == SRC_SYNTH || src == SRC_FM) && this[slot, POLY].roundToInt() <= 1) this[slot, POLY] = 8f
    }

    fun nudge(slot: Int, i: Int, delta: Int, fine: Boolean) {
        val d = DEFS[i] ?: return
        val cur = this[slot, i]
        val k = if (fine) 0.1f else 1f
        val next = when {
            d.choices != null -> {
                // A switch moves one choice per notch-sized turn, not per encoder unit.
                acc += delta
                if (abs(acc) < 25) return
                val dir = if (acc > 0) 1 else -1
                acc = 0
                (cur.roundToInt() + dir).toFloat()
            }
            d.log -> cur * 2f.pow(delta * d.step * k * 2.5f)
            else -> cur + delta * d.step * k
        }
        if (i == SRC) { setSource(slot, next.coerceIn(d.min, d.max).roundToInt()); return }
        this[slot, i] = next.coerceIn(d.min, d.max)
        if (i == START && this[slot, START] > this[slot, END] - 0.001f) this[slot, START] = (this[slot, END] - 0.001f).coerceAtLeast(0f)
        if (i == END && this[slot, END] < this[slot, START] + 0.001f) this[slot, END] = (this[slot, START] + 0.001f).coerceAtMost(1f)
        if (i == LSTART && this[slot, LSTART] > this[slot, LEND] - 0.001f) this[slot, LSTART] = (this[slot, LEND] - 0.001f).coerceAtLeast(0f)
        if (i == LEND && this[slot, LEND] < this[slot, LSTART] + 0.001f) this[slot, LEND] = (this[slot, LSTART] + 0.001f).coerceAtMost(1f)
    }

    fun display(slot: Int, i: Int): String {
        val d = DEFS[i] ?: return ""
        val v = this[slot, i]
        if (i == FMPROG) fmPatchName?.let { return "${v.roundToInt() + 1} " + it(this[slot, FMBANK].roundToInt(), v.roundToInt()).trim() }
        return d.choices?.getOrNull(v.roundToInt()) ?: d.format(v)
    }

    fun save(ctx: Context) {
        val a = JSONArray()
        for (s in 0 until Pattern.SOUNDS) {
            val row = JSONArray()
            for (i in 0 until COUNT) row.put(this[s, i].toDouble())
            a.put(row)
        }
        file(ctx).writeText(a.toString())
    }
}

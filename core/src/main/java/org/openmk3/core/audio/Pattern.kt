package org.openmk3.core.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.openmk3.core.AppLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One pattern of a group: 16 sounds x up to 64 steps (4 bars of 16ths), with notes. */
class PatternData(var length: Int = 16) {
    val vel = Array(Pattern.PADS) { FloatArray(Pattern.STEPS) }
    val note = Array(Pattern.PADS) { FloatArray(Pattern.STEPS) { Pattern.ROOT.toFloat() } }
    val xvel = Array(Pattern.PADS) { FloatArray(Pattern.STEPS * Pattern.EXTRA) }
    val xnote = Array(Pattern.PADS) { FloatArray(Pattern.STEPS * Pattern.EXTRA) }
    /** Note length in steps (shared by a step's chord notes). */
    val len = Array(Pattern.PADS) { FloatArray(Pattern.STEPS) { 1f } }
    /** Start within the step, 0..1 (notes off the grid). */
    val off = Array(Pattern.PADS) { FloatArray(Pattern.STEPS) }

    /** An automation lane: a sound parameter's value per step (NaN = no point). */
    class Lane(val pad: Int, val param: Int, val values: FloatArray = FloatArray(Pattern.STEPS) { Float.NaN })
    val lanes = mutableListOf<Lane>()

    fun copy(): PatternData {
        val c = PatternData(length)
        for (p in 0 until Pattern.PADS) {
            vel[p].copyInto(c.vel[p]); note[p].copyInto(c.note[p])
            xvel[p].copyInto(c.xvel[p]); xnote[p].copyInto(c.xnote[p])
            len[p].copyInto(c.len[p]); off[p].copyInto(c.off[p])
        }
        for (ln in lanes) c.lanes.add(Lane(ln.pad, ln.param, ln.values.copyOf()))
        return c
    }

    /** Moves everything in a step to another step (overwriting it). */
    fun moveCell(pad: Int, from: Int, to: Int, newOff: Float) {
        if (from == to) { off[pad][to] = newOff; return }
        val v = vel[pad][from]; val n = note[pad][from]; val l = len[pad][from]
        val xv = FloatArray(Pattern.EXTRA) { xvel[pad][from * Pattern.EXTRA + it] }
        val xn = FloatArray(Pattern.EXTRA) { xnote[pad][from * Pattern.EXTRA + it] }
        clearStep(pad, from)
        vel[pad][to] = v; note[pad][to] = n; len[pad][to] = l; off[pad][to] = newOff
        for (x in 0 until Pattern.EXTRA) { xvel[pad][to * Pattern.EXTRA + x] = xv[x]; xnote[pad][to * Pattern.EXTRA + x] = xn[x] }
    }

    fun has(pad: Int, step: Int) = vel[pad][step] > 0f || (0 until Pattern.EXTRA).any { xvel[pad][step * Pattern.EXTRA + it] > 0f }
    fun isEmpty() = (0 until Pattern.PADS).none { p -> (0 until length).any { has(p, it) } }

    fun clearStep(pad: Int, step: Int) {
        vel[pad][step] = 0f
        note[pad][step] = Pattern.ROOT.toFloat()
        len[pad][step] = 1f
        off[pad][step] = 0f
        for (x in 0 until Pattern.EXTRA) { xvel[pad][step * Pattern.EXTRA + x] = 0f; xnote[pad][step * Pattern.EXTRA + x] = 0f }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("length", length)
        val ev = JSONArray()
        for (p in 0 until Pattern.PADS) for (t in 0 until Pattern.STEPS) {
            if (vel[p][t] > 0f) ev.put(JSONArray(listOf(p, t, -1, vel[p][t].toDouble(), note[p][t].toInt(), len[p][t].toDouble(), off[p][t].toDouble())))
            for (x in 0 until Pattern.EXTRA) {
                val v = xvel[p][t * Pattern.EXTRA + x]
                if (v > 0f) ev.put(JSONArray(listOf(p, t, x, v.toDouble(), xnote[p][t * Pattern.EXTRA + x].toInt())))
            }
        }
        put("events", ev)
        put("automation", JSONArray().apply {
            for (ln in lanes) put(JSONObject().put("pad", ln.pad).put("param", ln.param)
                .put("values", JSONArray(ln.values.map { if (it.isNaN()) JSONObject.NULL else it.toDouble() })))
        })
    }

    companion object {
        fun fromJson(j: JSONObject): PatternData {
            val d = PatternData(j.optInt("length", 16).coerceIn(1, Pattern.STEPS))
            val ev = j.optJSONArray("events") ?: JSONArray()
            for (i in 0 until ev.length()) {
                val e = ev.getJSONArray(i)
                val p = e.getInt(0); val t = e.getInt(1); val x = e.getInt(2)
                val v = e.getDouble(3).toFloat().coerceIn(0f, 1f); val n = e.getInt(4).coerceIn(0, 127)
                if (p !in 0 until Pattern.PADS || t !in 0 until Pattern.STEPS) continue
                if (x < 0) {
                    d.vel[p][t] = v; d.note[p][t] = n.toFloat()
                    if (e.length() > 6) { d.len[p][t] = e.getDouble(5).toFloat().coerceIn(0.05f, 64f); d.off[p][t] = e.getDouble(6).toFloat().coerceIn(0f, 0.999f) }
                }
                else if (x < Pattern.EXTRA) { d.xvel[p][t * Pattern.EXTRA + x] = v; d.xnote[p][t * Pattern.EXTRA + x] = n.toFloat() }
            }
            j.optJSONArray("automation")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val vs = o.getJSONArray("values")
                    val ln = Lane(o.getInt("pad"), o.getInt("param"))
                    for (t in 0 until minOf(Pattern.STEPS, vs.length())) if (!vs.isNull(t)) ln.values[t] = vs.getDouble(t).toFloat()
                    d.lanes.add(ln)
                }
            }
            return d
        }
    }
}

/**
 * The song: 16 patterns per group, 16 scenes, mute / solo, tempo and swing.
 *
 * The native engine reads a direct buffer (layout must match engine.cpp) holding the globals
 * and, per group, two banks: the pattern playing and the one queued to play next. Editing
 * writes to both the pattern here and the playing bank, so changes are heard at once.
 */
class Pattern {
    companion object {
        const val GROUPS = 8
        const val PADS = 16
        const val SOUNDS = GROUPS * PADS
        const val STEPS = 64           // longest pattern: 4 bars of 16ths
        const val SLOTS = 64           // patterns per group, and scenes (4 banks of 16)
        const val BANK_SLOTS = 16
        const val EXTRA = 3            // extra notes per step (chords)
        const val ROOT = 60

        private const val OFF_MUTE = 0
        private const val OFF_SOLO = OFF_MUTE + SOUNDS
        private const val OFF_BPM = OFF_SOLO + SOUNDS
        private const val OFF_SWING = OFF_BPM + 1
        private const val OFF_GMUTE = OFF_SWING + 1
        private const val OFF_GSOLO = OFF_GMUTE + GROUPS
        private const val OFF_ACTIVE = OFF_GSOLO + GROUPS
        private const val OFF_PENDING = OFF_ACTIVE + GROUPS
        private const val OFF_GSTEP = OFF_PENDING + GROUPS
        private const val OFF_METRO = OFF_GSTEP + GROUPS
        private const val OFF_COUNTIN = OFF_METRO + 1
        private const val OFF_COUNTING = OFF_COUNTIN + 1
        private const val OFF_GVOL = OFF_COUNTING + 1
        private const val OFF_GPAN = OFF_GVOL + GROUPS
        private const val OFF_GFILT = OFF_GPAN + GROUPS
        private const val OFF_METROVOL = OFF_GFILT + GROUPS
        private const val OFF_COUNTBARS = OFF_METROVOL + 1
        private const val OFF_GSWING = OFF_GFILT + GROUPS + 6
        private const val OFF_GORIGIN = OFF_GSWING + GROUPS
        private const val OFF_SWITCHAT = OFF_GORIGIN + GROUPS
        private const val OFF_NOW = OFF_SWITCHAT + 1
        private const val OFF_PFX = OFF_NOW + 1
        private const val OFF_GSEND = OFF_PFX + 4
        private const val OFF_BANKS = OFF_GSEND + GROUPS * 2
        /** Perform FX types (SHIFT + PERFORM). */
        val PERFORM_FX = listOf("FILTER", "STUTTER", "TAPE STOP", "TRANCE GATE", "LO-FI")
        private const val B_VEL = 0
        private const val B_NOTE = B_VEL + PADS * STEPS
        private const val B_XVEL = B_NOTE + PADS * STEPS
        private const val B_XNOTE = B_XVEL + PADS * STEPS * EXTRA
        private const val B_SKIP = B_XNOTE + PADS * STEPS * EXTRA
        private const val B_NLEN = B_SKIP + PADS * STEPS
        private const val B_OFF = B_NLEN + PADS * STEPS
        const val LANES = 16
        private const val LANE = 2 + STEPS
        private const val B_AUTO = B_OFF + PADS * STEPS
        private const val B_LEN = B_AUTO + LANES * LANE
        private const val BANK = B_LEN + 1
        private const val FLOATS = OFF_BANKS + GROUPS * 2 * BANK
    }

    /** Shared with the native engine. */
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(FLOATS * 4).order(ByteOrder.nativeOrder())
    private val f = buffer.asFloatBuffer()

    /** [size] flags stored as 0f / 1f. */
    inner class Flags(private val base: Int, private val size: Int) {
        operator fun get(i: Int): Boolean = f.get(base + i) > 0.5f
        operator fun set(i: Int, v: Boolean) { f.put(base + i, if (v) 1f else 0f) }
        fun fill(v: Boolean) { for (i in 0 until size) set(i, v) }
        fun any(range: IntRange = 0 until size): Boolean = range.any { get(it) }
        fun toList(): List<Boolean> = (0 until size).map { get(it) }
    }

    val mute = Flags(OFF_MUTE, SOUNDS)
    val solo = Flags(OFF_SOLO, SOUNDS)
    val groupMute = Flags(OFF_GMUTE, GROUPS)
    val groupSolo = Flags(OFF_GSOLO, GROUPS)

    var bpm: Double
        get() = f.get(OFF_BPM).toDouble()
        set(v) { f.put(OFF_BPM, v.toFloat()) }

    /** 0 = straight, 0.5 = maximum (odd 16ths delayed by half a step). */
    var swing: Float
        get() = f.get(OFF_SWING)
        set(v) { f.put(OFF_SWING, v) }

    var metronome: Boolean
        get() = f.get(OFF_METRO) > 0.5f
        set(v) { f.put(OFF_METRO, if (v) 1f else 0f) }

    var metronomeLevel: Float
        get() = f.get(OFF_METROVOL)
        set(v) { f.put(OFF_METROVOL, v.coerceIn(0f, 1f)) }
    /** Count-in length in bars (1, 2 or 4). */
    var countInBars: Int
        get() = f.get(OFF_COUNTBARS).toInt().coerceIn(1, 4)
        set(v) { f.put(OFF_COUNTBARS, v.coerceIn(1, 4).toFloat()) }

    /** Ask the engine to count in at the next start. */
    fun requestCountIn() = f.put(OFF_COUNTIN, 1f)
    val countingIn get() = f.get(OFF_COUNTING) > 0.5f

    fun groupVolume(g: Int) = f.get(OFF_GVOL + g)
    fun setGroupVolume(g: Int, db: Float) = f.put(OFF_GVOL + g, db.coerceIn(-60f, 12f))
    fun groupPan(g: Int) = f.get(OFF_GPAN + g)
    /** Extra swing of a group (CHANNEL groove), 0..0.5, added to the project swing. */
    fun groupSwing(g: Int) = f.get(OFF_GSWING + g)
    fun setGroupSwing(g: Int, v: Float) = f.put(OFF_GSWING + g, v.coerceIn(0f, 0.5f))
    fun setGroupPan(g: Int, v: Float) = f.put(OFF_GPAN + g, v.coerceIn(-1f, 1f))
    /** Performance filter of a group: -1 low-pass .. 0 off .. 1 high-pass. */
    /** Perform FX chosen for the touch strip (index into PERFORM_FX). */
    var performType: Int
        get() = f.get(OFF_PFX).toInt()
        set(v) { f.put(OFF_PFX, v.coerceIn(0, PERFORM_FX.size - 1).toFloat()) }
    /** Touch strip held ([on]) at [amount] 0..1. */
    fun setPerform(on: Boolean, amount: Float) { f.put(OFF_PFX + 2, amount.coerceIn(0f, 1f)); f.put(OFF_PFX + 1, if (on) 1f else 0f) }

    /** Send level (0..1) of group [g] to return A (0) or B (1). */
    fun groupSend(g: Int, a: Int) = f.get(OFF_GSEND + g * 2 + a)
    fun setGroupSend(g: Int, a: Int, v: Float) = f.put(OFF_GSEND + g * 2 + a, v.coerceIn(0f, 1f))

    fun setGroupFilter(g: Int, v: Float) = f.put(OFF_GFILT + g, v.coerceIn(-1f, 1f))

    // ---- song model ----
    /** Patterns of each group (null = empty slot). */
    val patterns = Array(GROUPS) { arrayOfNulls<PatternData>(SLOTS) }
    /** Pattern playing (and edited) in each group, -1 = none. */
    val current = IntArray(GROUPS) { -1 }
    /** Pattern waiting to start at the end of the playing one, -1 = none. */
    val queued = IntArray(GROUPS) { -1 }
    /** Scenes: the pattern of each group (-1 = none); null = empty scene slot. */
    val scenes = arrayOfNulls<IntArray>(SLOTS)
    var currentScene = -1

    /** Length (steps) of each group's playing pattern; 16 when the group has none. */
    fun length(g: Int) = cur(g)?.length ?: 16

    /** Step each group is playing (-1 when stopped). */
    fun stepOf(g: Int) = f.get(OFF_GSTEP + g).toInt()

    private fun cur(g: Int) = current[g].takeIf { it >= 0 }?.let { patterns[g][it] }
    private fun bankBase(g: Int, b: Int) = OFF_BANKS + (g * 2 + b) * BANK
    private fun active(g: Int) = if (f.get(OFF_ACTIVE + g) > 0.5f) 1 else 0

    init {
        bpm = 120.0
        metronomeLevel = 0.7f
        countInBars = 1
        f.put(OFF_SWITCHAT, -1f)
        for (g in 0 until GROUPS) {
            f.put(OFF_PENDING + g, -1f)
            for (b in 0..1) { f.put(bankBase(g, b) + B_LEN, 16f); clearBank(g, b) }
        }
    }

    private fun clearBank(g: Int, b: Int) {
        val base = bankBase(g, b)
        for (i in 0 until PADS * STEPS) {
            f.put(base + B_VEL + i, 0f); f.put(base + B_NOTE + i, ROOT.toFloat()); f.put(base + B_SKIP + i, 0f)
            f.put(base + B_NLEN + i, 1f); f.put(base + B_OFF + i, 0f)
        }
        for (i in 0 until LANES * LANE) f.put(base + B_AUTO + i, if (i % LANE < 2) 0f else Float.NaN)
        for (i in 0 until PADS * STEPS * EXTRA) { f.put(base + B_XVEL + i, 0f); f.put(base + B_XNOTE + i, 0f) }
    }

    /** Copies [d] (or silence) into bank [b] of group [g]. */
    private fun writeBank(g: Int, b: Int, d: PatternData?) {
        clearBank(g, b)
        val base = bankBase(g, b)
        f.put(base + B_LEN, (d?.length ?: 16).toFloat())
        if (d == null) return
        writeLanes(base, d)
        for (p in 0 until PADS) for (t in 0 until STEPS) {
            val c = p * STEPS + t
            f.put(base + B_VEL + c, d.vel[p][t]); f.put(base + B_NOTE + c, d.note[p][t])
            f.put(base + B_NLEN + c, d.len[p][t]); f.put(base + B_OFF + c, d.off[p][t])
            for (x in 0 until EXTRA) {
                f.put(base + B_XVEL + c * EXTRA + x, d.xvel[p][t * EXTRA + x])
                f.put(base + B_XNOTE + c * EXTRA + x, d.xnote[p][t * EXTRA + x])
            }
        }
    }

    private fun writeLanes(base: Int, d: PatternData) {
        for (k in 0 until LANES) {
            val ln = d.lanes.getOrNull(k)
            val b = base + B_AUTO + k * LANE
            f.put(b, if (ln == null) 0f else (ln.pad + 1).toFloat())
            f.put(b + 1, (ln?.param ?: 0).toFloat())
            for (t in 0 until STEPS) f.put(b + 2 + t, ln?.values?.get(t) ?: Float.NaN)
        }
    }

    /** AUTO: records [value] for a sound parameter at [step] of the playing pattern. */
    fun writeAutomation(sound: Int, param: Int, step: Int, value: Float): Boolean {
        val g = sound / PADS
        val d = cur(g) ?: return false
        val pad = sound % PADS
        var ln = d.lanes.firstOrNull { it.pad == pad && it.param == param }
        if (ln == null) {
            if (d.lanes.size >= LANES) return false
            ln = PatternData.Lane(pad, param)
            d.lanes.add(ln)
        }
        if (step in 0 until STEPS) ln.values[step] = value
        writeLanes(bankBase(g, active(g)), d)
        return true
    }

    /** Removes automation: one parameter of a sound, all of a sound (param < 0), or the group (sound < 0). */
    fun clearAutomation(g: Int, sound: Int = -1, param: Int = -1) {
        val d = cur(g) ?: return
        d.lanes.removeAll { (sound < 0 || it.pad == sound % PADS) && (param < 0 || it.param == param) }
        writeLanes(bankBase(g, active(g)), d)
    }

    fun hasAutomation(sound: Int, param: Int) = cur(sound / PADS)?.lanes?.any { it.pad == sound % PADS && it.param == param } ?: false

    /** Makes the playing bank match the current pattern (after edits made off-buffer). */
    fun refreshGroup(g: Int) = writeBank(g, active(g), cur(g))

    /**
     * Selects pattern [idx] of group [g] (creating an empty one if the slot is empty). While
     * playing it starts when the current pattern ends; stopped, it switches at once.
     */
    fun selectPattern(g: Int, idx: Int, playing: Boolean, create: Boolean = true) {
        if (idx in 0 until SLOTS && patterns[g][idx] == null && create) patterns[g][idx] = PatternData(defaultLength(g))
        val d = if (idx >= 0) patterns[g][idx] else null
        if (!playing || current[g] < 0) {
            current[g] = idx
            queued[g] = -1
            f.put(OFF_PENDING + g, -1f)
            writeBank(g, active(g), d)
            return
        }
        val other = 1 - active(g)
        writeBank(g, other, d)
        queued[g] = idx
        f.put(OFF_PENDING + g, other.toFloat())
    }

    private fun defaultLength(g: Int) = cur(g)?.length ?: 16

    /** Call from the UI thread: picks up pattern switches the engine made. */
    fun poll() {
        for (g in 0 until GROUPS) {
            if (queued[g] >= 0 && f.get(OFF_PENDING + g) < 0f) {
                current[g] = queued[g]
                queued[g] = -1
            }
        }
    }

    /** Pattern of group [g] being edited, created on first use (recording into an empty group). */
    private fun editable(g: Int): PatternData {
        cur(g)?.let { return it }
        val idx = (0 until SLOTS).firstOrNull { patterns[g][it] == null } ?: 0
        val d = PatternData(16)
        patterns[g][idx] = d
        current[g] = idx
        writeBank(g, active(g), d)
        return d
    }

    // ---- editing (sound = 0..127) ----
    fun vel(sound: Int, step: Int): Float = cur(sound / PADS)?.vel?.get(sound % PADS)?.get(step) ?: 0f
    fun noteOf(sound: Int, step: Int): Int = cur(sound / PADS)?.note?.get(sound % PADS)?.get(step)?.toInt() ?: ROOT
    fun hasStep(sound: Int, step: Int): Boolean = cur(sound / PADS)?.has(sound % PADS, step) ?: false

    /** Writes a (chord) note into a step: the main hit, the slot holding that note, or a free one. */
    fun recordNote(sound: Int, step: Int, n: Int, v: Float, offset: Float = 0f, length: Float = 1f) {
        val g = sound / PADS
        val pad = sound % PADS
        val d = editable(g)
        if (!d.has(pad, step)) { d.off[pad][step] = offset.coerceIn(0f, 0.999f); d.len[pad][step] = length.coerceIn(0.05f, 64f) }
        val mv = d.vel[pad][step]
        val mn = d.note[pad][step].toInt().let { if (it <= 0) ROOT else it }
        if (mv <= 0f || mn == n) {
            d.vel[pad][step] = maxOf(mv, v)
            d.note[pad][step] = n.toFloat()
        } else {
            var done = false
            for (x in 0 until EXTRA) if (!done && d.xvel[pad][step * EXTRA + x] > 0f && d.xnote[pad][step * EXTRA + x].toInt() == n) {
                d.xvel[pad][step * EXTRA + x] = maxOf(v, d.xvel[pad][step * EXTRA + x]); done = true
            }
            for (x in 0 until EXTRA) if (!done && d.xvel[pad][step * EXTRA + x] <= 0f) {
                d.xvel[pad][step * EXTRA + x] = v; d.xnote[pad][step * EXTRA + x] = n.toFloat(); done = true
            }
        }
        writeCell(g, pad, step, d)
    }

    /** Note length (steps) of a step's notes. */
    fun noteLength(sound: Int, step: Int): Float = cur(sound / PADS)?.len?.get(sound % PADS)?.get(step) ?: 1f
    fun noteOffset(sound: Int, step: Int): Float = cur(sound / PADS)?.off?.get(sound % PADS)?.get(step) ?: 0f

    fun setNoteLength(sound: Int, step: Int, l: Float) {
        val g = sound / PADS
        val d = cur(g) ?: return
        d.len[sound % PADS][step] = l.coerceIn(0.05f, STEPS.toFloat())
        writeCell(g, sound % PADS, step, d)
    }

    /** Steps (of the playing pattern) that hold notes of [sound]. */
    fun stepsOf(sound: Int): List<Int> {
        val d = cur(sound / PADS) ?: return emptyList()
        return (0 until d.length).filter { d.has(sound % PADS, it) }
    }

    /**
     * Moves the notes of [sounds] by [steps] (fractional; they wrap around the pattern).
     * Notes landing on the same step merge (the moved one wins).
     */
    fun move(sounds: Collection<Int>, steps: Float) {
        for (snd in sounds) {
            val g = snd / PADS
            val d = cur(g) ?: continue
            val pad = snd % PADS
            val l = d.length
            val src = (0 until l).filter { d.has(pad, it) }
            val moved = src.map { t ->
                val pos = ((t + d.off[pad][t] + steps) % l + l) % l
                Triple(t, pos.toInt().coerceIn(0, l - 1), pos - pos.toInt())
            }
            // order matters: move into a copy so earlier moves don't overwrite later sources
            val copy = d.copy()
            for (t in src) d.clearStep(pad, t)
            for ((from, to, o) in moved) {
                d.vel[pad][to] = copy.vel[pad][from]; d.note[pad][to] = copy.note[pad][from]
                d.len[pad][to] = copy.len[pad][from]; d.off[pad][to] = o.coerceIn(0f, 0.999f)
                for (x in 0 until EXTRA) {
                    d.xvel[pad][to * EXTRA + x] = copy.xvel[pad][from * EXTRA + x]
                    d.xnote[pad][to * EXTRA + x] = copy.xnote[pad][from * EXTRA + x]
                }
            }
            refreshGroup(g)
        }
    }

    /** Quantise [sounds]: [amount] 1 = onto the grid, 0.5 = half way. */
    fun quantize(sounds: Collection<Int>, amount: Float) {
        for (snd in sounds) {
            val g = snd / PADS
            val d = cur(g) ?: continue
            val pad = snd % PADS
            val l = d.length
            for (t in (0 until l).filter { d.has(pad, it) && d.off[pad][it] > 0f }) {
                val o = d.off[pad][t]
                val target = if (o >= 0.5f) 1f else 0f        // nearest grid line
                val newPos = t + o + (target - o) * amount
                val to = (newPos.toInt() % l + l) % l
                val no = (newPos - newPos.toInt()).coerceIn(0f, 0.999f)
                if (to != t && d.has(pad, to)) { d.clearStep(pad, t); continue }  // merge into the note already there
                d.moveCell(pad, t, to, no)
            }
            refreshGroup(g)
        }
    }

    /** Adds [delta] to every velocity of [sounds] (keeping their differences). */
    fun addVelocity(sounds: Collection<Int>, delta: Float) {
        for (snd in sounds) {
            val g = snd / PADS
            val d = cur(g) ?: continue
            val pad = snd % PADS
            for (t in 0 until STEPS) {
                if (d.vel[pad][t] > 0f) d.vel[pad][t] = (d.vel[pad][t] + delta).coerceIn(0.01f, 1f)
                for (x in 0 until EXTRA) if (d.xvel[pad][t * EXTRA + x] > 0f)
                    d.xvel[pad][t * EXTRA + x] = (d.xvel[pad][t * EXTRA + x] + delta).coerceIn(0.01f, 1f)
            }
            refreshGroup(g)
        }
    }

    /** Adds [delta] steps to every note length of [sounds]. */
    fun addLength(sounds: Collection<Int>, delta: Float) {
        for (snd in sounds) {
            val g = snd / PADS
            val d = cur(g) ?: continue
            val pad = snd % PADS
            for (t in 0 until STEPS) if (d.has(pad, t)) d.len[pad][t] = (d.len[pad][t] + delta).coerceIn(0.05f, STEPS.toFloat())
            refreshGroup(g)
        }
    }

    /** STEP mode edits of one step's notes: velocity, position (fraction of a step), pitch. */
    fun editStep(sound: Int, step: Int, velDelta: Float = 0f, posDelta: Float = 0f, semis: Int = 0) {
        val g = sound / PADS
        val d = cur(g) ?: return
        val pad = sound % PADS
        if (!d.has(pad, step)) return
        if (velDelta != 0f) {
            if (d.vel[pad][step] > 0f) d.vel[pad][step] = (d.vel[pad][step] + velDelta).coerceIn(0.01f, 1f)
            for (x in 0 until EXTRA) if (d.xvel[pad][step * EXTRA + x] > 0f) d.xvel[pad][step * EXTRA + x] = (d.xvel[pad][step * EXTRA + x] + velDelta).coerceIn(0.01f, 1f)
        }
        if (semis != 0) {
            d.note[pad][step] = (d.note[pad][step] + semis).coerceIn(0f, 127f)
            for (x in 0 until EXTRA) if (d.xvel[pad][step * EXTRA + x] > 0f) d.xnote[pad][step * EXTRA + x] = (d.xnote[pad][step * EXTRA + x] + semis).coerceIn(0f, 127f)
        }
        // position stays within its step here (the pad is that step)
        if (posDelta != 0f) d.off[pad][step] = (d.off[pad][step] + posDelta).coerceIn(0f, 0.999f)
        refreshGroup(g)
    }

    /** Sets the main note's velocity of a step (0 removes it). */
    fun setVelocity(sound: Int, step: Int, v: Float) {
        val g = sound / PADS
        val d = cur(g) ?: return
        d.vel[sound % PADS][step] = v.coerceIn(0f, 1f)
        writeCell(g, sound % PADS, step, d)
    }

    fun clearStep(sound: Int, step: Int) {
        val g = sound / PADS
        val d = cur(g) ?: return
        d.clearStep(sound % PADS, step)
        writeCell(g, sound % PADS, step, d)
    }

    fun clear(sound: Int) {
        val g = sound / PADS
        val d = cur(g) ?: return
        for (t in 0 until STEPS) d.clearStep(sound % PADS, t)
        refreshGroup(g)
    }

    /** Don't play this step again in the current loop (a live hit already sounded). */
    fun skipOnce(sound: Int, step: Int) {
        val g = sound / PADS
        f.put(bankBase(g, active(g)) + B_SKIP + (sound % PADS) * STEPS + step, 1f)
    }

    private fun writeCell(g: Int, pad: Int, step: Int, d: PatternData) {
        val base = bankBase(g, active(g))
        val c = pad * STEPS + step
        f.put(base + B_VEL + c, d.vel[pad][step]); f.put(base + B_NOTE + c, d.note[pad][step])
        f.put(base + B_NLEN + c, d.len[pad][step]); f.put(base + B_OFF + c, d.off[pad][step])
        for (x in 0 until EXTRA) {
            f.put(base + B_XVEL + c * EXTRA + x, d.xvel[pad][step * EXTRA + x])
            f.put(base + B_XNOTE + c * EXTRA + x, d.xnote[pad][step * EXTRA + x])
        }
    }

    /** The engine wrote repeat / arp hits into the playing bank: copy them into the pattern. */
    fun pullFromEngine() {
        for (g in 0 until GROUPS) {
            val d = cur(g) ?: continue
            val base = bankBase(g, active(g))
            for (p in 0 until PADS) for (t in 0 until STEPS) {
                val c = p * STEPS + t
                d.vel[p][t] = f.get(base + B_VEL + c); d.note[p][t] = f.get(base + B_NOTE + c)
                d.len[p][t] = f.get(base + B_NLEN + c).let { if (it <= 0f) 1f else it }; d.off[p][t] = f.get(base + B_OFF + c)
                for (x in 0 until EXTRA) {
                    d.xvel[p][t * EXTRA + x] = f.get(base + B_XVEL + c * EXTRA + x)
                    d.xnote[p][t * EXTRA + x] = f.get(base + B_XNOTE + c * EXTRA + x)
                }
            }
        }
    }

    fun setLength(g: Int, steps: Int) {
        val d = editable(g)
        d.length = steps.coerceIn(1, STEPS)
        f.put(bankBase(g, active(g)) + B_LEN, d.length.toFloat())
    }

    /** SHIFT + DUPLICATE: doubles the pattern's length, repeating its content. */
    fun double(g: Int): Boolean {
        val d = cur(g) ?: return false
        if (d.length * 2 > STEPS) return false
        val l = d.length
        for (p in 0 until PADS) for (t in 0 until l) {
            d.vel[p][t + l] = d.vel[p][t]; d.note[p][t + l] = d.note[p][t]
            d.len[p][t + l] = d.len[p][t]; d.off[p][t + l] = d.off[p][t]
            for (x in 0 until EXTRA) { d.xvel[p][(t + l) * EXTRA + x] = d.xvel[p][t * EXTRA + x]; d.xnote[p][(t + l) * EXTRA + x] = d.xnote[p][t * EXTRA + x] }
        }
        d.length = l * 2
        refreshGroup(g)
        return true
    }

    /** Moves a sound's notes by [steps] (wrapping around the pattern). */
    fun nudge(sound: Int, steps: Int) {
        val g = sound / PADS
        val d = cur(g) ?: return
        val p = sound % PADS
        val l = d.length
        val v = FloatArray(l) { d.vel[p][it] }; val n = FloatArray(l) { d.note[p][it] }
        val xv = FloatArray(l * EXTRA) { d.xvel[p][it] }; val xn = FloatArray(l * EXTRA) { d.xnote[p][it] }
        val ln = FloatArray(l) { d.len[p][it] }; val of = FloatArray(l) { d.off[p][it] }
        for (t in 0 until l) {
            val to = Math.floorMod(t + steps, l)
            d.vel[p][to] = v[t]; d.note[p][to] = n[t]; d.len[p][to] = ln[t]; d.off[p][to] = of[t]
            for (x in 0 until EXTRA) { d.xvel[p][to * EXTRA + x] = xv[t * EXTRA + x]; d.xnote[p][to * EXTRA + x] = xn[t * EXTRA + x] }
        }
        refreshGroup(g)
    }

    /** Transposes a sound's notes by [semitones]. */
    fun transpose(sound: Int, semitones: Int) {
        val g = sound / PADS
        val d = cur(g) ?: return
        val p = sound % PADS
        for (t in 0 until STEPS) {
            if (d.vel[p][t] > 0f) d.note[p][t] = (d.note[p][t] + semitones).coerceIn(0f, 127f)
            for (x in 0 until EXTRA) if (d.xvel[p][t * EXTRA + x] > 0f)
                d.xnote[p][t * EXTRA + x] = (d.xnote[p][t * EXTRA + x] + semitones).coerceIn(0f, 127f)
        }
        refreshGroup(g)
    }

    /** Clears every note of the group's playing pattern (keeps the pattern and its length). */
    fun clearGroup(g: Int) {
        val d = cur(g) ?: return
        for (p in 0 until PADS) for (t in 0 until STEPS) d.clearStep(p, t)
        refreshGroup(g)
    }

    /** Copies a sound's notes to another sound of the same group (DUPLICATE in pad mode). */
    fun copySound(from: Int, to: Int) {
        val g = from / PADS
        if (to / PADS != g) return
        val d = cur(g) ?: return
        val a = from % PADS; val b = to % PADS
        d.vel[a].copyInto(d.vel[b]); d.note[a].copyInto(d.note[b])
        d.xvel[a].copyInto(d.xvel[b]); d.xnote[a].copyInto(d.xnote[b])
        d.len[a].copyInto(d.len[b]); d.off[a].copyInto(d.off[b])
        refreshGroup(g)
    }

    fun copyOf(g: Int): PatternData? = cur(g)?.copy()

    fun paste(g: Int, src: PatternData) {
        val idx = current[g].takeIf { it >= 0 } ?: (0 until SLOTS).firstOrNull { patterns[g][it] == null } ?: 0
        patterns[g][idx] = src.copy()
        current[g] = idx
        refreshGroup(g)
    }

    fun deletePattern(g: Int, idx: Int, playing: Boolean) {
        patterns[g][idx] = null
        if (current[g] == idx) { current[g] = -1; writeBank(g, active(g), null) }
        if (queued[g] == idx) { queued[g] = -1; f.put(OFF_PENDING + g, -1f) }
        for (s in scenes) if (s != null && s[g] == idx) s[g] = -1
    }

    /** Copies group [from]'s patterns and mixer settings to group [to]. */
    fun copyGroup(from: Int, to: Int) {
        for (i in 0 until SLOTS) patterns[to][i] = patterns[from][i]?.copy()
        current[to] = current[from]
        queued[to] = -1
        f.put(OFF_PENDING + to, -1f)
        setGroupVolume(to, groupVolume(from)); setGroupPan(to, groupPan(from)); setGroupSwing(to, groupSwing(from))
        for (a in 0..1) setGroupSend(to, a, groupSend(from, a))
        writeBank(to, active(to), cur(to))
    }

    /** Removes group [g]'s patterns (its mixer settings go back to the defaults). */
    fun removeGroup(g: Int) {
        patterns[g].fill(null)
        current[g] = -1
        queued[g] = -1
        f.put(OFF_PENDING + g, -1f)
        setGroupVolume(g, 0f); setGroupPan(g, 0f); setGroupSwing(g, 0f)
        for (a in 0..1) setGroupSend(g, a, 0f)
        writeBank(g, active(g), null)
    }

    fun duplicatePattern(g: Int, from: Int, to: Int) {
        val src = patterns[g][from] ?: return
        patterns[g][to] = src.copy()
    }

    // ---- scenes ----
    /** Saves the patterns playing now as scene [idx]. */
    fun captureScene(idx: Int) {
        scenes[idx] = IntArray(GROUPS) { current[it] }
        currentScene = idx
    }

    /** Starts scene [idx]: each group switches to its pattern (or stops) at its loop end. */
    fun launchScene(idx: Int, playing: Boolean) {
        val sc = scenes[idx] ?: return
        currentScene = idx
        for (g in 0 until GROUPS) selectPattern(g, sc[g], playing, create = false)
    }

    fun deleteScene(idx: Int) {
        scenes[idx] = null
        if (currentScene == idx) currentScene = -1
        song.removeAll { it == idx }
        songPos = songPos.coerceIn(0, maxOf(0, song.size - 1))
    }

    // ---- song (arranger): a list of scenes played in order ----
    /** Sections of the song: scene indices. */
    val song = mutableListOf<Int>()
    /** Song mode: scenes follow the song list; off, scenes and patterns loop as launched. */
    var songMode = false
    /** Loop the playing section (SHIFT + RESTART). */
    var loop = false
    /** Section playing (or starting at the next PLAY). */
    var songPos = 0
    /** Section to jump to at the end of the playing one (-1: the next one). */
    var songJump = -1
    private var secStart = 0
    private var secLen = 16
    private var queuedSec = -1
    private var queuedAt = -1

    /** Length of scene [idx] in steps: its longest pattern. */
    fun sceneLength(idx: Int): Int {
        val sc = scenes.getOrNull(idx) ?: return 16
        var len = 0
        for (g in 0 until GROUPS) sc[g].takeIf { it >= 0 }?.let { patterns[g][it] }?.let { len = maxOf(len, it.length) }
        return if (len == 0) 16 else len
    }

    private fun sectionScene(pos: Int) = song.getOrNull(pos)?.takeIf { scenes[it] != null }

    private fun nextSection(): Int = when {
        songJump in song.indices -> songJump
        loop -> songPos
        else -> if (songPos + 1 < song.size) songPos + 1 else 0
    }

    /** Puts section [pos]'s patterns in place now (stopped) or at global step [at] (playing). */
    private fun queueSection(pos: Int, at: Int, playing: Boolean) {
        val sc = scenes[sectionScene(pos) ?: return]!!
        if (!playing) {
            for (g in 0 until GROUPS) selectPattern(g, sc[g], false, create = false)
        } else {
            for (g in 0 until GROUPS) {
                val idx = sc[g]
                val d = if (idx >= 0) patterns[g][idx] else null
                val other = 1 - active(g)
                writeBank(g, other, d)
                queued[g] = idx
                f.put(OFF_PENDING + g, other.toFloat())
            }
            f.put(OFF_SWITCHAT, at.toFloat())
        }
        queuedSec = pos
        queuedAt = at
    }

    /** RESTART in song mode: the song starts again from section 1. */
    fun songRestart() {
        if (!songMode || song.isEmpty()) return
        songPos = 0; songJump = -1
        queueSection(0, 0, playing = true)
        secStart = 0; secLen = sceneLength(song[0])
    }

    private var wasPlaying = false

    /** Drives song mode; call from the UI thread often (every frame). */
    fun songTick(playing: Boolean) {
        if (!songMode || song.isEmpty()) { wasPlaying = playing; return }
        songPos = songPos.coerceIn(0, song.size - 1)
        if (!playing) {
            // stopped: the section that PLAY will start is in place
            val sc = sectionScene(songPos)
            if (sc != null) {
                val want = scenes[sc]!!
                if ((0 until GROUPS).any { current[it] != want[it] }) queueSection(songPos, 0, playing = false)
                currentScene = sc
            }
            secStart = 0; secLen = sc?.let { sceneLength(it) } ?: 16
            queuedSec = -1; queuedAt = -1
            wasPlaying = false
            return
        }
        val now = f.get(OFF_NOW).toInt()
        if (!wasPlaying) { secStart = 0; secLen = sectionScene(songPos)?.let { sceneLength(it) } ?: 16; queuedSec = -1; queuedAt = -1 }
        wasPlaying = true
        if (now < secStart) { secStart = 0; queuedSec = -1; queuedAt = -1 }  // restarted
        val end = secStart + secLen
        if (queuedAt >= 0 && now >= queuedAt && f.get(OFF_SWITCHAT) < 0f) {
            // the queued section started
            songPos = queuedSec
            if (songJump == songPos) songJump = -1
            sectionScene(songPos)?.let { currentScene = it }
            secStart = queuedAt
            secLen = sectionScene(songPos)?.let { sceneLength(it) } ?: 16
            queuedSec = -1; queuedAt = -1
            sectionScene(songPos)?.let { sc -> for (g in 0 until GROUPS) { current[g] = scenes[sc]!![g]; queued[g] = -1 } }
            return
        }
        val next = nextSection()
        if (now >= secStart && now < end - 1 && (queuedSec != next || queuedAt != end)) queueSection(next, end, playing = true)
    }

    /** Progress through the playing section (0..1), or -1. */
    fun sectionProgress(): Float {
        if (!songMode || !wasPlaying) return -1f
        return ((f.get(OFF_NOW) - secStart) / secLen).coerceIn(0f, 1f)
    }

    // ---- mute / solo ----
    /** Same rule as the engine: group mute/solo, then sound mute/solo within the group. */
    fun groupAudible(g: Int) = !groupMute[g] && (!groupSolo.any() || groupSolo[g])

    fun audible(sound: Int): Boolean {
        val g = sound / PADS
        if (!groupAudible(g)) return false
        return if (solo.any(g * PADS until (g + 1) * PADS)) solo[sound] else !mute[sound]
    }

    fun updateSolo() {}
    /** No notes in group [g]'s current pattern (or no pattern yet). */
    fun groupEmpty(g: Int) = cur(g)?.isEmpty() ?: true

    fun isEmpty(sound: Int) = cur(sound / PADS)?.let { d -> (0 until d.length).none { d.has(sound % PADS, it) } } ?: true
    fun groupIsEmpty(g: Int) = cur(g)?.isEmpty() ?: true

    // ---- persistence ----
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 3)
        put("bpm", bpm)
        put("swing", swing.toDouble())
        put("metronome", metronome)
        put("metronomeLevel", metronomeLevel.toDouble())
        put("countInBars", countInBars)
        put("mute", JSONArray(mute.toList()))
        put("solo", JSONArray(solo.toList()))
        put("groupMute", JSONArray(groupMute.toList()))
        put("groupSolo", JSONArray(groupSolo.toList()))
        put("groupVolume", JSONArray((0 until GROUPS).map { groupVolume(it).toDouble() }))
        put("groupPan", JSONArray((0 until GROUPS).map { groupPan(it).toDouble() }))
        put("groupSwing", JSONArray((0 until GROUPS).map { groupSwing(it).toDouble() }))
        put("groups", JSONArray().apply {
            for (g in 0 until GROUPS) put(JSONObject().apply {
                put("current", current[g])
                put("patterns", JSONArray().apply {
                    for (i in 0 until SLOTS) patterns[g][i]?.let { put(it.toJson().put("slot", i)) }
                })
            })
        })
        put("scenes", JSONArray().apply {
            for (i in 0 until SLOTS) scenes[i]?.let { put(JSONObject().put("slot", i).put("patterns", JSONArray(it.toList()))) }
        })
        put("currentScene", currentScene)
        put("song", JSONArray(song.toList()))
        put("songMode", songMode)
        put("loop", loop)
        put("performType", performType)
        put("groupSends", JSONArray().apply { for (g in 0 until GROUPS) for (a in 0..1) put(groupSend(g, a).toDouble()) })
    }

    fun loadJson(j: JSONObject) {
        bpm = j.optDouble("bpm", 120.0).coerceIn(40.0, 240.0)
        swing = j.optDouble("swing", 0.0).toFloat().coerceIn(0f, 0.5f)
        metronome = j.optBoolean("metronome", false)
        metronomeLevel = j.optDouble("metronomeLevel", 0.7).toFloat()
        countInBars = j.optInt("countInBars", 1)
        j.optJSONArray("mute")?.let { a -> for (s in 0 until minOf(SOUNDS, a.length())) mute[s] = a.getBoolean(s) }
        j.optJSONArray("solo")?.let { a -> for (s in 0 until minOf(SOUNDS, a.length())) solo[s] = a.getBoolean(s) }
        j.optJSONArray("groupMute")?.let { a -> for (g in 0 until minOf(GROUPS, a.length())) groupMute[g] = a.getBoolean(g) }
        j.optJSONArray("groupSolo")?.let { a -> for (g in 0 until minOf(GROUPS, a.length())) groupSolo[g] = a.getBoolean(g) }
        j.optJSONArray("groupVolume")?.let { a -> for (g in 0 until minOf(GROUPS, a.length())) setGroupVolume(g, a.getDouble(g).toFloat()) }
        j.optJSONArray("groupPan")?.let { a -> for (g in 0 until minOf(GROUPS, a.length())) setGroupPan(g, a.getDouble(g).toFloat()) }
        j.optJSONArray("groupSwing")?.let { a -> for (g in 0 until minOf(GROUPS, a.length())) setGroupSwing(g, a.getDouble(g).toFloat()) }
        for (g in 0 until GROUPS) { patterns[g].fill(null); current[g] = -1; queued[g] = -1 }
        scenes.fill(null)
        song.clear(); songMode = false; songPos = 0; songJump = -1
        if (j.optInt("version", 1) >= 3) {
            val gs = j.optJSONArray("groups") ?: JSONArray()
            for (g in 0 until minOf(GROUPS, gs.length())) {
                val o = gs.getJSONObject(g)
                val ps = o.optJSONArray("patterns") ?: JSONArray()
                for (i in 0 until ps.length()) {
                    val po = ps.getJSONObject(i)
                    val slot = po.optInt("slot", i)
                    if (slot in 0 until SLOTS) patterns[g][slot] = PatternData.fromJson(po)
                }
                current[g] = o.optInt("current", -1).takeIf { it in 0 until SLOTS && patterns[g][it] != null } ?: -1
            }
            val sc = j.optJSONArray("scenes") ?: JSONArray()
            for (i in 0 until sc.length()) {
                val o = sc.getJSONObject(i)
                val slot = o.optInt("slot", i)
                val a = o.getJSONArray("patterns")
                if (slot in 0 until SLOTS) scenes[slot] = IntArray(GROUPS) { if (it < a.length()) a.getInt(it) else -1 }
            }
            currentScene = j.optInt("currentScene", -1)
            song.clear()
            j.optJSONArray("song")?.let { a -> for (i in 0 until a.length()) a.getInt(i).takeIf { it in 0 until SLOTS }?.let { song.add(it) } }
            songMode = j.optBoolean("songMode", false)
            loop = j.optBoolean("loop", false)
            performType = j.optInt("performType", 0)
            val sends = j.optJSONArray("groupSends")
            for (g in 0 until GROUPS) for (a in 0..1) setGroupSend(g, a, sends?.optDouble(g * 2 + a, 0.0)?.toFloat() ?: 0f)
            songPos = 0; songJump = -1
        } else {
            // version 1 / 2: one 16-step pattern for each group (sound rows of 16 velocities)
            val va = j.optJSONArray("vel")
            val na = j.optJSONArray("notes")
            for (g in 0 until GROUPS) {
                val d = PatternData(16)
                var any = false
                for (p in 0 until PADS) {
                    val s = g * PADS + p
                    val row = va?.optJSONArray(s) ?: continue
                    val nrow = na?.optJSONArray(s)
                    for (t in 0 until minOf(16, row.length())) {
                        val v = row.getDouble(t).toFloat()
                        if (v > 0f) { d.vel[p][t] = v.coerceIn(0f, 1f); any = true }
                        if (nrow != null && t < nrow.length()) d.note[p][t] = nrow.getDouble(t).toFloat().coerceIn(0f, 127f)
                    }
                }
                j.optJSONArray("extra")?.let { ex ->
                    for (i in 0 until ex.length()) {
                        val e = ex.getJSONArray(i)
                        val s = e.getInt(0); val t = e.getInt(1); val x = e.getInt(2)
                        if (s / PADS == g && t < 16 && x < EXTRA) {
                            d.xvel[s % PADS][t * EXTRA + x] = e.getDouble(3).toFloat(); d.xnote[s % PADS][t * EXTRA + x] = e.getInt(4).toFloat(); any = true
                        }
                    }
                }
                if (any) { patterns[g][0] = d; current[g] = 0 }
            }
        }
        for (g in 0 until GROUPS) { f.put(OFF_PENDING + g, -1f); writeBank(g, active(g), cur(g)) }
    }

    /** A simple starting beat so PLAY does something on first run. */
    private fun loadDefault() {
        val d = PatternData(16)
        for (t in 0 until 16) {
            if (t % 4 == 0) d.vel[0][t] = 1f                               // kick
            if (t == 4 || t == 12) d.vel[1][t] = 0.9f                      // snare
            if (t % 2 == 0) d.vel[2][t] = if (t % 4 == 0) 0.75f else 0.55f // closed hat
        }
        d.vel[3][14] = 0.6f                                                // open hat
        patterns[0][0] = d
        current[0] = 0
        writeBank(0, active(0), d)
    }

    // ---- persistence ----

    fun save(ctx: Context) {
        try {
            file(ctx).writeText(toJson().toString())
        } catch (e: Exception) {
            AppLog.i("Pattern save failed: $e")
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "pattern.json")

    fun load(ctx: Context): Pattern {
        val f = file(ctx)
        if (f.exists()) {
            try {
                loadJson(JSONObject(f.readText()))
                AppLog.i("Song loaded (${"%.1f".format(bpm)} BPM)")
                return this
            } catch (e: Exception) {
                AppLog.i("Pattern file unreadable: $e")
            }
        }
        loadDefault()
        return this
    }
}

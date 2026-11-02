package org.openmk3.core.audio

import android.content.Context
import org.openmk3.core.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One bar of 16th notes for each of the 128 sounds (8 groups x 16 pads).
 *
 * All values live in a direct buffer that the native audio engine reads in its real-time
 * callback (layout must match engine.cpp). Editing a step is a plain float write; aligned
 * 32-bit writes are atomic, and a change simply takes effect on the next callback.
 */
class Pattern {
    companion object {
        const val GROUPS = 8
        const val PADS = 16
        const val SOUNDS = GROUPS * PADS
        const val STEPS = 16
        private const val OFF_VEL = 0
        private const val OFF_MUTE = OFF_VEL + SOUNDS * STEPS
        private const val OFF_SOLO = OFF_MUTE + SOUNDS
        private const val OFF_SKIP = OFF_SOLO + SOUNDS
        private const val OFF_BPM = OFF_SKIP + SOUNDS * STEPS
        private const val OFF_SWING = OFF_BPM + 1
        private const val OFF_GMUTE = OFF_SWING + 1
        private const val OFF_GSOLO = OFF_GMUTE + GROUPS
        private const val FLOATS = OFF_GSOLO + GROUPS
    }

    /** Shared with the native engine. */
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(FLOATS * 4).order(ByteOrder.nativeOrder())
    private val f = buffer.asFloatBuffer()

    /** A row of 16 floats in the shared buffer, usable like a FloatArray. */
    inner class Row(private val base: Int) {
        operator fun get(i: Int): Float = f.get(base + i)
        operator fun set(i: Int, v: Float) { f.put(base + i, v) }
        fun fill(v: Float) { for (i in 0 until STEPS) f.put(base + i, v) }
        fun all(pred: (Float) -> Boolean): Boolean = (0 until STEPS).all { pred(get(it)) }
        fun toList(): List<Float> = (0 until STEPS).map { get(it) }
    }

    /** [size] flags stored as 0f / 1f. */
    inner class Flags(private val base: Int, private val size: Int = STEPS) {
        operator fun get(i: Int): Boolean = f.get(base + i) > 0.5f
        operator fun set(i: Int, v: Boolean) { f.put(base + i, if (v) 1f else 0f) }
        fun fill(v: Boolean) { for (i in 0 until size) set(i, v) }
        fun any(range: IntRange = 0 until size): Boolean = range.any { get(it) }
        fun toList(): List<Boolean> = (0 until size).map { get(it) }
    }

    /** Velocity 0..1 per sound and step; 0 = step off. */
    val vel = Array(SOUNDS) { Row(OFF_VEL + it * STEPS) }
    val mute = Flags(OFF_MUTE, SOUNDS)
    val solo = Flags(OFF_SOLO, SOUNDS)
    val groupMute = Flags(OFF_GMUTE, GROUPS)
    val groupSolo = Flags(OFF_GSOLO, GROUPS)
    /** Set when a live-recorded hit lands on a step the playhead hasn't reached yet, so it isn't heard twice. */
    val skipOnce = Array(SOUNDS) { Flags(OFF_SKIP + it * STEPS) }

    var bpm: Double
        get() = f.get(OFF_BPM).toDouble()
        set(v) { f.put(OFF_BPM, v.toFloat()) }

    /** 0 = straight, 0.5 = maximum (odd 16ths delayed by half a step). */
    var swing: Float
        get() = f.get(OFF_SWING)
        set(v) { f.put(OFF_SWING, v) }

    init {
        bpm = 120.0
    }

    /** Same rule as the engine: group mute/solo, then sound mute/solo within the group. */
    fun groupAudible(g: Int) = !groupMute[g] && (!groupSolo.any() || groupSolo[g])

    fun audible(sound: Int): Boolean {
        val g = sound / PADS
        if (!groupAudible(g)) return false
        return if (solo.any(g * PADS until (g + 1) * PADS)) solo[sound] else !mute[sound]
    }

    /** Kept for callers; solo state is derived on the fly now. */
    fun updateSolo() {}

    fun groupIsEmpty(g: Int) = (g * PADS until (g + 1) * PADS).all { isEmpty(it) }

    fun isEmpty(sound: Int) = vel[sound].all { it == 0f }

    fun clear(sound: Int) = vel[sound].fill(0f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 2)
        put("bpm", bpm)
        put("swing", swing.toDouble())
        put("vel", JSONArray().apply { for (s in vel) put(JSONArray(s.toList().map { it.toDouble() })) })
        put("mute", JSONArray(mute.toList()))
        put("solo", JSONArray(solo.toList()))
        put("groupMute", JSONArray(groupMute.toList()))
        put("groupSolo", JSONArray(groupSolo.toList()))
    }

    private fun loadJson(j: JSONObject) {
        bpm = j.optDouble("bpm", 120.0).coerceIn(40.0, 240.0)
        swing = j.optDouble("swing", 0.0).toFloat().coerceIn(0f, 0.5f)
        j.optJSONArray("vel")?.let { a ->
            for (s in 0 until minOf(SOUNDS, a.length())) {
                val row = a.getJSONArray(s)
                for (t in 0 until minOf(STEPS, row.length())) vel[s][t] = row.getDouble(t).toFloat().coerceIn(0f, 1f)
            }
        }
        j.optJSONArray("mute")?.let { a -> for (s in 0 until minOf(SOUNDS, a.length())) mute[s] = a.getBoolean(s) }
        j.optJSONArray("solo")?.let { a -> for (s in 0 until minOf(SOUNDS, a.length())) solo[s] = a.getBoolean(s) }
        j.optJSONArray("groupMute")?.let { a -> for (g in 0 until minOf(GROUPS, a.length())) groupMute[g] = a.getBoolean(g) }
        j.optJSONArray("groupSolo")?.let { a -> for (g in 0 until minOf(GROUPS, a.length())) groupSolo[g] = a.getBoolean(g) }
        updateSolo()
    }

    /** A simple starting beat so PLAY does something on first run. */
    private fun loadDefault() {
        for (t in 0 until STEPS) {
            if (t % 4 == 0) vel[0][t] = 1f                               // kick
            if (t == 4 || t == 12) vel[1][t] = 0.9f                      // snare
            if (t % 2 == 0) vel[2][t] = if (t % 4 == 0) 0.75f else 0.55f // closed hat
        }
        vel[3][14] = 0.6f                                                // open hat
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
                AppLog.i("Pattern loaded (${"%.1f".format(bpm)} BPM)")
                return this
            } catch (e: Exception) {
                AppLog.i("Pattern file unreadable: $e")
            }
        }
        loadDefault()
        return this
    }
}

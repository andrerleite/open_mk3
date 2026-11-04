package org.openmk3.core.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.openmk3.core.AppLog
import java.io.File

/**
 * One zone: a sample played for a key range (pitched from its root key) and velocity range,
 * with its own play range, loop, tuning, level, pan and (optionally) envelope times.
 */
class Zone(val sample: Sample, var root: Int, var lo: Int, var hi: Int, var vlo: Int = 1, var vhi: Int = 127) {
    var start = 0f
    var end = 1f
    var tune = 0f       // semitones
    var gain = 0f       // dB
    var pan = 0f
    var loop = -1       // -1 as the sound, 0 off, 1 on
    var lstart = 0f
    var lend = 1f
    var xfade = 0f
    var attack = -1f    // seconds, -1 = as the sound
    var decay = -1f

    fun params() = floatArrayOf(start, end, tune, gain, pan, loop.toFloat(), lstart, lend, xfade, attack, decay)

    fun settingsJson(o: JSONObject): JSONObject = o.put("start", start.toDouble()).put("end", end.toDouble())
        .put("tune", tune.toDouble()).put("gain", gain.toDouble()).put("pan", pan.toDouble()).put("loop", loop)
        .put("lstart", lstart.toDouble()).put("lend", lend.toDouble()).put("xfade", xfade.toDouble())
        .put("att", attack.toDouble()).put("dec", decay.toDouble())

    fun readSettings(q: JSONObject) {
        start = q.optDouble("start", 0.0).toFloat(); end = q.optDouble("end", 1.0).toFloat()
        tune = q.optDouble("tune", 0.0).toFloat(); gain = q.optDouble("gain", 0.0).toFloat(); pan = q.optDouble("pan", 0.0).toFloat()
        loop = q.optInt("loop", -1); lstart = q.optDouble("lstart", 0.0).toFloat(); lend = q.optDouble("lend", 1.0).toFloat()
        xfade = q.optDouble("xfade", 0.0).toFloat(); attack = q.optDouble("att", -1.0).toFloat(); decay = q.optDouble("dec", -1.0).toFloat()
    }

    fun copySettings(o: Zone) {
        start = o.start; end = o.end; tune = o.tune; gain = o.gain; pan = o.pan; loop = o.loop
        lstart = o.lstart; lend = o.lend; xfade = o.xfade; attack = o.attack; decay = o.decay
    }
}

/**
 * Sample zones: up to 16 extra samples per sound (multisampled instruments). A note uses the
 * first zone holding its key and velocity, otherwise the sound's own sample. Saved as
 * zones.json and in projects (file paths, like the kit).
 */
class Zones(private val native: NativeEngine) {

    companion object {
        const val MAX = 16
        private val NOTE = Regex("(?i)(?:^|[^A-Za-z])([A-G])(#|b|s)?(-?[0-9])(?![0-9])")
        private val NUMBER = Regex("(?:^|[^0-9])([0-9]{2,3})(?![0-9])")

        /** Root key from a file name ("Piano C3", "Bass_F#1", "Pad 060"; 60 = C3), or null. */
        fun rootFromName(name: String): Int? {
            NOTE.findAll(name).lastOrNull()?.let { m ->
                val base = intArrayOf(9, 11, 0, 2, 4, 5, 7)["ABCDEFG".indexOf(m.groupValues[1].uppercase())]
                val acc = when (m.groupValues[2]) { "#", "s" -> 1; "b" -> -1; else -> 0 }
                val n = base + acc + (m.groupValues[3].toInt() + 2) * 12
                if (n in 0..127) return n
            }
            NUMBER.findAll(name).lastOrNull()?.let { m -> m.groupValues[1].toInt().takeIf { it in 21..108 }?.let { return it } }
            return null
        }
    }

    private val z = Array(Pattern.SOUNDS) { mutableListOf<Zone>() }

    fun of(sound: Int): List<Zone> = z[sound]

    private fun push(sound: Int) {
        val list = z[sound]
        for (i in 0 until MAX) {
            val zn = list.getOrNull(i)
            if (zn == null) native.nativeSetZone(sound, i, null, 1, 60, 0, 127, 1, 127)
            else {
                native.nativeSetZone(sound, i, zn.sample.data, zn.sample.channels, zn.root, zn.lo, zn.hi, zn.vlo, zn.vhi)
                native.nativeZoneParams(sound, i, zn.params())
            }
        }
    }

    private fun pushMap(sound: Int, i: Int) {
        val zn = z[sound][i]
        native.nativeZoneMap(sound, i, zn.root, zn.lo, zn.hi, zn.vlo, zn.vhi)
    }

    /** Adds [s] as a zone (root from its name, else after the last zone). Returns its index or -1. */
    fun add(sound: Int, s: Sample, root: Int? = null): Int {
        val list = z[sound]
        if (list.size >= MAX) return -1
        val r = root ?: rootFromName(s.name) ?: ((list.maxOfOrNull { it.root } ?: 59) + 1).coerceAtMost(127)
        list.add(Zone(s, r, r, r))
        val i = list.size - 1
        native.nativeSetZone(sound, i, s.data, s.channels, r, r, r, 1, 127)
        return i
    }

    fun remove(sound: Int, i: Int) {
        if (i !in z[sound].indices) return
        z[sound].removeAt(i)
        push(sound)
    }

    fun clear(sound: Int) {
        if (z[sound].isEmpty()) return
        z[sound].clear()
        push(sound)
    }

    fun update(sound: Int, i: Int, f: (Zone) -> Unit) {
        val zn = z[sound].getOrNull(i) ?: return
        f(zn)
        zn.start = zn.start.coerceIn(0f, 1f); zn.end = zn.end.coerceIn(zn.start, 1f)
        zn.tune = zn.tune.coerceIn(-48f, 48f); zn.gain = zn.gain.coerceIn(-60f, 12f); zn.pan = zn.pan.coerceIn(-1f, 1f)
        zn.loop = zn.loop.coerceIn(-1, 1); zn.lstart = zn.lstart.coerceIn(0f, 1f); zn.lend = zn.lend.coerceIn(zn.lstart, 1f)
        zn.xfade = zn.xfade.coerceIn(0f, 1f)
        native.nativeZoneParams(sound, i, zn.params())
        zn.root = zn.root.coerceIn(0, 127)
        zn.lo = zn.lo.coerceIn(0, 127); zn.hi = zn.hi.coerceIn(zn.lo, 127)
        zn.vlo = zn.vlo.coerceIn(1, 127); zn.vhi = zn.vhi.coerceIn(zn.vlo, 127)
        pushMap(sound, i)
    }

    /** Spreads the key ranges between the roots (each zone reaches halfway to its neighbours). */
    fun autoMap(sound: Int) {
        val list = z[sound]
        if (list.isEmpty()) return
        list.sortBy { it.root }
        for ((k, zn) in list.withIndex()) {
            zn.lo = if (k == 0) 0 else (list[k - 1].root + zn.root) / 2 + 1
            zn.hi = if (k == list.size - 1) 127 else (zn.root + list[k + 1].root) / 2
            zn.vlo = 1; zn.vhi = 127
        }
        push(sound)
    }

    fun copy(from: Int, to: Int) {
        z[to].clear()
        for (zn in z[from]) z[to].add(Zone(zn.sample, zn.root, zn.lo, zn.hi, zn.vlo, zn.vhi).also { it.copySettings(zn) })
        push(to)
    }

    fun toJson(): JSONArray = JSONArray().apply {
        for (s in 0 until Pattern.SOUNDS) {
            val list = z[s].filter { it.sample.path != null }
            if (list.isEmpty()) continue
            put(JSONObject().put("sound", s).put("zones", JSONArray().apply {
                for (zn in list) put(zn.settingsJson(JSONObject().put("path", zn.sample.path).put("root", zn.root).put("lo", zn.lo)
                    .put("hi", zn.hi).put("vlo", zn.vlo).put("vhi", zn.vhi)))
            }))
        }
    }

    /** Decodes the zones' files (call off the UI thread). */
    fun loadJson(a: JSONArray?) {
        for (s in 0 until Pattern.SOUNDS) if (z[s].isNotEmpty()) clear(s)
        if (a == null) return
        for (k in 0 until a.length()) {
            val o = a.getJSONObject(k)
            val s = o.optInt("sound", -1)
            if (s !in 0 until Pattern.SOUNDS) continue
            val zs = o.getJSONArray("zones")
            for (i in 0 until minOf(MAX, zs.length())) {
                val q = zs.getJSONObject(i)
                val path = q.getString("path")
                val sample = try { WavDecoder.decode(File(path)) } catch (e: Exception) { AppLog.i("Zone missing: $path"); continue }
                z[s].add(Zone(sample, q.optInt("root", 60), q.optInt("lo", 0), q.optInt("hi", 127), q.optInt("vlo", 1), q.optInt("vhi", 127)).also { it.readSettings(q) })
            }
            push(s)
        }
    }

    fun save(ctx: Context) {
        try { File(ctx.filesDir, "zones.json").writeText(toJson().toString()) } catch (e: Exception) { AppLog.i("zones.json: ${e.message}") }
    }

    fun load(ctx: Context) {
        val f = File(ctx.filesDir, "zones.json")
        if (!f.exists()) return
        try { loadJson(JSONArray(f.readText())) } catch (e: Exception) { AppLog.i("zones.json: ${e.message}") }
    }
}

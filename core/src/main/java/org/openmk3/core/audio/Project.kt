package org.openmk3.core.audio

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import org.openmk3.core.AppLog
import org.openmk3.core.Edition
import java.io.File

/**
 * Projects: the song (patterns, scenes, mixer), the kit (sample file paths), every sound's
 * settings, effects, colours and keyboard settings, as one JSON file in
 * Download/<app folder>/Projects/<name>.omk3. Samples are referenced, not copied.
 */
object Project {
    const val EXT = "omk3"

    val dir: File
        get() = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Edition.storageFolder), "Projects")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("openmk3", Context.MODE_PRIVATE)
    fun currentName(ctx: Context): String? = prefs(ctx).getString("project", null)
    private fun setCurrent(ctx: Context, name: String?) = prefs(ctx).edit().putString("project", name).apply()

    fun list(): List<File> = dir.listFiles { f -> f.isFile && f.extension == EXT }.orEmpty().sortedByDescending { it.lastModified() }

    fun nextName(): String {
        var i = 1
        while (File(dir, "Project %03d.$EXT".format(i)).exists()) i++
        return "Project %03d".format(i)
    }

    fun save(ctx: Context, e: AudioEngine, colours: IntArray, keys: Keys, name: String): File {
        val j = JSONObject()
        j.put("version", 1)
        j.put("song", e.pattern.toJson())
        j.put("kit", JSONArray().apply { for (i in 0 until Pattern.SOUNDS) put(e.kit[i].path ?: JSONObject.NULL) })
        j.put("params", JSONArray().apply {
            for (s in 0 until Pattern.SOUNDS) put(JSONArray().apply { for (i in 0 until SoundParams.COUNT) put(e.params[s, i].toDouble()) })
        })
        j.put("fx", JSONArray().apply {
            for (c in 0 until FxParams.CHAINS) for (k in 0 until FxParams.SLOTS) {
                if (e.fx.type(c, k) == FxParams.NONE) continue
                put(JSONArray().apply { put(c); put(k); for (i in 0 until FxParams.PER_SLOT) put(e.fx[c, k, i].toDouble()) })
            }
        })
        j.put("poly", e.poly.toJson())
        j.put("zones", e.zones.toJson())
        j.put("colours", JSONArray(colours.toList()))
        j.put("keys", JSONObject().apply {
            put("root", keys.root); put("octave", keys.octave); put("scale", keys.scale)
            put("chordMode", keys.chordMode); put("chordType", keys.chordType); put("chordSet", keys.chordSet)
        })
        dir.mkdirs()
        val f = File(dir, "$name.$EXT")
        f.writeText(j.toString())
        setCurrent(ctx, name)
        AppLog.i("Project saved: ${f.absolutePath}")
        return f
    }

    /**
     * Loads a project. Samples are decoded on the calling (background) thread; [onSample] runs
     * per sound so the caller can install it. Returns null on success or an error message.
     */
    fun load(ctx: Context, e: AudioEngine, colours: IntArray, keys: Keys, f: File, onSample: (Int, Sample) -> Unit): String? {
        val j = try { JSONObject(f.readText()) } catch (ex: Exception) { return "Can't read ${f.name}: ${ex.message}" }
        e.pattern.loadJson(j.getJSONObject("song"))
        j.optJSONArray("params")?.let { a ->
            for (s in 0 until minOf(Pattern.SOUNDS, a.length())) {
                val row = a.getJSONArray(s)
                e.params.reset(s)
                for (i in 0 until minOf(SoundParams.COUNT, row.length())) e.params[s, i] = row.getDouble(i).toFloat()
            }
        }
        // effects: clear every slot, then re-create the saved ones
        for (c in 0 until FxParams.CHAINS) for (k in 0 until FxParams.SLOTS) if (e.fx.type(c, k) != FxParams.NONE) e.setFx(c, k, FxParams.NONE)
        j.optJSONArray("fx")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.getJSONArray(i)
                val c = r.getInt(0); val k = r.getInt(1)
                if (c !in 0 until FxParams.CHAINS || k !in 0 until FxParams.SLOTS) continue
                val type = r.getDouble(2).toInt()
                e.setFx(c, k, type)
                for (p in 0 until FxParams.PER_SLOT) if (p + 2 < r.length()) e.fx[c, k, p] = r.getDouble(p + 2).toFloat()
            }
        }
        e.poly.loadJson(j.optJSONArray("poly"))
        e.zones.loadJson(j.optJSONArray("zones"))
        j.optJSONArray("colours")?.let { a -> for (g in 0 until minOf(colours.size, a.length())) colours[g] = a.getInt(g) }
        j.optJSONObject("keys")?.let { k ->
            keys.root = k.optInt("root", 0); keys.octave = k.optInt("octave", 3); keys.scale = k.optInt("scale", 0)
            keys.chordMode = k.optInt("chordMode", 0); keys.chordType = k.optInt("chordType", 2); keys.chordSet = k.optInt("chordSet", 0)
        }
        // kit last (decoding takes a moment); missing files fall back to built-in / empty
        val kit = j.optJSONArray("kit")
        for (s in 0 until Pattern.SOUNDS) {
            val path = kit?.let { if (s < it.length() && !it.isNull(s)) it.getString(s) else null }
            val sample = if (path == null) e.builtIn[s] else try {
                WavDecoder.decode(File(path))
            } catch (ex: Exception) {
                AppLog.i("Project: ${soundLabel(s)} missing $path"); e.builtIn[s]
            }
            onSample(s, sample)
        }
        setCurrent(ctx, f.nameWithoutExtension)
        AppLog.i("Project loaded: ${f.name}")
        return null
    }

    /** An empty project: no patterns, built-in kit in group A, default settings, no effects. */
    fun clear(ctx: Context, e: AudioEngine) {
        e.pattern.loadJson(JSONObject().put("version", 3).put("bpm", 120.0))
        for (s in 0 until Pattern.SOUNDS) { e.params.reset(s); e.poly.reset(s); e.zones.clear(s); e.setSample(s, e.builtIn[s]) }
        for (c in 0 until FxParams.CHAINS) for (k in 0 until FxParams.SLOTS) if (e.fx.type(c, k) != FxParams.NONE) e.setFx(c, k, FxParams.NONE)
        setCurrent(ctx, null)
    }
}

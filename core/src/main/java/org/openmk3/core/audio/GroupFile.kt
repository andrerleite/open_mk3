package org.openmk3.core.audio

import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import org.openmk3.core.AppLog
import org.openmk3.core.Edition
import java.io.File

/**
 * A group on its own (a kit with its patterns): the 16 sounds' samples (paths), settings,
 * synth patches, zones and effects, the group's effects, mixer and colour, and its patterns.
 * Saved as Download/<app folder>/Groups/<name>.omk3g; loaded into any group.
 */
object GroupFile {
    const val EXT = "omk3g"

    val dir: File
        get() = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Edition.storageFolder), "Groups")

    fun list(): List<File> = dir.listFiles { f -> f.isFile && f.extension == EXT }.orEmpty().sortedByDescending { it.lastModified() }

    private fun fxJson(e: AudioEngine, chain: Int) = JSONArray().apply {
        for (k in 0 until FxParams.SLOTS) {
            if (e.fx.type(chain, k) == FxParams.NONE) { put(JSONObject.NULL); continue }
            put(JSONArray().apply { for (i in 0 until FxParams.PER_SLOT) put(e.fx[chain, k, i].toDouble()) })
        }
    }

    private fun loadFx(e: AudioEngine, chain: Int, a: JSONArray?) {
        for (k in 0 until FxParams.SLOTS) {
            val r = a?.optJSONArray(k)
            val type = r?.optDouble(0, 0.0)?.toInt() ?: FxParams.NONE
            if (e.fx.type(chain, k) != type) e.setFx(chain, k, type)
            if (r != null) for (p in 0 until minOf(FxParams.PER_SLOT, r.length())) e.fx[chain, k, p] = r.getDouble(p).toFloat()
        }
    }

    fun save(e: AudioEngine, g: Int, colour: Int, name: String): File {
        val p = e.pattern
        val j = JSONObject().put("version", 1).put("colour", colour)
        j.put("mixer", JSONObject().put("vol", p.groupVolume(g).toDouble()).put("pan", p.groupPan(g).toDouble()).put("swing", p.groupSwing(g).toDouble()))
        j.put("groupFx", fxJson(e, FxParams.GROUP0 + g))
        j.put("sounds", JSONArray().apply { for (k in 0 until 16) put(soundJson(e, g * 16 + k)) })
        j.put("patterns", JSONArray().apply { for (d in p.patterns[g]) put(d?.toJson() ?: JSONObject.NULL) })
        j.put("current", p.current[g])
        dir.mkdirs()
        val f = File(dir, "${Project.clean(name)}.$EXT")
        f.writeText(j.toString())
        AppLog.i("Group saved: ${f.absolutePath}")
        return f
    }

    /**
     * Loads [f] into group [g] (decoding samples on the calling background thread). [onSample]
     * installs each sound's sample, [onDone] runs the rest on the caller's thread of choice.
     * Returns the group's colour, or null on error.
     */
    fun load(e: AudioEngine, g: Int, f: File, onSample: (Int, Sample) -> Unit): Int? {
        val j = try { JSONObject(f.readText()) } catch (ex: Exception) { AppLog.i("Group: can't read ${f.name}: ${ex.message}"); return null }
        val p = e.pattern
        val sounds = j.optJSONArray("sounds") ?: return null
        for (k in 0 until minOf(16, sounds.length())) loadSound(e, g * 16 + k, sounds.getJSONObject(k), onSample)
        loadFx(e, FxParams.GROUP0 + g, j.optJSONArray("groupFx"))
        j.optJSONObject("mixer")?.let { m ->
            p.setGroupVolume(g, m.optDouble("vol", 0.0).toFloat()); p.setGroupPan(g, m.optDouble("pan", 0.0).toFloat()); p.setGroupSwing(g, m.optDouble("swing", 0.0).toFloat())
        }
        j.optJSONArray("patterns")?.let { a ->
            for (i in 0 until Pattern.SLOTS) p.patterns[g][i] = if (i < a.length() && !a.isNull(i)) PatternData.fromJson(a.getJSONObject(i)) else null
            p.current[g] = j.optInt("current", -1).takeIf { it in 0 until Pattern.SLOTS && p.patterns[g][it] != null } ?: -1
            p.refreshGroup(g)
        }
        AppLog.i("Group loaded into ${'A' + g}: ${f.name}")
        return j.optInt("colour", -1)
    }

    // ---- one sound (its sample, settings, synth patch, zones, effects) ----
    const val SOUND_EXT = "omk3s"
    val soundDir: File get() = File(dir.parentFile, "Sounds")
    fun soundList(): List<File> = soundDir.listFiles { f -> f.isFile && f.extension == SOUND_EXT }.orEmpty().sortedByDescending { it.lastModified() }

    fun soundJson(e: AudioEngine, s: Int): JSONObject {
        val o = JSONObject()
        o.put("sample", e.kit[s].path ?: JSONObject.NULL)
        o.put("params", JSONArray().apply { for (i in 0 until SoundParams.COUNT) put(e.params[s, i].toDouble()) })
        o.put("poly", JSONArray().apply { for (i in 0 until e.poly.count) put(e.poly[s, i].toDouble()) }).put("polyName", e.poly.patchName[s])
        o.put("fx", fxJson(e, s))
        o.put("zones", JSONArray().apply {
            for (z in e.zones.of(s)) if (z.sample.path != null) put(z.settingsJson(JSONObject().put("path", z.sample.path)
                .put("root", z.root).put("lo", z.lo).put("hi", z.hi).put("vlo", z.vlo).put("vhi", z.vhi)))
        })
        return o
    }

    fun saveSound(e: AudioEngine, s: Int, name: String): File {
        soundDir.mkdirs()
        val f = File(soundDir, "${Project.clean(name)}.$SOUND_EXT")
        f.writeText(soundJson(e, s).put("version", 1).toString())
        AppLog.i("Sound saved: ${f.absolutePath}")
        return f
    }

    /** Loads a saved sound onto sound [s] (call off the UI thread). */
    fun loadSoundFile(e: AudioEngine, s: Int, f: File, onSample: (Int, Sample) -> Unit): Boolean {
        val o = try { JSONObject(f.readText()) } catch (ex: Exception) { AppLog.i("Sound: can't read ${f.name}"); return false }
        loadSound(e, s, o, onSample)
        AppLog.i("Sound loaded: ${f.name}")
        return true
    }

    private fun loadSound(e: AudioEngine, s: Int, o: JSONObject, onSample: (Int, Sample) -> Unit) {
        run {
            val path = if (o.isNull("sample")) null else o.optString("sample")
            val sample = if (path == null) e.builtIn[s] else try { WavDecoder.decode(File(path)) } catch (ex: Exception) {
                AppLog.i("Group: missing $path"); e.builtIn[s]
            }
            onSample(s, sample)
            e.params.reset(s)
            o.optJSONArray("params")?.let { a -> for (i in 0 until minOf(SoundParams.COUNT, a.length())) e.params[s, i] = a.getDouble(i).toFloat() }
            e.poly.reset(s)
            o.optJSONArray("poly")?.let { a -> for (i in 0 until minOf(e.poly.count, a.length())) e.poly[s, i] = a.getDouble(i).toFloat() }
            e.poly.patchName[s] = o.optString("polyName", "INIT")
            loadFx(e, s, o.optJSONArray("fx"))
            e.zones.clear(s)
            o.optJSONArray("zones")?.let { zs ->
                for (i in 0 until zs.length()) {
                    val q = zs.getJSONObject(i)
                    val zsmp = try { WavDecoder.decode(File(q.getString("path"))) } catch (ex: Exception) { continue }
                    val idx = e.zones.add(s, zsmp, q.optInt("root", 60))
                    if (idx >= 0) e.zones.update(s, idx) { z ->
                        z.lo = q.optInt("lo", 0); z.hi = q.optInt("hi", 127); z.vlo = q.optInt("vlo", 1); z.vhi = q.optInt("vhi", 127); z.readSettings(q)
                    }
                }
            }
        }
    }

}

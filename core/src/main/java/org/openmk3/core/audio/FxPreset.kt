package org.openmk3.core.audio

import org.json.JSONArray
import org.json.JSONObject
import org.openmk3.core.AppLog
import java.io.File

/** Effect presets: one effect slot's type and settings, in Download/<app folder>/Presets/<name>.omk3fx. */
object FxPreset {
    const val EXT = "omk3fx"
    val dir: File get() = File(GroupFile.dir.parentFile, "Presets")

    /** Saves slot [slot] of [chain]; the name is the effect's type and a number. Returns the file. */
    fun save(e: AudioEngine, chain: Int, slot: Int): File? {
        val type = e.fx.type(chain, slot)
        if (type == FxParams.NONE) return null
        dir.mkdirs()
        val base = FxParams.TYPES.getOrElse(type) { "FX" }
        var i = 1
        var f: File
        do { f = File(dir, "%s %03d.%s".format(base, i++, EXT)) } while (f.exists())
        f.writeText(JSONObject().put("type", type)
            .put("v", JSONArray().apply { for (k in 0 until FxParams.PER_SLOT) put(e.fx[chain, slot, k].toDouble()) }).toString())
        AppLog.i("FX preset saved: ${f.absolutePath}")
        return f
    }

    /** Loads [f] into slot [slot] of [chain] (the effect there is replaced). */
    fun load(e: AudioEngine, chain: Int, slot: Int, f: File): Boolean {
        val j = try { JSONObject(f.readText()) } catch (ex: Exception) { return false }
        val type = j.optInt("type", 0)
        if (type !in 1 until FxParams.TYPES.size) return false
        e.setFx(chain, slot, type)
        val v = j.optJSONArray("v") ?: return true
        for (k in 1 until minOf(FxParams.PER_SLOT, v.length())) e.fx[chain, slot, k] = v.getDouble(k).toFloat()
        e.fx.save(e.ctx)
        return true
    }
}

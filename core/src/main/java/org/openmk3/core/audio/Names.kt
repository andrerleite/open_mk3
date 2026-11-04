package org.openmk3.core.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.openmk3.core.AppLog
import java.io.File

/** Names given to sounds and groups (empty: the sound shows its sample's name, a group its letter). */
object Names {
    val sound = Array(Pattern.SOUNDS) { "" }
    val group = Array(8) { "" }

    fun toJson(): JSONObject = JSONObject().put("sounds", JSONArray(sound.toList())).put("groups", JSONArray(group.toList()))

    fun loadJson(j: JSONObject?) {
        sound.fill(""); group.fill("")
        j?.optJSONArray("sounds")?.let { a -> for (i in 0 until minOf(sound.size, a.length())) sound[i] = a.optString(i, "") }
        j?.optJSONArray("groups")?.let { a -> for (i in 0 until minOf(group.size, a.length())) group[i] = a.optString(i, "") }
    }

    fun save(ctx: Context) {
        try { File(ctx.filesDir, "names.json").writeText(toJson().toString()) } catch (e: Exception) { AppLog.i("names.json: ${e.message}") }
    }

    fun load(ctx: Context) {
        val f = File(ctx.filesDir, "names.json")
        try { loadJson(if (f.exists()) JSONObject(f.readText()) else null) } catch (e: Exception) { loadJson(null) }
    }
}

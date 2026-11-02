package org.openmk3.core.audio

import android.content.Context
import org.openmk3.core.AppLog
import org.json.JSONArray
import java.io.File

/** Which WAV file is loaded on each pad (null = built-in sound), saved as kit.json. */
object KitStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "kit.json")

    fun load(ctx: Context): Array<String?> {
        val paths = arrayOfNulls<String>(Pattern.SOUNDS)
        val f = file(ctx)
        if (!f.exists()) return paths
        try {
            val a = JSONArray(f.readText())
            // Older files have 16 entries: they become group A.
            for (i in 0 until minOf(Pattern.SOUNDS, a.length())) paths[i] = if (a.isNull(i)) null else a.getString(i)
        } catch (e: Exception) {
            AppLog.i("kit.json unreadable: $e")
        }
        return paths
    }

    fun save(ctx: Context, kit: Array<Sample>) {
        val a = JSONArray()
        for (i in 0 until Pattern.SOUNDS) a.put(kit[i].path ?: JSONObjectNull)
        file(ctx).writeText(a.toString())
    }

    private val JSONObjectNull = org.json.JSONObject.NULL
}

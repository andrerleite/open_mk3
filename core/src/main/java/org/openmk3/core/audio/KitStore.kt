package org.openmk3.core.audio

import android.content.Context
import org.openmk3.core.AppLog
import org.json.JSONArray
import java.io.File

/** Which WAV file is loaded on each pad (null = built-in sound), saved as kit.json. */
object KitStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "kit.json")

    fun load(ctx: Context): Array<String?> {
        val paths = arrayOfNulls<String>(16)
        val f = file(ctx)
        if (!f.exists()) return paths
        try {
            val a = JSONArray(f.readText())
            for (i in 0 until minOf(16, a.length())) paths[i] = if (a.isNull(i)) null else a.getString(i)
        } catch (e: Exception) {
            AppLog.i("kit.json unreadable: $e")
        }
        return paths
    }

    fun save(ctx: Context, kit: Array<Sample>) {
        val a = JSONArray()
        for (s in kit) a.put(s.path ?: JSONObjectNull)
        file(ctx).writeText(a.toString())
    }

    private val JSONObjectNull = org.json.JSONObject.NULL
}

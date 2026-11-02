package org.openmk3.core.ui

import android.content.Context
import org.json.JSONArray
import org.openmk3.core.AppLog
import org.openmk3.core.mk3.Mk3Colors
import java.io.File

/**
 * Group colours (saved as colours.json). Sound colours live in the sound parameters
 * (COLOUR, 0 = follow the group). Values are controller colour indices 1..16.
 */
object Colours {
    private val DEFAULTS = intArrayOf(
        Mk3Colors.RED, Mk3Colors.ORANGE, Mk3Colors.YELLOW, Mk3Colors.GREEN,
        Mk3Colors.CYAN, Mk3Colors.BLUE, Mk3Colors.VIOLET, Mk3Colors.MAGENTA,
    )
    val group = DEFAULTS.copyOf()

    private fun file(ctx: Context) = File(ctx.filesDir, "colours.json")

    fun load(ctx: Context) {
        try {
            val f = file(ctx)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (g in 0 until minOf(8, a.length())) group[g] = a.getInt(g).coerceIn(1, 16)
        } catch (e: Exception) {
            AppLog.i("colours.json unreadable: $e")
        }
    }

    fun save(ctx: Context) {
        try {
            file(ctx).writeText(JSONArray(group.toList()).toString())
        } catch (e: Exception) {
            AppLog.i("colours.json save failed: $e")
        }
    }
}

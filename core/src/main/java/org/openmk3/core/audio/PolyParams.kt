package org.openmk3.core.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.openmk3.core.AppLog
import java.io.File
import kotlin.math.roundToInt

/**
 * POLY source (the OB-Xf synth engine): each sound's patch, 104 parameters of 0..1 in OB-Xf's
 * order. Names, ranges and value texts come from the engine. Saved as poly.json and in projects.
 */
class PolyParams(private val native: NativeEngine) {

    val count = native.nativePolyParamCount()
    val names = Array(count) { native.nativePolyParamName(it) }
    val groups = Array(count) { native.nativePolyParamGroup(it) }
    val defaults = FloatArray(count) { native.nativePolyParamDefault(it) }
    val steps = IntArray(count) { native.nativePolyParamSteps(it) }

    private val v = Array(Pattern.SOUNDS) { defaults.copyOf() }
    /** Name of each sound's patch (the preset it came from, "INIT" otherwise). */
    val patchName = Array(Pattern.SOUNDS) { "INIT" }

    operator fun get(sound: Int, i: Int) = v[sound][i]
    operator fun set(sound: Int, i: Int, value: Float) {
        val x = value.coerceIn(0f, 1f)
        v[sound][i] = x
        native.nativePolySet(sound, i, x)
    }

    fun display(sound: Int, i: Int): String = native.nativePolyDisplay(i, v[sound][i])

    /** Knob turn: continuous parameters move smoothly, stepped ones one step per notch. */
    private val acc = HashMap<Int, Int>()
    fun nudge(sound: Int, i: Int, delta: Int, fine: Boolean) {
        val st = steps[i]
        if (st == 0) {
            this[sound, i] = v[sound][i] + delta * (if (fine) 0.0002f else 0.002f)
            return
        }
        val a = (acc[i] ?: 0) + delta
        if (kotlin.math.abs(a) < (if (st == 1) 25 else 12)) { acc[i] = a; return }
        acc[i] = 0
        val cur = (v[sound][i] * st).roundToInt()
        this[sound, i] = (cur + (if (a > 0) 1 else -1)).coerceIn(0, st) / st.toFloat()
    }

    fun reset(sound: Int) {
        for (i in 0 until count) this[sound, i] = defaults[i]
        patchName[sound] = "INIT"
    }

    fun apply(sound: Int, p: PolyPresets.Preset) {
        for (i in 0 until count) this[sound, i] = p.values.getOrElse(i) { defaults[i] }
        patchName[sound] = p.name
    }

    fun copy(from: Int, to: Int) {
        for (i in 0 until count) this[to, i] = v[from][i]
        patchName[to] = patchName[from]
    }

    /** Sends every patch to the engine (after start or a load). */
    fun pushAll() {
        for (s in 0 until Pattern.SOUNDS) for (i in 0 until count) native.nativePolySet(s, i, v[s][i])
    }

    /** Only sounds whose patch differs from the defaults are stored. */
    fun toJson(): JSONArray = JSONArray().apply {
        for (s in 0 until Pattern.SOUNDS) {
            if (patchName[s] == "INIT" && (0 until count).all { v[s][it] == defaults[it] }) continue
            put(JSONObject().put("sound", s).put("name", patchName[s])
                .put("v", JSONArray().apply { for (i in 0 until count) put(v[s][i].toDouble()) }))
        }
    }

    fun loadJson(a: JSONArray?) {
        for (s in 0 until Pattern.SOUNDS) reset(s)
        if (a == null) return
        for (k in 0 until a.length()) {
            val o = a.getJSONObject(k)
            val s = o.optInt("sound", -1)
            if (s !in 0 until Pattern.SOUNDS) continue
            val r = o.getJSONArray("v")
            for (i in 0 until minOf(count, r.length())) this[s, i] = r.getDouble(i).toFloat()
            patchName[s] = o.optString("name", "INIT")
        }
    }

    fun save(ctx: Context) {
        try { File(ctx.filesDir, "poly.json").writeText(toJson().toString()) } catch (e: Exception) { AppLog.i("poly.json: ${e.message}") }
    }

    fun load(ctx: Context) {
        val f = File(ctx.filesDir, "poly.json")
        try { loadJson(if (f.exists()) JSONArray(f.readText()) else null) } catch (e: Exception) { AppLog.i("poly.json: ${e.message}"); loadJson(null) }
    }
}

/** The OB-Xf factory patches (CC0), from assets/obxf/patches.json. */
object PolyPresets {
    class Preset(val name: String, val category: String, val author: String, val values: FloatArray)

    @Volatile private var all: List<Preset>? = null

    fun list(ctx: Context): List<Preset> {
        all?.let { return it }
        val out = mutableListOf<Preset>()
        try {
            val a = JSONArray(ctx.assets.open("obxf/patches.json").bufferedReader().use { it.readText() })
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val v = o.getJSONArray("v")
                out.add(Preset(o.getString("n"), o.optString("c", ""), o.optString("a", ""), FloatArray(v.length()) { v.getDouble(it).toFloat() }))
            }
        } catch (e: Exception) { AppLog.i("POLY presets: ${e.message}") }
        all = out
        return out
    }

    @Volatile private var cats: List<String>? = null
    fun categories(ctx: Context): List<String> = cats ?: list(ctx).map { it.category }.distinct().also { cats = it }
}

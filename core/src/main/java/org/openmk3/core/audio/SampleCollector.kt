package org.openmk3.core.audio

import org.json.JSONArray
import org.json.JSONObject
import org.openmk3.core.AppLog
import java.io.File

/**
 * "Save with samples": copies every WAV a saved project / group / sound file uses into a folder
 * next to it ("<name> Samples") and points the file at the copies, so the whole thing can be
 * moved or shared as one folder. Files already inside that folder are left alone; two different
 * samples with the same name get a number.
 */
object SampleCollector {
    class Result(val copied: Int, val missing: Int)

    fun collect(saved: File): Result {
        val folder = File(saved.parentFile, "${saved.nameWithoutExtension} Samples")
        val j = JSONObject(saved.readText())
        val done = HashMap<String, String>()   // original path -> copy
        var copied = 0; var missing = 0
        fun fix(path: String): String {
            if (!path.endsWith(".wav", true) || !path.startsWith("/")) return path   // only file paths
            done[path]?.let { return it }
            val src = File(path)
            if (src.parentFile?.canonicalPath == folder.canonicalPath) return path
            if (!src.isFile) { missing++; return path }
            folder.mkdirs()
            var dst = File(folder, src.name)
            var k = 2
            while (dst.exists() && dst.length() != src.length()) dst = File(folder, "${src.nameWithoutExtension} $k.${src.extension}").also { k++ }
            if (!dst.exists()) { src.copyTo(dst); copied++ }
            return dst.absolutePath.also { done[path] = it }
        }
        fun walk(v: Any?): Any? = when (v) {
            is JSONObject -> { for (key in v.keys().asSequence().toList()) v.put(key, walk(v.get(key))); v }
            is JSONArray -> { for (i in 0 until v.length()) v.put(i, walk(v.get(i))); v }
            is String -> fix(v)
            else -> v
        }
        walk(j)
        saved.writeText(j.toString())
        AppLog.i("Collected samples for ${saved.name}: $copied copied, $missing missing → ${folder.absolutePath}")
        return Result(copied, missing)
    }
}

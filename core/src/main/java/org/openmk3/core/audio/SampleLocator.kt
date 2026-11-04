package org.openmk3.core.audio

import android.os.Environment
import org.openmk3.core.AppLog
import java.io.File

/**
 * Finds samples that moved. A project, group, sound or zone remembers each sample's full path;
 * when that file is gone, the phone's Download and Music folders are searched (once, then
 * remembered) for a file with the same name, and that one is used. The new path is saved with
 * the next save.
 */
object SampleLocator {
    private var index: Map<String, List<File>>? = null
    /** Samples found somewhere else / not found since the last [takeReport]. */
    @Volatile var relocated = 0
    @Volatile var missing = 0

    private fun roots(): List<File> = listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_MUSIC)
        .map { Environment.getExternalStoragePublicDirectory(it) }.filter { it.isDirectory }

    @Synchronized private fun index(): Map<String, List<File>> {
        index?.let { return it }
        val m = HashMap<String, MutableList<File>>()
        var n = 0
        fun walk(d: File, depth: Int) {
            if (depth > 8 || n > 50000) return
            for (f in d.listFiles().orEmpty()) {
                if (f.isDirectory) { if (!f.name.startsWith(".")) walk(f, depth + 1) }
                else if (f.extension.equals("wav", true)) { m.getOrPut(f.name.lowercase()) { mutableListOf() }.add(f); n++ }
            }
        }
        roots().forEach { walk(it, 0) }
        AppLog.i("Sample locator: indexed $n WAV files")
        return m.also { index = it }
    }

    /** Forget the index (files were added or moved). */
    @Synchronized fun reset() { index = null }

    /** The file at [path], or a file with the same name elsewhere (the one whose folders match best). */
    fun find(path: String): File? {
        val f = File(path)
        if (f.isFile) return f
        val hits = index()[f.name.lowercase()] ?: return null
        val parts = f.parentFile?.path?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        return hits.maxByOrNull { h -> h.parentFile?.path?.split('/')?.count { it in parts } ?: 0 }
    }

    /** Decodes the sample at [path], looking for it elsewhere when it moved; throws when not found. */
    fun decode(path: String, maxSeconds: Int = 30): Sample {
        val f = find(path) ?: run { missing++; throw java.io.FileNotFoundException(path) }
        if (f.path != File(path).path) { relocated++; AppLog.i("Sample moved: $path → ${f.path}") }
        return WavDecoder.decode(f, maxSeconds)
    }

    /** "3 samples found in new places · 1 missing", or null when there is nothing to tell; resets the counts. */
    fun takeReport(): String? {
        val r = relocated; val m = missing
        relocated = 0; missing = 0
        if (r == 0 && m == 0) return null
        return listOfNotNull(if (r > 0) "$r sample${if (r > 1) "s" else ""} found in new places" else null,
            if (m > 0) "$m missing" else null).joinToString(" · ")
    }
}

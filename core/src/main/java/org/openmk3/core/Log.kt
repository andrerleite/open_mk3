package org.openmk3.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-wide log: keeps the last [MAX_LINES] lines in memory for the UI and appends
 * every line to a file under the app's external files dir.
 */
object AppLog {
    private const val TAG = "OpenMK3"
    private const val MAX_LINES = 3000

    private val lines = ArrayDeque<String>()
    private var writer: BufferedWriter? = null
    var file: File? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val notifyPending = AtomicBoolean(false)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        synchronized(this) {
            if (writer != null) return
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val f = File(dir, "openmk3-$stamp.log")
            file = f
            writer = BufferedWriter(FileWriter(f, true))
        }
        i("=== ${Edition.title} ${BuildInfo.describe()} ===")
        i("log file: ${file?.absolutePath}")
    }

    fun i(msg: String) {
        val line = synchronized(timeFmt) { timeFmt.format(Date()) } + "  " + msg
        synchronized(this) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            try {
                writer?.write(line)
                writer?.newLine()
            } catch (_: Exception) {
            }
        }
        Log.i(TAG, msg)
        if (notifyPending.compareAndSet(false, true)) {
            main.postDelayed({
                notifyPending.set(false)
                flush()
                listeners.forEach { it() }
            }, 150)
        }
    }

    fun flush() {
        synchronized(this) {
            try {
                writer?.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun tail(n: Int): String = synchronized(this) {
        val start = (lines.size - n).coerceAtLeast(0)
        val sb = StringBuilder()
        var idx = 0
        for (l in lines) {
            if (idx++ >= start) sb.append(l).append('\n')
        }
        sb.toString()
    }

    fun clearView() = synchronized(this) { lines.clear() }.also { listeners.forEach { it() } }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** Copies the full log file into the public Downloads folder. Returns its Uri. */
    fun exportToDownloads(ctx: Context): Uri? {
        flush()
        val src = file ?: return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, src.name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Edition.storageFolder)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
        return uri
    }
}

object BuildInfo {
    fun describe(): String =
        "app ${Edition.version} | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
            "(${android.os.Build.DEVICE}) Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}"
}


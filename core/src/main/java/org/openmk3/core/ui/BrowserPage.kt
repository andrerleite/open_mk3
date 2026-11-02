package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Environment
import org.openmk3.core.AppLog
import org.openmk3.core.audio.KitStore
import org.openmk3.core.audio.Sample
import org.openmk3.core.audio.WavDecoder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Sample browser on the MK3 screens, rooted at Download/<Edition.storageFolder>.
 *
 *  ENCODER turn = move (SHIFT = page) · landing on a WAV previews it
 *  ENCODER push / RIGHT = open folder, or load the WAV onto the target pad
 *  ENCODER LEFT / PAGE LEFT = up a folder · PADS = choose the target pad (and play it)
 *  PLAY / STOP keep working · BROWSER or SETTINGS = back
 */
class BrowserPage(ui: Ui) : Page(ui) {

    override val playsPads = true

    private sealed class Entry(val label: String) {
        class BuiltIn : Entry("‹ built-in sound ›")
        class Up : Entry("‹ back ›")
        class Folder(val file: File) : Entry(file.name + "/")
        class Wav(val file: File) : Entry(file.nameWithoutExtension)
    }

    companion object {
        val ROOT: File
            get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), org.openmk3.core.Edition.storageFolder)
        private var lastDir: File? = null   // reopen where the player left off
        private var lastSel = 0
        private const val ROWS = 10
    }

    private var dir = lastDir?.takeIf { it.isDirectory } ?: ROOT
    private var entries: List<Entry> = emptyList()
    private var sel = 0
    private var top = 0
    private var message = ""
    private var info = ""
    private var problem: String? = null

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sample-loader") }
    private var previewJob: Future<*>? = null
    private var previewed: Sample? = null
    private var previewToken = 0

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val path = Theme.text(13f, Theme.DIM)
    private val row = Theme.text(16f)
    private val rowDir = Theme.text(16f, Theme.ACCENT)
    private val rowDim = Theme.text(16f, Theme.DIM)
    private val selFill = Theme.fill(android.graphics.Color.rgb(60, 60, 80))
    private val big = Theme.text(22f, Theme.FG, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val value = Theme.text(16f)
    private val ok = Theme.text(16f, Theme.OK)
    private val warn = Theme.text(15f, Theme.WARN)

    override fun onEnter() {
        list(dir, lastSel)
        ui.buttonLed("BROWSER", 0x7F)
    }

    override fun onExit() {
        lastDir = dir
        lastSel = sel
        worker.shutdownNow()
    }

    private fun list(d: File, select: Int = 0) {
        dir = d
        problem = null
        if (!ROOT.exists()) ROOT.mkdirs()
        val files = d.listFiles()
        if (files == null) {
            problem = "Can't read this folder. Allow 'Music and audio' for this app in Android settings."
        }
        val folders = files.orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
        val wavs = files.orEmpty().filter { it.isFile && it.extension.equals("wav", true) }
            .sortedBy { it.name.lowercase() }
        entries = buildList {
            add(Entry.BuiltIn())
            if (d.canonicalPath != ROOT.canonicalPath) add(Entry.Up())
            folders.forEach { add(Entry.Folder(it)) }
            wavs.forEach { add(Entry.Wav(it)) }
        }
        if (files != null && folders.isEmpty() && wavs.isEmpty()) {
            problem = "No WAV files or folders here."
        }
        sel = select.coerceIn(0, entries.size - 1)
        top = (sel - ROWS / 2).coerceIn(0, maxOf(0, entries.size - ROWS))
        info = ""
        previewed = null
        ui.invalidate()
    }

    private fun move(d: Int) {
        if (entries.isEmpty()) return
        val n = (sel + d).coerceIn(0, entries.size - 1)
        if (n == sel) return
        sel = n
        if (sel < top) top = sel
        if (sel >= top + ROWS) top = sel - ROWS + 1
        info = ""
        schedulePreview()
        ui.invalidate()
    }

    /** Decode in the background (debounced) and audition the WAV under the cursor. */
    private fun schedulePreview() {
        val e = entries.getOrNull(sel) as? Entry.Wav ?: return
        val token = ++previewToken
        previewJob?.cancel(false)
        previewJob = worker.submit {
            Thread.sleep(120) // let fast scrolling settle
            if (token != previewToken) return@submit
            try {
                val s = WavDecoder.decode(e.file)
                ui.post {
                    if (token != previewToken) return@post
                    previewed = s
                    info = s.info
                    ui.engine.preview(s)
                    ui.invalidate()
                }
            } catch (ex: Exception) {
                ui.post { if (token == previewToken) { info = "can't read: ${ex.message}"; ui.invalidate() } }
            }
        }
    }

    private fun activate() {
        when (val e = entries.getOrNull(sel)) {
            is Entry.BuiltIn -> {
                val slot = ui.selectedSound
                ui.engine.resetSlot(slot)
                ui.engine.params.resetRange(slot)
                ui.engine.params.save(ui.ctx)
                KitStore.save(ui.ctx, ui.engine.kit)
                message = "Pad ${slot + 1}: built-in ${ui.engine.kit[slot].name}"
                AppLog.i("Kit: $message")
            }
            is Entry.Up -> up()
            is Entry.Folder -> list(e.file)
            is Entry.Wav -> load(e)
            null -> {}
        }
        ui.invalidate()
    }

    private fun load(e: Entry.Wav) {
        val slot = ui.selectedSound
        val ready = previewed?.takeIf { it.path == e.file.absolutePath }
        if (ready != null) {
            assign(slot, ready)
            return
        }
        message = "Loading…"
        worker.submit {
            try {
                val s = WavDecoder.decode(e.file)
                ui.post { assign(slot, s) }
            } catch (ex: Exception) {
                ui.post { message = "Can't load: ${ex.message}"; ui.invalidate() }
            }
        }
    }

    private fun assign(slot: Int, s: Sample) {
        ui.engine.setSample(slot, s)
        ui.engine.params.resetRange(slot)
        ui.engine.params.save(ui.ctx)
        KitStore.save(ui.ctx, ui.engine.kit)
        message = "Pad ${slot + 1} ← ${s.name}"
        info = s.info
        AppLog.i("Kit: pad ${slot + 1} = ${s.path} (${s.info})")
        ui.engine.trigger(slot, 0.8f)
        ui.invalidate()
    }

    private fun up() {
        if (dir.canonicalPath == ROOT.canonicalPath) return
        val child = dir
        val parent = dir.parentFile ?: return
        list(parent)
        val i = entries.indexOfFirst { it is Entry.Folder && it.file == child }
        if (i >= 0) { sel = i; top = (sel - ROWS / 2).coerceIn(0, maxOf(0, entries.size - ROWS)) }
    }

    // =====================================================================================

    override fun onEncoder(delta: Int) {
        // SHIFT scrolls a page at a time (shift state lives on the home page; read the button map)
        move(if (shiftHeld) delta * ROWS else delta)
    }

    private var shiftHeld = false

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (name == "SHIFT") { shiftHeld = down; return }
        if (!down) return
        val e = ui.engine
        when (name) {
            "ENCODER PUSH", "ENCODER RIGHT", "PAGE RIGHT" -> activate()
            "ENCODER LEFT", "PAGE LEFT" -> up()
            "ENCODER UP" -> move(-1)
            "ENCODER DOWN" -> move(1)
            "PLAY" -> if (e.playing) e.transportStop() else e.transportPlay()
            "STOP" -> e.transportStop()
            "BROWSER", "SETTINGS" -> ui.showHome()
        }
        ui.invalidate()
    }

    override fun lights(l: Lights) {
        l.set("BROWSER", true)
        l.set("PLAY", ui.engine.playing)
        l.set("SHIFT", shiftHeld)
        l.set("STOP", "PAGE LEFT", "PAGE RIGHT", "SETTINGS")
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        ui.selectedSound = slot
        message = ""
    }

    override fun onKnob(knob: Int, delta: Int) {
        if (knob == 0) ui.engine.masterGain = (ui.engine.masterGain + delta / 300f).coerceIn(0f, 1.5f)
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        // left: file list
        l.drawColor(Theme.BG)
        l.drawText("BROWSER", 12f, 24f, title)
        val rel = dir.absolutePath.removePrefix(ROOT.parentFile?.absolutePath ?: "").trimStart('/')
        l.drawText(ellipsize(rel, 52), 110f, 23f, path)
        val y0 = 36f
        val rh = 23f
        for (i in 0 until ROWS) {
            val idx = top + i
            val e = entries.getOrNull(idx) ?: break
            val y = y0 + i * rh
            if (idx == sel) l.drawRect(0f, y, 480f, y + rh, selFill)
            val p = when (e) {
                is Entry.Folder, is Entry.Up -> rowDir
                is Entry.BuiltIn -> rowDim
                else -> row
            }
            l.drawText(ellipsize(e.label, 48), 12f, y + 17f, p)
        }
        if (entries.size > ROWS) {
            val h = 230f * ROWS / entries.size
            val ty = y0 + 230f * top / entries.size
            l.drawRect(474f, ty, 478f, ty + h, Theme.fill(Theme.DIM))
        }
        problem?.let { l.drawText(ellipsize(it, 56), 12f, 266f, warn) }

        // right: target pad + selection info
        r.drawColor(Theme.BG)
        val slot = ui.selectedSound
        r.drawText("TARGET PAD", 16f, 28f, label)
        r.drawRoundRect(RectF(16f, 38f, 44f, 66f), 5f, 5f, Theme.fill(Theme.ACCENT))
        r.drawText("${slot + 1}  ${ui.engine.kit[slot].name}", 56f, 60f, big)
        r.drawText(ellipsize(ui.engine.kit[slot].info, 50), 16f, 86f, label)

        r.drawText("SELECTED", 16f, 120f, label)
        val e = entries.getOrNull(sel)
        r.drawText(ellipsize(e?.label ?: "", 34), 16f, 144f, value)
        if (info.isNotEmpty()) r.drawText(ellipsize(info, 50), 16f, 166f, label)
        if (message.isNotEmpty()) r.drawText(ellipsize(message, 40), 16f, 194f, ok)

        r.drawText("ENC turn: browse · push: open / load", 16f, 226f, label)
        r.drawText("◀: back · PADS: target · BROWSER: done", 16f, 246f, label)
        r.drawText("PLAY: hear it in the beat", 16f, 266f, label)
    }

    private fun ellipsize(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}

package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Environment
import org.openmk3.core.AppLog
import org.openmk3.core.audio.AudioEngine
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
 *  D7 = add the WAV as a zone of the target pad · D8 = add every WAV of this folder as zones
 *  (root keys from the file names; then auto-mapped) · SAMPLING (SHIFT) = the zones
 *  D5 / D6 = load the previous / next WAV straight onto the target pad (hear it in context while
 *  the pattern plays) · D4 = PREHEAR on / off (auditioning while scrolling) · D1 = QUICK BROWSE:
 *  the folder of the target pad's sample, with that file selected
 *  PLAY / STOP keep working · BROWSER or SETTINGS = back
 */
class BrowserPage(ui: Ui) : Page(ui) {

    override val handlesTransport = true

    override val playsPads = true

    private sealed class Entry(val label: String) {
        class BuiltIn : Entry("‹ built-in sound / clear ›")
        class Up : Entry("‹ back ›")
        class Folder(val file: File) : Entry(file.name + "/")
        class Wav(val file: File) : Entry(file.nameWithoutExtension)
        class SoundFile(val file: File) : Entry("♪ " + file.nameWithoutExtension)
        class GroupFile(val file: File) : Entry("▦ " + file.nameWithoutExtension)
        class FxFile(val file: File) : Entry("⚙ " + file.nameWithoutExtension)
    }

    companion object {
        val ROOT: File
            get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), org.openmk3.core.Edition.storageFolder)
        private var lastDir: File? = null   // reopen where the player left off
        private var lastSel = 0
        private const val ROWS = 10
        /** PREHEAR: landing on a WAV auditions it. */
        var prehear = true
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
        val saved = files.orEmpty().filter { it.isFile && (it.extension == org.openmk3.core.audio.GroupFile.EXT || it.extension == org.openmk3.core.audio.GroupFile.SOUND_EXT || it.extension == org.openmk3.core.audio.FxPreset.EXT) }
            .sortedBy { it.name.lowercase() }
        entries = buildList {
            add(Entry.BuiltIn())
            if (d.canonicalPath != ROOT.canonicalPath) add(Entry.Up())
            folders.forEach { add(Entry.Folder(it)) }
            saved.forEach { add(when (it.extension) { org.openmk3.core.audio.GroupFile.EXT -> Entry.GroupFile(it); org.openmk3.core.audio.FxPreset.EXT -> Entry.FxFile(it); else -> Entry.SoundFile(it) }) }
            wavs.forEach { add(Entry.Wav(it)) }
        }
        if (files != null && folders.isEmpty() && wavs.isEmpty() && saved.isEmpty()) {
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
                    if (prehear) ui.engine.preview(s)
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
                val slot = ui.selectedIndex
                ui.engine.resetSlot(slot)
                ui.engine.params.resetRange(slot)
                ui.engine.params.save(ui.ctx)
                KitStore.save(ui.ctx, ui.engine.kit)
                message = "${ui.padLabel()}: ${ui.engine.kit[slot].name.ifEmpty { "empty" }}"
                AppLog.i("Kit: $message")
            }
            is Entry.Up -> up()
            is Entry.Folder -> list(e.file)
            is Entry.Wav -> load(e)
            is Entry.SoundFile -> loadSaved(e.file, group = false)
            is Entry.GroupFile -> loadSaved(e.file, group = true)
            is Entry.FxFile -> {
                // into the effect slot last open on PLUG-IN (else the focused sound's first effect)
                val (c, k) = SoundPageNav.fxTarget ?: (ui.selectedIndex to 0)
                message = if (org.openmk3.core.audio.FxPreset.load(ui.engine, c, k, e.file)) "${e.file.nameWithoutExtension} → effect slot ${k + 1}" else "Can't load ${e.file.name}"
            }
            null -> {}
        }
        ui.invalidate()
    }

    /** A saved sound onto the focused pad, or a saved group into the focused group (replacing it; UNDO only brings back patterns). */
    private fun loadSaved(f: File, group: Boolean) {
        val slot = ui.selectedIndex
        val g = ui.group
        message = "Loading ${f.nameWithoutExtension}…"
        worker.submit {
            val e = ui.engine
            val colour: Int? = if (group) org.openmk3.core.audio.GroupFile.load(e, g, f) { s, smp -> e.setSample(s, smp) }
                else if (org.openmk3.core.audio.GroupFile.loadSoundFile(e, slot, f) { s, smp -> e.setSample(s, smp) }) 0 else null
            ui.post {
                if (colour == null) message = "Can't load ${f.name}"
                else {
                    if (group && colour > 0) { Colours.group[g] = colour; Colours.save(ui.ctx) }
                    KitStore.save(ui.ctx, e.kit); e.params.save(ui.ctx); e.fx.save(ui.ctx); e.poly.save(ui.ctx); e.zones.save(ui.ctx)
                    if (group) e.pattern.save(ui.ctx)
                    org.openmk3.core.audio.Names.save(ui.ctx)
                    ui.checkpoint()
                    message = if (group) "Group ${'A' + g} ← ${f.nameWithoutExtension}" else "${ui.padLabel()} ← ${f.nameWithoutExtension}"
                    if (!group) e.trigger(slot, 0.8f)
                }
                ui.invalidate()
            }
        }
    }

    private fun load(e: Entry.Wav) {
        val slot = ui.selectedIndex
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
        message = "${org.openmk3.core.audio.soundLabel(slot)} ← ${s.name}"
        info = s.info
        AppLog.i("Kit: ${org.openmk3.core.audio.soundLabel(slot)} = ${s.path} (${s.info})")
        if (!ui.engine.playing) ui.engine.trigger(slot, 0.8f)  // while playing: heard in the pattern
        ui.invalidate()
    }

    /** D5 / D6: the previous / next WAV in this folder goes straight onto the target pad. */
    private fun stepLoad(d: Int) {
        val wavs = entries.indices.filter { entries[it] is Entry.Wav }
        if (wavs.isEmpty()) { message = "No WAV files here"; return }
        val next = if (entries.getOrNull(sel) !is Entry.Wav) (if (d > 0) wavs.first() else wavs.last())
            else wavs.getOrNull(wavs.indexOf(sel) + d) ?: return
        sel = next
        if (sel < top) top = sel
        if (sel >= top + ROWS) top = sel - ROWS + 1
        load(entries[sel] as Entry.Wav)
    }

    /** D1 QUICK BROWSE: back to where the target pad's sample came from. */
    private fun quickBrowse() {
        val path = ui.engine.kit[ui.selectedIndex].path ?: run { message = "${ui.padLabel()} has no sample file"; return }
        val f = File(path)
        val parent = f.parentFile?.takeIf { it.isDirectory } ?: run { message = "Folder not found"; return }
        list(parent)
        val i = entries.indexOfFirst { it is Entry.Wav && it.file.name == f.name }
        if (i >= 0) { sel = i; top = (sel - ROWS / 2).coerceIn(0, maxOf(0, entries.size - ROWS)) }
        message = "Quick browse: ${f.nameWithoutExtension}"
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
            "STOP" -> e.stopPressed()
            "BROWSER", "SETTINGS" -> ui.showHome()
            "D5" -> stepLoad(-1)
            "D6" -> stepLoad(1)
            "D4" -> { prehear = !prehear; message = "Prehear ${if (prehear) "on" else "off"}"; if (!prehear) ui.engine.killAll(AudioEngine.PREVIEW_SLOT) }
            "D1" -> quickBrowse()
            "D7" -> (entries.getOrNull(sel) as? Entry.Wav)?.let { addZones(listOf(it.file)) }
            "D8" -> addZones(entries.filterIsInstance<Entry.Wav>().map { it.file })
        }
        ui.invalidate()
    }

    override fun lights(l: Lights) {
        l.set("BROWSER", true)
        l.set("PLAY", ui.engine.playing)
        l.set("SHIFT", shiftHeld)
        l.set("STOP", "PAGE LEFT", "PAGE RIGHT", "SETTINGS")
        l.set("D1", ui.engine.kit[ui.selectedIndex].path != null)
        l.set("D4", prehear)
        l.set("D5", "D6")
        l.set("D7", entries.getOrNull(sel) is Entry.Wav)
        l.set("D8", entries.any { it is Entry.Wav })
    }

    /** Adds WAVs as zones of the target pad (decoded in the background), then auto-maps. */
    private fun addZones(files: List<File>) {
        if (files.isEmpty()) return
        val slot = ui.selectedIndex
        message = "Adding ${files.size} zone(s)…"
        worker.submit {
            val samples = files.take(org.openmk3.core.audio.Zones.MAX).mapNotNull { f ->
                try { WavDecoder.decode(f) } catch (ex: Exception) { AppLog.i("Zone: can't read ${f.name}"); null }
            }
            ui.post {
                val z = ui.engine.zones
                var added = 0
                for (s in samples) if (z.add(slot, s) >= 0) added++
                if (files.size > 1) z.autoMap(slot)
                z.save(ui.ctx)
                message = "${org.openmk3.core.audio.soundLabel(slot)}: $added zone(s) (${z.of(slot).size} in all)"
                AppLog.i("Zones: $message")
                ui.invalidate()
            }
        }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (ui.padMode != Ui.PadMode.PAD) return  // KEYBOARD / CHORDS / 16 VEL: pads only play notes
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
        val slot = ui.selectedIndex
        r.drawText("TARGET PAD", 16f, 28f, label)
        r.drawRoundRect(RectF(16f, 38f, 44f, 66f), 5f, 5f, Theme.fill(Theme.ACCENT))
        r.drawText("${ui.padLabel()}  ${ui.soundName(slot).ifEmpty { "(empty)" }}", 56f, 60f, big)
        r.drawText(ellipsize(ui.engine.kit[slot].info, 50), 16f, 86f, label)

        r.drawText("SELECTED", 16f, 120f, label)
        val e = entries.getOrNull(sel)
        r.drawText(ellipsize(e?.label ?: "", 34), 16f, 144f, value)
        if (info.isNotEmpty()) r.drawText(ellipsize(info, 50), 16f, 166f, label)
        if (message.isNotEmpty()) r.drawText(ellipsize(message, 40), 16f, 194f, ok)

        r.drawText("ENC turn: browse · push: open / load", 16f, 226f, label)
        r.drawText("◀: back · PADS: target · BROWSER: done", 16f, 246f, label)
        r.drawText("D5/D6 load prev/next · D7/D8 zone(s) · D1 quick", 16f, 266f, label)
        r.drawText("PREHEAR ${if (prehear) "ON" else "OFF"} (D4)", 330f, 120f, label)
        val zn = ui.engine.zones.of(slot).size
        if (zn > 0) r.drawText("$zn zone(s)", 360f, 28f, label)
    }

    private fun ellipsize(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}

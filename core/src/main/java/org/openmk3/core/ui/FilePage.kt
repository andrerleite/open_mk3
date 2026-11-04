package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import org.openmk3.core.AppLog
import org.openmk3.core.audio.KitStore
import org.openmk3.core.audio.Project
import java.io.File
import java.util.concurrent.Executors

/**
 * FILE: save, save as (with a suggested name you can edit), rename, new project, and the saved
 * projects (newest first).
 *  Encoder turn / ◀ ▶: move · encoder push or D8: do it (loading and NEW ask for a second push)
 *  Name editor: encoder turn (or K2) = the letter at the cursor · ◀ ▶ / tilt (or K1) = cursor ·
 *  D5 suggest another name · D6 delete · D7 cancel · D8 OK · SHIFT + encoder: upper / lower case
 *  SHIFT + FILE (anywhere): quick save · FILE: back
 */
class FilePage(ui: Ui) : Page(ui) {

    private sealed class Item(val label: String) {
        class Save(name: String) : Item("SAVE  \"$name\"")
        object SaveNew : Item("SAVE AS…  (name it)")
        class Rename(name: String) : Item("RENAME  \"$name\"")
        object New : Item("NEW EMPTY PROJECT")
        object Export : Item("EXPORT AUDIO…  (loop or song as WAV)")
        class SaveGroup(letter: Char) : Item("SAVE GROUP $letter…  (kit + patterns)")
        class LoadGroup(val file: File, letter: Char) : Item("GROUP → $letter:  ${file.nameWithoutExtension}")
        class SaveSound(pad: String) : Item("SAVE SOUND $pad…  (sample + settings)")
        class RenameSound(pad: String, name: String) : Item("RENAME SOUND $pad  \"$name\"")
        class RenameGroup(name: String) : Item("RENAME GROUP  \"$name\"")
        class LoadSound(val file: File, pad: String) : Item("SOUND → $pad:  ${file.nameWithoutExtension}")
        class Load(val file: File) : Item(file.nameWithoutExtension)
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "project-io") }
    private var items: List<Item> = emptyList()
    private var sel = 0
    private var top = 0
    private var armed = -1
    private var message = ""
    private var busy = false

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val row = Theme.text(17f)
    private val rowAct = Theme.text(17f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val ok = Theme.text(16f, Theme.OK)
    private val warn = Theme.text(16f, Theme.WARN)
    private val selFill = Theme.fill(android.graphics.Color.rgb(60, 60, 80))
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)

    override fun onEnter() = refresh()

    override fun onExit() { worker.shutdown() }

    private fun refresh() {
        val cur = Project.currentName(ui.ctx)
        items = buildList {
            if (cur != null) add(Item.Save(cur))
            add(Item.SaveNew)
            if (cur != null) add(Item.Rename(cur))
            add(Item.Export)
            add(Item.New)
            add(Item.SaveGroup(ui.groupLetter))
            add(Item.SaveSound(ui.padLabel()))
            add(Item.RenameSound(ui.padLabel(), ui.soundName(ui.selectedIndex)))
            add(Item.RenameGroup(ui.groupName(ui.group)))
            Project.list().forEach { add(Item.Load(it)) }
            org.openmk3.core.audio.GroupFile.list().forEach { add(Item.LoadGroup(it, ui.groupLetter)) }
            org.openmk3.core.audio.GroupFile.soundList().forEach { add(Item.LoadSound(it, ui.padLabel())) }
        }
        sel = sel.coerceIn(0, items.size - 1)
        ui.invalidate()
    }

    // ---- audio export: the loop or the song, played once from the top and recorded from the master ----
    private var exporting = false
    private var exportState = 0       // 0 setup, 1 recording, 2 saving
    private var exportSong = false
    private var exportLoops = 1
    private var exportFrames = 0
    private val LOOPS = listOf(1, 2, 4, 8)

    /** Steps of one pass: the song (song mode) or the longest pattern playing. */
    private fun exportSteps(): Int {
        val p = ui.engine.pattern
        if (exportSong && p.songMode && p.song.isNotEmpty()) return p.song.sumOf { p.sceneLength(it) }
        var len = 16
        for (g in 0 until 8) if (!p.groupEmpty(g)) len = maxOf(len, p.length(g))
        return len
    }

    private fun exportSeconds(): Double = exportSteps() * (if (exportSong) 1 else exportLoops) * 60.0 / ui.engine.bpm / 4.0

    private fun startExport() {
        val secs = exportSeconds()
        if (secs > 180) { message = "Can't export more than 3 minutes at once"; return }
        exportFrames = (secs * org.openmk3.core.audio.AudioEngine.SR).toInt()
        val n = org.openmk3.core.audio.NativeEngine
        n.nativeRecArmExport(exportFrames)
        val e = ui.engine
        if (e.pattern.songMode) e.pattern.songRestart()
        if (e.playing) e.transportRestart() else e.transportPlay()
        exportState = 1
        message = ""
        AppLog.i("Export: ${if (exportSong) "song" else "loop ×$exportLoops"}, ${"%.1f".format(secs)} s")
    }

    override fun tick(now: Long): Boolean {
        if (!exporting || exportState != 1) return false
        val n = org.openmk3.core.audio.NativeEngine
        if (n.nativeRecState() == 0) {  // done
            exportState = 2
            val data = n.nativeRecTake()
            ui.engine.transportStop()
            val base = (Project.currentName(ui.ctx) ?: "Open MK3") + if (exportSong) " song" else " loop"
            worker.execute {
                val msg = try {
                    val dir = java.io.File(Project.dir.parentFile, "Exports").apply { mkdirs() }
                    var i = 1
                    var f: java.io.File
                    do { f = java.io.File(dir, "%s %03d.wav".format(base, i++)) } while (f.exists())
                    org.openmk3.core.audio.WavWriter.write(f, data, 2)
                    AppLog.i("Export: saved ${f.absolutePath}")
                    "Saved Exports/${f.name}"
                } catch (e: Exception) { "Can't save: ${e.message}" }
                ui.post { exportState = 0; exporting = false; message = msg; refresh() }
            }
        }
        return true
    }

    private var expAcc = IntArray(2)
    private fun exportKnob(knob: Int, delta: Int) {
        if (exportState != 0 || knob > 1) return
        expAcc[knob] += delta
        if (kotlin.math.abs(expAcc[knob]) < 25) return
        val d = if (expAcc[knob] > 0) 1 else -1
        expAcc[knob] = 0
        if (knob == 0) exportSong = d > 0
        else exportLoops = LOOPS[(LOOPS.indexOf(exportLoops) + d).coerceIn(0, LOOPS.size - 1)]
        ui.invalidate()
    }

    private fun drawExport(l: Canvas, r: Canvas) {
        l.drawText("EXPORT AUDIO", 16f, 30f, title)
        val p = ui.engine.pattern
        val songOk = p.songMode && p.song.isNotEmpty()
        l.drawText(if (exportSong) (if (songOk) "The song, from section 1" else "Song mode is off: the loop instead") else "The loop playing, $exportLoops time${if (exportLoops > 1) "s" else ""}", 16f, 70f, row)
        l.drawText("%.1f s · 48 kHz · 24-bit · stereo".format(exportSeconds()), 16f, 98f, label)
        when (exportState) {
            1 -> {
                val done = org.openmk3.core.audio.NativeEngine.nativeRecFrames().toFloat() / exportFrames.coerceAtLeast(1)
                l.drawText("● Recording the master… ${(done * 100).toInt()}%", 16f, 140f, warn)
                l.drawRect(16f, 156f, 464f, 166f, selFill)
                l.drawRect(16f, 156f, 16f + 448f * done.coerceIn(0f, 1f), 166f, Theme.fill(Theme.OK))
            }
            2 -> l.drawText("Saving…", 16f, 140f, ok)
            else -> {
                l.drawText("It plays from the top and is recorded as you hear it", 16f, 140f, label)
                l.drawText("(the master, after its effects)", 16f, 160f, label)
            }
        }
        val kn = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
        val kv = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)
        l.drawText("WHAT", 60f, 238f, kn); l.drawText(if (exportSong) "SONG" else "LOOP", 60f, 260f, kv)
        l.drawText("LOOPS", 180f, 238f, kn); l.drawText(if (exportSong) "—" else "$exportLoops", 180f, 260f, kv)
        r.drawText("CANCEL", 300f, 24f, dLabel)
        r.drawText(if (exportState == 0) "START" else "…", 420f, 24f, dLabel)
        r.drawText("Saved in Download/…/Exports", 16f, 70f, label)
        if (message.isNotEmpty()) r.drawText(message.take(44), 16f, 220f, if (message.startsWith("Can't")) warn else ok)
    }

    // ---- the name editor ----
    private var editing = false
    private var renaming = false
    private var savingGroup = false
    private var savingSound = false
    /** 1: naming the focused sound, 2: the focused group. */
    private var nameTarget = 0
    private val name = StringBuilder()
    private var cursor = 0
    private var shift = false
    private var knobAcc = IntArray(2)
    private val CHARS = " ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.&'()#!"
    /** The character grid shown while SHIFT is held (rows of 13). */
    private val GRID = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 -_.&'()#!+=@"
    private val GCOLS = 13
    private var gridSel = 0
    private var gridTyped = false

    private fun startEdit(initial: String, rename: Boolean) {
        editing = true; renaming = rename
        name.setLength(0); name.append(initial)
        cursor = name.length
        message = ""
    }

    /** Changes the letter at the cursor (past the end: adds one). */
    private fun turnChar(d: Int) {
        if (cursor >= name.length) name.append(if (d > 0) 'A' else ' ').also { if (d < 0) {} }
        else {
            val c = name[cursor]
            if (shift && c.isLetter()) { name.setCharAt(cursor, if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar()); return }
            val i = CHARS.indexOf(c).coerceAtLeast(0)
            name.setCharAt(cursor, CHARS[Math.floorMod(i + d, CHARS.length)])
        }
    }

    private fun caseAt(upper: Boolean) {
        if (cursor < name.length && name[cursor].isLetter()) name.setCharAt(cursor, if (upper) name[cursor].uppercaseChar() else name[cursor].lowercaseChar())
    }

    private fun backspace() { if (cursor > 0) { name.deleteCharAt(cursor - 1); cursor-- } }

    private fun moveCursor(d: Int) { cursor = (cursor + d).coerceIn(0, minOf(name.length, 39)) }

    private fun finishEdit() {
        val n = Project.clean(name.toString())
        if (n.isEmpty()) { message = "Can't use an empty name"; return }
        if (nameTarget != 0) {
            val t = nameTarget
            nameTarget = 0
            editing = false
            if (t == 1) org.openmk3.core.audio.Names.sound[ui.selectedIndex] = n else org.openmk3.core.audio.Names.group[ui.group] = n
            org.openmk3.core.audio.Names.save(ui.ctx)
            message = if (t == 1) "${ui.padLabel()} is now \"$n\"" else "Group ${ui.groupLetter} is now \"$n\""
            refresh()
            return
        }
        if (savingSound) {
            savingSound = false
            editing = false
            message = try { org.openmk3.core.audio.GroupFile.saveSound(ui.engine, ui.selectedIndex, n); "Sound ${ui.padLabel()} saved as \"$n\"" } catch (e: Exception) { "Can't save: ${e.message}" }
            refresh()
            return
        }
        if (savingGroup) {
            savingGroup = false
            editing = false
            message = try {
                org.openmk3.core.audio.GroupFile.save(ui.engine, ui.group, Colours.group[ui.group], n)
                "Group ${ui.groupLetter} saved as \"$n\""
            } catch (e: Exception) { "Can't save: ${e.message}" }
            refresh()
            return
        }
        if (renaming) {
            val cur = Project.currentName(ui.ctx) ?: return
            message = if (Project.rename(ui.ctx, cur, n)) "Renamed to \"$n\"" else "Can't rename: \"$n\" exists"
            editing = false
            refresh()
        } else {
            if (java.io.File(Project.dir, "$n.${Project.EXT}").exists()) { message = "\"$n\" exists: change the name"; return }
            editing = false
            save(n)
        }
    }

    override fun onKnob(knob: Int, delta: Int) {
        if (exporting) { exportKnob(knob, delta); return }
        if (!editing || knob > 1) return
        knobAcc[knob] += delta
        if (kotlin.math.abs(knobAcc[knob]) < 12) return
        val d = if (knobAcc[knob] > 0) 1 else -1
        knobAcc[knob] = 0
        if (knob == 0) moveCursor(d) else turnChar(d)
        ui.invalidate()
    }

    override fun lights(l: Lights) {
        l.set("FILE", true)
        if (exporting) { l.set("D7"); l.set("D8", exportState == 1); return }
        if (editing) { l.set("D5", "D6", "D7"); l.set("D8", true); l.set("SHIFT", shift); l.set("PAGE LEFT", "PAGE RIGHT"); return }
        l.set("D8", armed >= 0)
        l.set("PAGE LEFT", "PAGE RIGHT")
    }

    private fun move(d: Int) {
        sel = (sel + d).coerceIn(0, items.size - 1)
        if (sel < top) top = sel
        if (sel >= top + 8) top = sel - 7
        armed = -1
        ui.invalidate()
    }

    override fun onEncoder(delta: Int) {
        if (editing && shift) { gridSel = Math.floorMod(gridSel + delta, GRID.length); ui.invalidate(); return }
        if (editing) { turnChar(delta); ui.invalidate() } else move(delta)
    }

    /** Writes the grid's character at the cursor (past the end: adds it). */
    private fun putGridChar(advance: Boolean) {
        val c = GRID[gridSel]
        if (cursor >= name.length) name.append(c) else name.setCharAt(cursor, c)
        if (advance) moveCursor(1)
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (name == "SHIFT") {
            if (editing) {
                if (down) {
                    // SHIFT held: the character grid, starting on the letter under the cursor
                    val cur = if (cursor < this.name.length) this.name[cursor] else 'A'
                    gridSel = GRID.indexOf(cur).let { if (it < 0) 0 else it }
                    gridTyped = false
                } else if (!gridTyped) putGridChar(false)  // released: the chosen character goes in
                ui.invalidate()
            }
            shift = down
            return
        }
        if (!down) return
        if (exporting) {
            when (name) {
                "D7" -> if (exportState == 1) {
                    org.openmk3.core.audio.NativeEngine.nativeRecStop(); ui.engine.transportStop(); exportState = 0; message = "Export cancelled"
                } else if (exportState == 0) exporting = false
                "D8", "ENCODER PUSH" -> if (exportState == 0) startExport()
                "FILE" -> if (exportState == 0) ui.showHome()
            }
            ui.invalidate()
            return
        }
        if (editing) {
            when (name) {
                // 4D encoder (like a hardware sampler's naming screen): turn = letter, tilt ◀ ▶ =
                // cursor, tilt ▲ ▼ = upper / lower case, SHIFT + ◀ = backspace, SHIFT + ▶ = space
                // SHIFT held: the 4D encoder moves around the character grid
                "PAGE LEFT", "ENCODER LEFT" -> if (shift) gridSel = Math.floorMod(gridSel - 1, GRID.length) else moveCursor(-1)
                "PAGE RIGHT", "ENCODER RIGHT" -> if (shift) gridSel = Math.floorMod(gridSel + 1, GRID.length) else moveCursor(1)
                "ENCODER UP" -> if (shift) gridSel = Math.floorMod(gridSel - GCOLS, GRID.length) else caseAt(true)
                "ENCODER DOWN" -> if (shift) gridSel = Math.floorMod(gridSel + GCOLS, GRID.length) else caseAt(false)
                "ENCODER PUSH" -> if (shift) { putGridChar(true); gridTyped = true } else finishEdit()
                "D5" -> startEdit(Project.suggestName(ui.engine.bpm), renaming)
                "D6" -> if (cursor < this.name.length) this.name.deleteCharAt(cursor) else backspace()
                "D7" -> { editing = false; savingGroup = false; savingSound = false; nameTarget = 0; message = "Cancelled" }
                "D8" -> finishEdit()
                "FILE" -> ui.showHome()
            }
            ui.invalidate()
            return
        }
        when (name) {
            "ENCODER PUSH", "D8" -> activate()
            "ENCODER UP", "PAGE LEFT" -> move(-1)
            "ENCODER DOWN", "PAGE RIGHT" -> move(1)
            "FILE" -> ui.showHome()
        }
    }

    private fun activate() {
        if (busy) return
        when (val it = items.getOrNull(sel)) {
            is Item.Save -> save(Project.currentName(ui.ctx) ?: Project.nextName())
            Item.Export -> { exporting = true; exportState = 0; message = "" }
            Item.SaveNew -> startEdit(Project.suggestName(ui.engine.bpm), false)
            is Item.Rename -> startEdit(Project.currentName(ui.ctx) ?: "", true)
            Item.New -> if (armed != sel) { armed = sel; message = "Push again: unsaved changes are lost" } else {
                Project.clear(ui.ctx, ui.engine)
                KitStore.save(ui.ctx, ui.engine.kit)
                ui.engine.params.save(ui.ctx); ui.engine.pattern.save(ui.ctx)
                ui.checkpoint()
                message = "New project"
                armed = -1
                refresh()
            }
            is Item.SaveGroup -> { savingGroup = true; startEdit("Kit ${ui.groupLetter} " + Project.suggestName(ui.engine.bpm).substringBefore(' '), false) }
            is Item.RenameSound -> { nameTarget = 1; startEdit(ui.soundName(ui.selectedIndex), false) }
            is Item.RenameGroup -> { nameTarget = 2; startEdit(org.openmk3.core.audio.Names.group[ui.group].ifEmpty { "Group ${ui.groupLetter}" }, false) }
            is Item.SaveSound -> { savingSound = true; startEdit(ui.engine.kit[ui.selectedIndex].name.ifEmpty { "Sound" }, false) }
            is Item.LoadSound -> loadSound(it.file)
            is Item.LoadGroup -> if (armed != sel) { armed = sel; message = "Push again: replaces group ${ui.groupLetter}" } else loadGroup(it.file)
            is Item.Load -> if (armed != sel) { armed = sel; message = "Push again to load (unsaved changes are lost)" } else load(it.file)
            null -> {}
        }
        ui.invalidate()
    }

    private fun save(name: String) {
        try {
            Project.save(ui.ctx, ui.engine, Colours.group, ui.keys, name)
            message = "Saved \"$name\""
        } catch (e: Exception) {
            message = "Can't save: ${e.message}"
        }
        armed = -1
        refresh()
    }

    private fun loadSound(f: File) {
        busy = true
        val s = ui.selectedIndex
        val label = ui.padLabel()
        message = "Loading ${f.nameWithoutExtension}…"
        worker.execute {
            val ok = org.openmk3.core.audio.GroupFile.loadSoundFile(ui.engine, s, f) { i, sample -> ui.engine.setSample(i, sample) }
            ui.post {
                busy = false
                message = if (ok) "\"${f.nameWithoutExtension}\" → $label" else "Can't load ${f.name}"
                if (ok) {
                    KitStore.save(ui.ctx, ui.engine.kit); ui.engine.params.save(ui.ctx); ui.engine.fx.save(ui.ctx)
                    ui.engine.poly.save(ui.ctx); ui.engine.zones.save(ui.ctx); ui.checkpoint()
                    ui.engine.trigger(s, 0.8f)
                }
                refresh()
            }
        }
    }

    private fun loadGroup(f: File) {
        busy = true
        val g = ui.group
        message = "Loading ${f.nameWithoutExtension} into ${ui.groupLetter}…"
        armed = -1
        worker.execute {
            val colour = org.openmk3.core.audio.GroupFile.load(ui.engine, g, f) { s, sample -> ui.engine.setSample(s, sample) }
            ui.post {
                busy = false
                if (colour == null) message = "Can't load ${f.name}"
                else {
                    if (colour > 0) { Colours.group[g] = colour; Colours.save(ui.ctx) }
                    KitStore.save(ui.ctx, ui.engine.kit)
                    ui.engine.params.save(ui.ctx); ui.engine.pattern.save(ui.ctx); ui.engine.fx.save(ui.ctx)
                    ui.engine.poly.save(ui.ctx); ui.engine.zones.save(ui.ctx)
                    ui.checkpoint()
                    message = "Loaded \"${f.nameWithoutExtension}\" into group ${'A' + g}"
                }
                refresh()
            }
        }
    }

    private fun load(f: File) {
        busy = true
        message = "Loading ${f.nameWithoutExtension}…"
        armed = -1
        worker.execute {
            val err = Project.load(ui.ctx, ui.engine, Colours.group, ui.keys, f) { s, sample -> ui.engine.setSample(s, sample) }
            ui.post {
                busy = false
                message = err ?: "Loaded \"${f.nameWithoutExtension}\""
                KitStore.save(ui.ctx, ui.engine.kit)
                ui.engine.params.save(ui.ctx); ui.engine.pattern.save(ui.ctx); ui.engine.fx.save(ui.ctx)
                Colours.save(ui.ctx); ui.keys.save(ui.ctx)
                ui.checkpoint()
                refresh()
            }
        }
    }

    private val nameText = Theme.text(26f, Theme.FG, bold = true)
    private val cursorFill = Theme.fill(Theme.ACCENT)

    private fun drawEditor(l: Canvas, r: Canvas, now: Long) {
        l.drawText(if (nameTarget == 1) "NAME SOUND ${ui.padLabel()}" else if (nameTarget == 2) "NAME GROUP ${ui.groupLetter}" else if (savingSound) "SAVE SOUND ${ui.padLabel()}" else if (savingGroup) "SAVE GROUP ${ui.groupLetter}" else if (renaming) "RENAME PROJECT" else "SAVE AS", 16f, 30f, title)
        val n = name.toString()
        val x0 = 16f
        val y = 120f
        l.drawText(n, x0, y, nameText)
        // cursor: under the letter it changes (a block past the end)
        val cx = x0 + nameText.measureText(n, 0, cursor.coerceAtMost(n.length))
        val cw = if (cursor < n.length) nameText.measureText(n, cursor, cursor + 1).coerceAtLeast(8f) else 14f
        if ((now / 400) % 2 == 0L || true) l.drawRect(cx, y + 6f, cx + cw, y + 10f, cursorFill)
        if (shift) {
            // the character grid: the 4D encoder moves the highlight, releasing SHIFT enters it
            val gx = 16f; val gy = 150f; val cw2 = 34f; val ch2 = 20f
            val gTxt = Theme.text(17f, Theme.FG, bold = true, align = Paint.Align.CENTER)
            for (i in GRID.indices) {
                val x = gx + (i % GCOLS) * cw2; val yy = gy + (i / GCOLS) * ch2
                if (i == gridSel) l.drawRect(x, yy - 17f, x + cw2 - 2f, yy + 4f, cursorFill)
                l.drawText(if (GRID[i] == ' ') "␣" else GRID[i].toString(), x + cw2 / 2, yy, gTxt)
            }
        } else {
            l.drawText("Turn: letter · ◀ ▶: cursor · ▲ ▼: upper / lower case", 16f, 170f, label)
            l.drawText("Hold SHIFT: letters (4D encoder moves, release enters, push types)", 16f, 192f, label)
        }
        // the characters around the one under the cursor, as a strip
        val cur = if (cursor < n.length) n[cursor] else ' '
        val ci = CHARS.indexOf(cur).coerceAtLeast(0)
        val big = Theme.text(24f, Theme.FG, bold = true, align = Paint.Align.CENTER)
        val small = Theme.text(18f, Theme.DIM, align = Paint.Align.CENTER)
        for (k in -6..6) {
            val ch = CHARS[Math.floorMod(ci + k, CHARS.length)]
            val x = 240f + k * 34f
            if (k == 0) r.drawRect(x - 16f, 132f, x + 16f, 172f, selFill)
            r.drawText(if (ch == ' ') "␣" else ch.toString(), x, 162f, if (k == 0) big else small)
        }
        listOf("SUGGEST", "DELETE", "CANCEL", if (renaming) "RENAME" else "SAVE").forEachIndexed { i, t -> r.drawText(t, 60f + i * 120f, 24f, dLabel) }
        r.drawText("A name is suggested: keep it, change it,", 16f, 70f, label)
        r.drawText("or press D5 for another one", 16f, 92f, label)
        if (message.isNotEmpty()) r.drawText(message.take(44), 16f, 220f, if (message.startsWith("Can't") || message.contains("exists")) warn else ok)
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        if (editing) { drawEditor(l, r, now); return }
        if (exporting) { drawExport(l, r); return }
        l.drawText("FILE", 16f, 30f, title)
        l.drawText(Project.currentName(ui.ctx)?.let { "Project: $it" } ?: "Unsaved project", 90f, 29f, label)
        for (i in 0 until 8) {
            val idx = top + i
            val it = items.getOrNull(idx) ?: break
            val y = 44f + i * 27f
            if (idx == sel) l.drawRect(0f, y, 480f, y + 26f, selFill)
            l.drawText(it.label.take(40), 16f, y + 19f, if (it is Item.Load || it is Item.LoadGroup || it is Item.LoadSound) row else rowAct)
        }
        r.drawText("OK", 420f, 24f, dLabel)
        r.drawText("Encoder: move · push / D8: do it", 16f, 70f, label)
        r.drawText("SHIFT + FILE anywhere: quick save", 16f, 94f, label)
        r.drawText("Projects keep the song, kit (sample paths),", 16f, 130f, label)
        r.drawText("sound settings, effects, colours and keys", 16f, 150f, label)
        r.drawText("in Download/…/Projects", 16f, 170f, label)
        if (message.isNotEmpty()) r.drawText(message.take(44), 16f, 220f, if (message.startsWith("Can't")) warn else ok)
        if (busy) r.drawText("…", 16f, 250f, ok)
    }
}

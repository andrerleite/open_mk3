package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import org.openmk3.core.AppLog
import org.openmk3.core.audio.KitStore
import org.openmk3.core.audio.Project
import java.io.File
import java.util.concurrent.Executors

/**
 * FILE: save, save as new, new project, and the saved projects (newest first).
 *  Encoder turn / ◀ ▶: move · encoder push or D8: do it (loading and NEW ask for a second push)
 *  SHIFT + FILE (anywhere): quick save · FILE: back
 */
class FilePage(ui: Ui) : Page(ui) {

    private sealed class Item(val label: String) {
        class Save(name: String) : Item("SAVE  \"$name\"")
        object SaveNew : Item("SAVE AS NEW PROJECT")
        object New : Item("NEW EMPTY PROJECT")
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
            add(Item.New)
            Project.list().forEach { add(Item.Load(it)) }
        }
        sel = sel.coerceIn(0, items.size - 1)
        ui.invalidate()
    }

    override fun lights(l: Lights) {
        l.set("FILE", true)
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

    override fun onEncoder(delta: Int) = move(delta)

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (!down) return
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
            Item.SaveNew -> save(Project.nextName())
            Item.New -> if (armed != sel) { armed = sel; message = "Push again: unsaved changes are lost" } else {
                Project.clear(ui.ctx, ui.engine)
                KitStore.save(ui.ctx, ui.engine.kit)
                ui.engine.params.save(ui.ctx); ui.engine.pattern.save(ui.ctx)
                ui.checkpoint()
                message = "New project"
                armed = -1
                refresh()
            }
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

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText("FILE", 16f, 30f, title)
        l.drawText(Project.currentName(ui.ctx)?.let { "Project: $it" } ?: "Unsaved project", 90f, 29f, label)
        for (i in 0 until 8) {
            val idx = top + i
            val it = items.getOrNull(idx) ?: break
            val y = 44f + i * 27f
            if (idx == sel) l.drawRect(0f, y, 480f, y + 26f, selFill)
            l.drawText(it.label.take(40), 16f, y + 19f, if (it is Item.Load) row else rowAct)
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

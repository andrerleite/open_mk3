package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import org.openmk3.core.AppLog
import org.openmk3.core.audio.Keys
import org.openmk3.core.mk3.Mk3Colors
import kotlin.math.abs

/**
 * EVENTS (hold; D1 pins): select notes and edit them.
 *  D2 EDIT / SELECT · D5 all · D6 none · D7 / D8 previous / next sound · ERASE + pad: erase a sound's notes
 *  Pads: select / deselect all notes of a sound (unlit: none · dim: has notes · lit: all selected)
 *  SELECT: K1 START / K2 END select the focused sound's notes in that range (step grid), K4 EVENT
 *          picks single notes, ◀ ▶ the previous / next note
 *  EDIT:   K1 POSITION (nudge grid; SHIFT fine), K2 PITCH, K3 VELOCITY, K4 LENGTH (step grid; SHIFT fine)
 *  4D encoder ▲ ▼: the focused sound · in EDIT, ◀ ▶ page through long patterns
 */
class EventsPage(ui: Ui, private val back: Page) : Page(ui) {

    override val customPads = true

    private companion object {
        var editing = true
    }

    private val p = ui.engine.pattern
    /** Selected notes: (sound, step). */
    private val sel = mutableSetOf<Pair<Int, Int>>()
    private var shift = false
    private var erase = false
    private var page = 0
    private val acc = IntArray(4)
    private var message = ""
    private var rangeA = 0f
    private var rangeB = 16f
    private var eventIdx = 0

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val ok = Theme.text(15f, Theme.OK)
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val dOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val kName = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val kValue = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val rowName = Theme.text(12f, Theme.FG)
    private val grid = Theme.fill(android.graphics.Color.rgb(32, 32, 40))
    private val gridBeat = Theme.fill(android.graphics.Color.rgb(44, 44, 54))
    private val head = Theme.fill(android.graphics.Color.WHITE)
    private val outline = Theme.stroke(Theme.FG, 2f)
    private val rangeFill = Theme.fill(android.graphics.Color.argb(50, 255, 140, 40))

    private val focus get() = ui.selectedIndex
    private fun events(snd: Int) = p.stepsOf(snd)
    private fun allSelected(snd: Int) = events(snd).let { it.isNotEmpty() && it.all { st -> (snd to st) in sel } }

    init {
        for (st in events(focus)) sel.add(focus to st)
    }

    fun back() = ui.show(back)

    private val pages get() = (p.length(ui.group) + 15) / 16

    override fun tick(now: Long): Boolean = true

    override fun lights(l: Lights) {
        l.set("EVENTS", true)
        l.set("D2", !editing)
        l.set("D5", "D6", "D7", "D8")
        l.set("ERASE", erase)
        l.set("SHIFT", shift)
        l.set("PAGE LEFT", "PAGE RIGHT")
    }

    /** The selected notes, or (none selected) all notes of the group. */
    private fun target(): List<Pair<Int, Int>> =
        if (sel.isNotEmpty()) sel.toList() else (0 until 16).flatMap { i -> events(ui.snd(i)).map { ui.snd(i) to it } }

    /** SHIFT + pad on EVENTS: the printed shortcuts, on the selected notes. */
    private fun shortcut(slot: Int) {
        val t = target()
        when (slot) {
            0, 2 -> { ui.shiftShortcut(slot); sel.clear() }
            1, 3 -> { ui.shiftShortcut(slot); sel.clear() }
            4, 5 -> {
                // quantise the notes to the step grid (pad 6: half way)
                val g = ui.stepGrid
                val amt = if (slot == 4) 1f else 0.5f
                val order = t.sortedBy { it.second }
                val moved = order.map { (snd, st) ->
                    val pos = st + p.noteOffset(snd, st)
                    val q = kotlin.math.round(pos / g) * g
                    snd to p.moveNote(snd, st, pos + (q - pos) * amt)
                }
                if (sel.isNotEmpty()) { sel.clear(); sel.addAll(moved) }
                changed(if (slot == 4) "Quantised" else "Quantised 50 %")
            }
            6, 7 -> {
                val move = if (slot == 6) -ui.nudgeGrid else ui.nudgeGrid
                val order = if (move > 0) t.sortedByDescending { it.second } else t.sortedBy { it.second }
                val moved = order.map { (snd, st) -> snd to p.moveNote(snd, st, st + p.noteOffset(snd, st) + move) }
                if (sel.isNotEmpty()) { sel.clear(); sel.addAll(moved) }
                changed(if (slot == 6) "Nudged left" else "Nudged right")
            }
            8 -> { for ((snd, st) in t) p.clearStep(snd, st); sel.clear(); changed("${t.size} note${if (t.size != 1) "s" else ""} cleared") }
            9 -> ui.shiftShortcut(slot)
            10 -> {
                ui.noteClipboard = t.map { (snd, st) -> Ui.CopiedNote(snd % 16, st + p.noteOffset(snd, st), p.noteOf(snd, st), p.vel(snd, st), p.noteLength(snd, st)) }
                changed("${t.size} note${if (t.size != 1) "s" else ""} copied")
            }
            11 -> {
                val c = ui.noteClipboard
                if (c.isEmpty()) { changed("Nothing copied"); return }
                // pasted at the same places, into this group's pattern (the copy can come from another pattern)
                sel.clear()
                for (n in c) {
                    val snd = ui.snd(n.pad)
                    val st = kotlin.math.floor(n.pos).toInt()
                    if (st >= p.length(ui.group)) continue
                    p.recordNote(snd, st, n.note, n.vel, n.pos - st, n.len)
                    sel.add(snd to st)
                }
                changed("${c.size} note${if (c.size != 1) "s" else ""} pasted")
            }
            12, 13, 14, 15 -> {
                val d = intArrayOf(-1, 1, -12, 12)[slot - 12]
                for ((snd, st) in t) p.editStep(snd, st, semis = d)
                changed("Transposed ${if (d > 0) "+" else ""}$d")
            }
        }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (shift) { shortcut(slot); return }
        val snd = ui.snd(slot)
        if (erase) { p.clear(snd); sel.removeAll { it.first == snd }; changed("${ui.padLabel(slot)} notes erased"); return }
        if (events(snd).isEmpty()) return
        if (allSelected(snd)) sel.removeAll { it.first == snd } else for (st in events(snd)) sel.add(snd to st)
        ui.selectedSound = slot
    }

    /** The focused sound's notes in time order. */
    private fun focusEvents() = events(focus).sortedBy { it + p.noteOffset(focus, it) }

    private fun selectOne(i: Int) {
        val ev = focusEvents()
        if (ev.isEmpty()) return
        eventIdx = i.coerceIn(0, ev.size - 1)
        sel.clear()
        sel.add(focus to ev[eventIdx])
        page = ev[eventIdx] / 16
    }

    private fun selectRange() {
        sel.removeAll { it.first == focus }
        for (st in events(focus)) {
            val t = st + p.noteOffset(focus, st)
            if (t >= rangeA && t < rangeB) sel.add(focus to st)
        }
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        when (name) {
            "SHIFT" -> { shift = down; return }
            "ERASE" -> { erase = down; return }
        }
        if (!down) return
        when (name) {
            "D2" -> editing = !editing
            "D5" -> { sel.clear(); for (i in 0 until 16) for (st in events(ui.snd(i))) sel.add(ui.snd(i) to st) }
            "D6" -> sel.clear()
            "D7", "ENCODER DOWN" -> ui.selectedSound = (ui.selectedSound + 15) % 16
            "D8", "ENCODER UP" -> ui.selectedSound = (ui.selectedSound + 1) % 16
            "PAGE LEFT" -> if (editing) page = (page - 1).coerceAtLeast(0) else selectOne(eventIdx - 1)
            "PAGE RIGHT" -> if (editing) page = (page + 1).coerceAtMost(pages - 1) else selectOne(eventIdx + 1)
        }
    }

    private fun notch(k: Int, delta: Int): Int {
        acc[k] += delta
        if (abs(acc[k]) < 12) return 0
        val d = if (acc[k] > 0) 1 else -1
        acc[k] = 0
        return d
    }

    override fun onKnob(knob: Int, delta: Int) {
        if (!editing) {
            val len = p.length(ui.group).toFloat()
            when (knob) {
                0 -> notch(0, delta).takeIf { it != 0 }?.let { rangeA = (rangeA + it * ui.stepGrid).coerceIn(0f, rangeB - ui.stepGrid); selectRange() }
                1 -> notch(1, delta).takeIf { it != 0 }?.let { rangeB = (rangeB + it * ui.stepGrid).coerceIn(rangeA + ui.stepGrid, len); selectRange() }
                3 -> notch(3, delta).takeIf { it != 0 }?.let { selectOne(eventIdx + it) }
            }
            return
        }
        if (sel.isEmpty()) return
        when (knob) {
            0 -> {
                val move = if (shift) delta * 0.005f else notch(0, delta) * ui.nudgeGrid
                if (move == 0f) return
                // the leading notes first, so moved notes don't land on each other
                val order = if (move > 0) sel.sortedByDescending { it.second } else sel.sortedBy { it.second }
                val moved = order.map { (snd, st) -> snd to p.moveNote(snd, st, st + p.noteOffset(snd, st) + move) }
                sel.clear(); sel.addAll(moved)
                changed(null)
            }
            1 -> notch(1, delta).takeIf { it != 0 }?.let { d -> for ((snd, st) in sel) p.editStep(snd, st, semis = d); changed(null) }
            2 -> { for ((snd, st) in sel) p.editStep(snd, st, velDelta = delta * (if (shift) 0.001f else 0.004f)); changed(null) }
            3 -> {
                val add = if (shift) delta * 0.005f else notch(3, delta) * ui.stepGrid
                if (add == 0f) return
                for ((snd, st) in sel) p.setNoteLength(snd, st, (p.noteLength(snd, st) + add).coerceAtLeast(0.05f))
                changed(null)
            }
        }
    }

    private var saveAt = 0L
    private fun changed(msg: String?) {
        if (msg != null) { message = msg; AppLog.i("Events: $msg") }
        saveAt = android.os.SystemClock.uptimeMillis()
    }

    override fun onExit() {
        if (saveAt != 0L) { p.save(ui.ctx); ui.checkpoint() }
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        if (saveAt != 0L && now - saveAt > 1200) { p.save(ui.ctx); ui.checkpoint(); saveAt = 0L }
        sel.retainAll { (snd, st) -> p.hasStep(snd, st) }
        // pads: unlit = no notes, dim = has notes, lit = all its notes selected
        for (i in 0 until 16) {
            val snd = ui.snd(i)
            val c = ui.soundColour(snd)
            val v = when {
                allSelected(snd) -> Mk3Colors.value(c, 3)
                sel.any { it.first == snd } -> Mk3Colors.value(c, 2)
                events(snd).isNotEmpty() -> Mk3Colors.value(c, 0)
                else -> 0
            }
            ui.padLed(i, v)
        }
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText("PIN", 60f, 24f, if ("EVENTS" in ui.pinned) dOn else dLabel)
        l.drawText(if (editing) "EDIT" else "SELECT", 180f, 24f, dOn)
        r.drawText("ALL", 60f, 24f, dLabel)
        r.drawText("NONE", 180f, 24f, dLabel)
        r.drawText("◀ SOUND", 300f, 24f, dLabel)
        r.drawText("SOUND ▶", 420f, 24f, dLabel)
        l.drawText("EVENTS · ${ui.padLabel()} ${ui.soundName(focus).take(14)}", 16f, 60f, title)
        l.drawText(if (sel.isEmpty()) "Pads or SELECT (D2) choose notes" else "${sel.size} note${if (sel.size > 1) "s" else ""} selected", 16f, 92f, label)
        l.drawText(if (editing) "SHIFT: fine · ERASE + pad: erase · ◀ ▶ page" else "◀ ▶ previous / next note", 16f, 114f, label)
        if (message.isNotEmpty()) l.drawText(message, 16f, 150f, ok)
        fun knob(i: Int, n: String, v: String) { val x = 60f + i * 120f; l.drawText(n, x, 244f, kName); l.drawText(v, x, 264f, kValue) }
        val one = sel.singleOrNull()
        if (editing) {
            val multi = if (sel.isEmpty()) "—" else "(MULTI)"
            knob(0, "POSITION", one?.let { (s, t) -> "%.2f".format(t + 1 + p.noteOffset(s, t)) } ?: multi)
            knob(1, "PITCH", one?.let { (s, t) -> Keys.noteName(p.noteOf(s, t)) } ?: multi)
            knob(2, "VELOCITY", one?.let { (s, t) -> "${(p.vel(s, t) * 127).toInt()}" } ?: multi)
            knob(3, "LENGTH", one?.let { (s, t) -> "%.2f".format(p.noteLength(s, t)) } ?: multi)
        } else {
            knob(0, "START", "%.2f".format(rangeA + 1))
            knob(1, "END", "%.2f".format(rangeB + 1))
            knob(3, "EVENT", if (one != null) "${focusEvents().indexOf(one.second) + 1}" else if (sel.isEmpty()) "—" else "(MULTI)")
        }
        // right: lanes of the focused sound and the sounds with selected notes, on the current page
        val len = p.length(ui.group)
        val pg = page.coerceIn(0, pages - 1)
        val rows = (0 until 16).map { ui.snd(it) }.filter { it == focus || sel.any { e -> e.first == it } }.take(9)
        val x0 = 60f; val cw = 26f; val top = 50f; val rh = 24f
        for (t in 0 until 16) r.drawRect(x0 + t * cw, top - 6f, x0 + t * cw + cw - 1f, top - 2f, if (t % 4 == 0) gridBeat else grid)
        val st = p.stepOf(ui.group)
        if (st >= 0 && st / 16 == pg) r.drawRect(x0 + (st % 16) * cw, top - 8f, x0 + (st % 16) * cw + cw - 1f, top - 2f, head)
        for ((row, snd) in rows.withIndex()) {
            val y = top + row * rh
            r.drawText(org.openmk3.core.audio.soundLabel(snd), 4f, y + 16f, rowName)
            for (t in 0 until 16) r.drawRect(x0 + t * cw, y + 2f, x0 + t * cw + cw - 1f, y + rh - 2f, if ((t / 4) % 2 == 0) gridBeat else grid)
            if (!editing && snd == focus) {
                val a = x0 + (rangeA - pg * 16).coerceIn(0f, 16f) * cw
                val b = x0 + (rangeB - pg * 16).coerceIn(0f, 16f) * cw
                if (b > a) r.drawRect(a, y + 2f, b, y + rh - 2f, rangeFill)
            }
            val col = Theme.fill(Mk3Colors.rgb(ui.soundColour(snd)))
            for (t in events(snd)) {
                val start = t + p.noteOffset(snd, t) - pg * 16
                val end = start + p.noteLength(snd, t)
                if (end < 0f || start >= 16f || t >= len) continue
                val a = x0 + start.coerceAtLeast(0f) * cw
                val b = maxOf(a + 3f, x0 + end.coerceAtMost(16f) * cw - 1f)
                val isSel = (snd to t) in sel
                col.alpha = if (isSel) 255 else 90
                r.drawRect(a, y + 5f, b, y + rh - 5f, col)
                if (isSel) r.drawRect(a, y + 5f, b, y + rh - 5f, outline)
            }
        }
        if (pages > 1) r.drawText("page ${pg + 1}/$pages", 400f, 266f, label)
    }
}

package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import org.openmk3.core.AppLog
import org.openmk3.core.mk3.Mk3Colors
import kotlin.math.abs

/**
 * EVENTS (hold; D1 pins): select notes by sound and edit them.
 *  D2 SELECT / EDIT · D5 select all sounds · D6 select none · ◀ ▶ page through long patterns
 *  Pads: add / remove a sound's notes (unlit: no notes · dim: has notes · lit: selected)
 *  ERASE + pad: erase that sound's notes
 *  Edit knobs: K1 POSITION (one step per notch, SHIFT: fine), K2 NOTE (semitones),
 *  K3 VELOCITY, K4 LENGTH (one step per notch, SHIFT: fine)
 */
class EventsPage(ui: Ui, private val back: Page) : Page(ui) {

    override val customPads = true

    private companion object {
        var editing = true
    }

    private val p = ui.engine.pattern
    private val selected = mutableSetOf<Int>()
    private var shift = false
    private var erase = false
    private var page = 0
    private val acc = IntArray(4)
    private var message = ""

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

    init {
        val f = ui.selectedIndex
        if (p.stepsOf(f).isNotEmpty()) selected.add(f)
    }

    fun back() = ui.show(back)

    private val pages get() = (p.length(ui.group) + 15) / 16

    override fun tick(now: Long): Boolean = true

    override fun lights(l: Lights) {
        l.set("EVENTS", true)
        l.set("D2", !editing)
        l.set("D5", "D6")
        l.set("ERASE", erase)
        l.set("SHIFT", shift)
        if (pages > 1) l.set("PAGE LEFT", "PAGE RIGHT")
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        val snd = ui.snd(slot)
        if (erase) { p.clear(snd); selected.remove(snd); changed("${ui.padLabel(slot)} notes erased"); return }
        if (p.stepsOf(snd).isEmpty()) return
        if (!selected.remove(snd)) selected.add(snd)
        ui.selectedSound = slot
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        when (name) {
            "SHIFT" -> { shift = down; return }
            "ERASE" -> { erase = down; return }
        }
        if (!down) return
        when (name) {
            "D2" -> editing = !editing
            "D5" -> { selected.clear(); for (i in 0 until 16) if (p.stepsOf(ui.snd(i)).isNotEmpty()) selected.add(ui.snd(i)) }
            "D6" -> selected.clear()
            "PAGE LEFT" -> page = (page - 1).coerceAtLeast(0)
            "PAGE RIGHT" -> page = (page + 1).coerceAtMost(pages - 1)
        }
    }

    private fun notch(k: Int, delta: Int): Int {
        acc[k] += delta
        if (abs(acc[k]) < 25) return 0
        val d = if (acc[k] > 0) 1 else -1
        acc[k] = 0
        return d
    }

    override fun onKnob(knob: Int, delta: Int) {
        if (selected.isEmpty()) return
        when (knob) {
            0 -> if (shift) { p.move(selected, delta / 400f); changed(null) }
                 else notch(0, delta).takeIf { it != 0 }?.let { p.move(selected, it * ui.nudgeGrid); changed("Moved ${if (it > 0) "right" else "left"}") }
            1 -> notch(1, delta).takeIf { it != 0 }?.let { d -> selected.forEach { p.transpose(it, d) }; changed("Transposed ${if (d > 0) "+1" else "−1"}") }
            2 -> { p.addVelocity(selected, delta / 400f); changed(null) }
            3 -> if (shift) { p.addLength(selected, delta / 400f); changed(null) }
                 else notch(3, delta).takeIf { it != 0 }?.let { p.addLength(selected, it.toFloat()); changed("Length ${if (it > 0) "+1" else "−1"} step") }
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
        // pads: unlit = no notes, dim = has notes, lit = selected
        for (i in 0 until 16) {
            val snd = ui.snd(i)
            val c = ui.soundColour(snd)
            val v = when {
                snd in selected -> Mk3Colors.value(c, 3)
                p.stepsOf(snd).isNotEmpty() -> Mk3Colors.value(c, 0)
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
        l.drawText("EVENTS · GROUP ${ui.groupLetter}", 16f, 60f, title)
        val notes = selected.sumOf { p.stepsOf(it).size }
        l.drawText(if (selected.isEmpty()) "Hit pads to select their notes" else "${selected.size} sound(s) · $notes note(s)", 16f, 92f, label)
        l.drawText("SHIFT: fine · ERASE + pad: erase", 16f, 114f, label)
        if (message.isNotEmpty()) l.drawText(message, 16f, 150f, ok)
        fun knob(i: Int, n: String) { val x = 60f + i * 120f; l.drawText(n, x, 244f, kName); l.drawText("◀ ▶", x, 264f, kValue) }
        knob(0, "POSITION"); knob(1, "NOTE"); knob(2, "VELOCITY"); knob(3, "LENGTH")
        // right: lanes of the selected sounds on the current page
        val len = p.length(ui.group)
        val pg = page.coerceIn(0, pages - 1)
        val rows = (0 until 16).map { ui.snd(it) }.filter { it in selected }.take(9)
        val x0 = 60f; val cw = 26f; val top = 44f; val rh = 24f
        for (t in 0 until 16) r.drawRect(x0 + t * cw, top - 6f, x0 + t * cw + cw - 1f, top - 2f, if (t % 4 == 0) gridBeat else grid)
        val st = p.stepOf(ui.group)
        if (st >= 0 && st / 16 == pg) r.drawRect(x0 + (st % 16) * cw, top - 8f, x0 + (st % 16) * cw + cw - 1f, top - 2f, head)
        for ((row, snd) in rows.withIndex()) {
            val y = top + row * rh
            r.drawText(org.openmk3.core.audio.soundLabel(snd), 4f, y + 16f, rowName)
            for (t in 0 until 16) r.drawRect(x0 + t * cw, y + 2f, x0 + t * cw + cw - 1f, y + rh - 2f, if ((t / 4) % 2 == 0) gridBeat else grid)
            val col = Theme.fill(Mk3Colors.rgb(ui.soundColour(snd)))
            for (t in p.stepsOf(snd)) {
                val start = t + p.noteOffset(snd, t) - pg * 16
                val end = start + p.noteLength(snd, t)
                if (end < 0f || start >= 16f || t >= len) continue
                val a = x0 + start.coerceAtLeast(0f) * cw
                val b = x0 + end.coerceAtMost(16f) * cw - 1f
                val alpha = (0.4f + 0.6f * p.vel(snd, t)).coerceIn(0.4f, 1f)
                col.alpha = (alpha * 255).toInt()
                r.drawRect(a, y + 5f, maxOf(a + 3f, b), y + rh - 5f, col)
            }
        }
        if (pages > 1) r.drawText("page ${pg + 1}/$pages", 400f, 266f, label)
    }
}

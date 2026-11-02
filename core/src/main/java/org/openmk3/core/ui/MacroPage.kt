package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import org.openmk3.core.AppLog
import org.openmk3.core.audio.SoundParams
import org.openmk3.core.audio.soundLabel

/**
 * MACRO: eight macro knobs per group, each controlling one sound parameter.
 *  K1–K8: the assigned parameters · SHIFT + MACRO (Set): D1–D8 pick a macro, then turn any
 *  sound parameter on the PLUG-IN page to assign it (ERASE + D-button clears) · MACRO: back
 */
class MacroPage(ui: Ui, private val setMode: Boolean) : Page(ui) {

    override val playsPads = true

    private val sp = ui.engine.params
    private var shift = false
    private var erase = false
    private val dLabel = Theme.text(14f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val kName = Theme.text(12f, Theme.DIM, align = Paint.Align.CENTER)
    private val kValue = Theme.text(15f, Theme.FG, bold = true, align = Paint.Align.CENTER)

    private fun target(i: Int): Pair<Int, Int>? {
        val m = ui.macros[ui.group]
        val s = m[i * 2]
        val p = m[i * 2 + 1]
        return if (s >= 0 && p >= 0) s to p else null
    }

    override fun lights(l: Lights) {
        l.set("MACRO", true)
        l.set("SHIFT", shift)
        if (setMode) for (i in 0 until 8) l.set("D${i + 1}", target(i) != null)
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        when (name) {
            "SHIFT" -> { shift = down; return }
            "ERASE" -> { erase = down; return }
        }
        if (!down) return
        if (name == "MACRO") { ui.showHome(); return }
        if (setMode && name != null && name.length == 2 && name[0] == 'D' && name[1] in '1'..'8') {
            val i = name[1] - '1'
            if (erase) { ui.macros[ui.group][i * 2] = -1; ui.macros[ui.group][i * 2 + 1] = -1; ui.saveMacros(); return }
            // learn: the next sound parameter turned on the PLUG-IN page goes to this macro
            ui.macroLearn = ui.group * 8 + i
            AppLog.i("Macro ${i + 1}: turn a parameter on the PLUG-IN page")
            if (org.openmk3.core.Edition.full) ui.show(SoundPage(ui))
        }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (ui.padMode == Ui.PadMode.PAD) ui.selectedSound = slot
    }

    override fun onKnob(knob: Int, delta: Int) {
        val (s, p) = target(knob) ?: return
        sp.nudge(s, p, delta, shift)
        ui.autoWrite(s, p)
        saveAt = android.os.SystemClock.uptimeMillis()
    }

    private var saveAt = 0L
    override fun tick(now: Long): Boolean {
        if (saveAt != 0L && now - saveAt > 1500) { sp.save(ui.ctx); saveAt = 0L }
        return false
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText(if (setMode) "MACRO SET · GROUP ${ui.groupLetter}" else "MACRO · GROUP ${ui.groupLetter}", 16f, 60f, title)
        l.drawText(if (setMode) "D1–D8: pick a macro, then turn a PLUG-IN parameter (ERASE + D clears)" else "SHIFT + MACRO: assign parameters", 16f, 92f, label)
        for (i in 0 until 8) {
            val c = if (i < 4) l else r
            val x = 60f + (i % 4) * 120f
            val t = target(i)
            if (setMode) c.drawText("M${i + 1}", x, 24f, dLabel)
            val name = t?.let { (s, p) -> "${soundLabel(s)} ${SoundParams.DEFS[p]?.name ?: p}" } ?: "—"
            c.drawText(name.take(16), x, 230f, kName)
            c.drawText(t?.let { (s, p) -> sp.display(s, p) } ?: "", x, 256f, kValue)
        }
    }
}

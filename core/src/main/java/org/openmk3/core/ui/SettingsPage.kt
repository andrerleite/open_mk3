package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint

/**
 * SETTINGS: metronome and count-in.
 *  D1 metronome on / off · K1 metronome level · K2 count-in length (1, 2, 4 bars)
 *  D8 LED palette (controller check) · SETTINGS: back
 */
class SettingsPage(ui: Ui) : Page(ui) {

    private val p = ui.engine.pattern
    private var acc = 0
    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val dOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val kName = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val kValue = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)

    override fun lights(l: Lights) {
        l.set("SETTINGS", true)
        l.set("D1", p.metronome)
        l.set("D8")
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (!down) return
        when (name) {
            "D1" -> { p.metronome = !p.metronome; p.save(ui.ctx) }
            "D8" -> ui.show(PalettePage(ui))
            "SETTINGS" -> ui.showHome()
        }
    }

    override fun onKnob(knob: Int, delta: Int) {
        when (knob) {
            0 -> p.metronomeLevel = p.metronomeLevel + delta / 500f
            1 -> {
                acc += delta
                if (kotlin.math.abs(acc) < 25) return
                val bars = listOf(1, 2, 4)
                val i = (bars.indexOf(p.countInBars).coerceAtLeast(0) + if (acc > 0) 1 else -1).coerceIn(0, 2)
                acc = 0
                p.countInBars = bars[i]
            }
        }
        p.save(ui.ctx)
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText("METRONOME", 60f, 24f, if (p.metronome) dOn else dLabel)
        r.drawText("LED CHECK", 420f, 24f, dLabel)
        l.drawText("SETTINGS", 16f, 70f, title)
        l.drawText("Metronome ${if (p.metronome) "on" else "off"} (also SHIFT + TAP)", 16f, 104f, label)
        l.drawText("Count-in: SHIFT + REC", 16f, 128f, label)
        l.drawText("LEVEL", 60f, 238f, kName)
        l.drawText("${(p.metronomeLevel * 100).toInt()}%", 60f, 260f, kValue)
        l.drawText("COUNT-IN", 180f, 238f, kName)
        l.drawText("${p.countInBars} bar${if (p.countInBars > 1) "s" else ""}", 180f, 260f, kValue)
    }
}

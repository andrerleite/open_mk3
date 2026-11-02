package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.openmk3.core.AppLog
import org.openmk3.core.mk3.Mk3Colors
import org.openmk3.core.mk3.Mk3Device

/**
 * Shows what LED values look like: pad slot N gets value base+N, and every mapped button
 * LED gets value base. Encoder / knob 1 move the base, so all 128 values can be inspected.
 * SETTINGS or STOP returns home.
 */
class PalettePage(ui: Ui) : Page(ui) {

    override val customLeds = true

    private var base = 0
    private val title = Theme.text(22f, Theme.ACCENT, bold = true)
    private val body = Theme.text(17f)
    private val small = Theme.text(15f, Theme.DIM)
    private val cell = Theme.fill(Theme.CELL)
    private val num = Theme.text(18f, Theme.FG, align = Paint.Align.CENTER)

    override fun onEnter() = apply()

    private fun apply() {
        if (ui.map.padLedsMapped) {
            for (s in 0 until 16) ui.padLed(s, (base + s).coerceAtMost(0x7F))
        } else {
            // Unknown layout: put base+i on every index of report 0x81.
            for (i in 0 until Mk3Device.PAD_LED_COUNT) ui.dev.setPadLed(i, (base + i) % 0x80)
        }
        for (idx in ui.map.buttonLeds.values) ui.dev.setButtonLed(idx, base)
        AppLog.i("Palette: base 0x${Integer.toHexString(base)}")
    }

    override fun onEncoder(delta: Int) = move(delta * 16)
    override fun onKnob(knob: Int, delta: Int) {
        if (knob == 0 && delta != 0) move(if (delta > 0) 1 else -1)
    }

    private fun move(d: Int) {
        base = (base + d).coerceIn(0, 0x7F)
        apply()
        ui.invalidate()
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (down && (name == "SETTINGS" || name == "STOP")) ui.showHome()
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        l.drawText("LED PALETTE", 16f, 32f, title)
        l.drawText("Base value: $base (0x${Integer.toHexString(base).uppercase()})", 16f, 72f, body)
        l.drawText("Pads: ${Mk3Colors.describe(base)} … ${Mk3Colors.describe((base + 15).coerceAtMost(0x7F))}", 16f, 100f, small)
        l.drawText("Pad N shows value base+N (colour×4 + brightness).", 16f, 120f, small)
        l.drawText("Mapped button lights show the base value.", 16f, 140f, small)
        l.drawText("ENCODER: ±16    KNOB 1: ±1", 16f, 170f, body)
        l.drawText("SETTINGS or STOP: back", 16f, 200f, body)
        if (!ui.map.padLedsMapped) l.drawText("Pad lights not mapped: showing raw indices", 16f, 250f, Theme.text(15f, Theme.WARN))

        r.drawColor(Theme.BG)
        val size = 60f
        val gap = 6f
        val x0 = (480f - 4 * size - 3 * gap) / 2
        val y0 = (272f - 4 * size - 3 * gap) / 2
        for (slot in 0 until 16) {
            val row = slot / 4
            val col = slot % 4
            val x = x0 + col * (size + gap)
            val y = y0 + (3 - row) * (size + gap)
            r.drawRoundRect(RectF(x, y, x + size, y + size), 8f, 8f, cell)
            r.drawText("${(base + slot).coerceAtMost(0x7F)}", x + size / 2, y + size / 2 + 7f, num)
        }
    }
}

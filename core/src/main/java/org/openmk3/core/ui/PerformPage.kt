package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.RectF
import org.openmk3.core.AppLog
import org.openmk3.core.audio.Pattern
import org.openmk3.core.mk3.Mk3Colors

/**
 * SHIFT + PERFORM: the effect the touch strip plays in PERFORM mode.
 *  Pads 1–10: FILTER (focused group: left low-pass, right high-pass), STUTTER (repeats the last
 *  1/4 … 1/32), TAPE STOP, TRANCE GATE (1/16 chops), LO-FI. The effect is on while the strip is
 *  touched; the strip position is the amount. SHIFT + PERFORM or PERFORM again: back.
 */
class PerformPage(ui: Ui) : Page(ui) {

    override val customPads = true

    private val p = ui.engine.pattern
    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val big = Theme.text(22f, Theme.FG, bold = true)
    private val cell = Theme.text(15f, Theme.FG, bold = true, align = android.graphics.Paint.Align.CENTER)
    private val off = Theme.fill(android.graphics.Color.rgb(32, 32, 40))
    private val dim = Theme.fill(android.graphics.Color.rgb(70, 70, 90))
    private val on = Theme.fill(Theme.ACCENT)

    private val help = listOf(
        "Focused group: left = low-pass, right = high-pass",
        "Repeats the last note value: 1/4 · 1/8 · 1/16 · 1/32 along the strip",
        "Slows the master to a halt (further right = slower)",
        "Chops the master in 1/16ths (further right = deeper)",
        "Lower sample rate and bit depth (further right = rougher)",
        "Jet flanger: the strip sweeps it",
        "Dotted 1/8 echoes; further right = more repeats",
        "Ring modulation: the strip sets the pitch (30 Hz – 2 kHz)",
        "Tremolo on the beat: 1/4 · 1/8 · 1/16 · 1/32 along the strip",
        "Scratch the last beat: move your finger along the strip",
    )

    override fun tick(now: Long): Boolean = true

    override fun lights(l: Lights) {
        l.set("PERFORM", true)
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (down && name == "PERFORM") ui.showHome()
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (slot >= Pattern.PERFORM_FX.size) return
        p.performType = slot
        p.save(ui.ctx)
        AppLog.i("Perform FX: ${Pattern.PERFORM_FX[slot]}")
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        val n = Pattern.PERFORM_FX.size
        for (i in 0 until 16) ui.padLed(i, when {
            i >= n -> 0
            i == p.performType -> Mk3Colors.value(Mk3Colors.WHITE, 3)
            else -> Mk3Colors.value(Mk3Colors.WHITE, 0)
        })
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText("PERFORM FX", 16f, 60f, title)
        l.drawText(Pattern.PERFORM_FX[p.performType], 16f, 96f, big)
        l.drawText(help[p.performType], 16f, 124f, label)
        l.drawText("PERFORM lights the strip for it; touch to play", 16f, 196f, label)
        l.drawText("Pads choose · PERFORM: back", 16f, 216f, label)
        val w = 112f; val h = 44f
        for (i in 0 until 16) {
            val col = i % 4; val row = 3 - i / 4
            val x = 8f + col * (w + 4f); val y = 40f + row * (h + 4f)
            r.drawRoundRect(RectF(x, y, x + w, y + h), 5f, 5f, when { i >= n -> off; i == p.performType -> on; else -> dim })
            if (i < n) r.drawText(Pattern.PERFORM_FX[i], x + w / 2, y + 28f, cell)
        }
    }
}

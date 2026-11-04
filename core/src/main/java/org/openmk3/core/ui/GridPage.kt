package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.openmk3.core.mk3.Mk3Colors

/**
 * Grids (SHIFT + FOLLOW; stays on until FOLLOW is pressed again).
 *  D2 PERFORM: when a launched scene takes over (its end, 1 bar … 1/16, or at once)
 *  D3 ARRANGE: pattern length steps (K4 in PATTERN mode)
 *  D4 STEP: what one STEP pad is (1/4 … 1/64, triplets)
 *  D5 NUDGE: how far EVENTS K1 moves notes
 *  Pads choose the value of the selected grid.
 *  K5 INPUT QUANTIZE: live recorded notes land on the step grid
 */
class GridPage(ui: Ui, private val back: Page) : Page(ui) {

    private enum class Which { PERFORM, ARRANGE, STEP, NUDGE }

    override val customPads = true

    private companion object {
        var which = Which.STEP
        // in 16th steps
        val PERFORM = listOf("SCENE" to 0f, "1 BAR" to 16f, "1/2" to 8f, "1/4" to 4f, "1/8" to 2f, "1/16" to 1f, "OFF" to -1f)
        val ARRANGE = listOf("1 BAR" to 16f, "1/2" to 8f, "1/4" to 4f, "1/8" to 2f, "1/16" to 1f)
        val FINE = listOf("1/4" to 4f, "1/8" to 2f, "1/16" to 1f, "1/32" to 0.5f, "1/64" to 0.25f,
            "1/4T" to 8f / 3f, "1/8T" to 4f / 3f, "1/16T" to 2f / 3f, "1/32T" to 1f / 3f)
    }

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val dOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val cell = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val off = Theme.fill(android.graphics.Color.rgb(32, 32, 40))
    private val on = Theme.fill(Theme.ACCENT)
    private val dim = Theme.fill(android.graphics.Color.rgb(70, 70, 90))

    fun back() = ui.show(back)

    private fun list() = when (which) { Which.ARRANGE -> ARRANGE; Which.PERFORM -> PERFORM; else -> FINE }
    private fun current(): Float = when (which) {
        Which.PERFORM -> ui.engine.pattern.performGrid.toFloat()
        Which.ARRANGE -> ui.arrangeGrid.toFloat()
        Which.STEP -> ui.stepGrid
        Which.NUDGE -> ui.nudgeGrid
    }

    override fun lights(l: Lights) {
        l.set("FOLLOW", true)
        l.set("D2", which == Which.PERFORM)
        l.set("D3", which == Which.ARRANGE)
        l.set("D4", which == Which.STEP)
        l.set("D5", which == Which.NUDGE)
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (!down) return
        when (name) {
            "D2" -> which = Which.PERFORM
            "D3" -> which = Which.ARRANGE
            "D4" -> which = Which.STEP
            "D5" -> which = Which.NUDGE
        }
    }

    private var qAcc = 0
    override fun onKnob(knob: Int, delta: Int) {
        if (knob != 4) return
        qAcc += delta
        if (kotlin.math.abs(qAcc) < 25) return
        qAcc = 0
        ui.inputQuant = !ui.inputQuant
        ui.invalidate()
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        val v = list().getOrNull(slot)?.second ?: return
        when (which) {
            Which.PERFORM -> { ui.engine.pattern.performGrid = v.toInt(); ui.engine.pattern.save(ui.ctx) }
            Which.ARRANGE -> ui.arrangeGrid = v.toInt()
            Which.STEP -> ui.stepGrid = v
            Which.NUDGE -> ui.nudgeGrid = v
        }
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        val items = list()
        val cur = current()
        for (i in 0 until 16) {
            val v = items.getOrNull(i)?.second
            ui.padLed(i, when {
                v == null -> 0
                kotlin.math.abs(v - cur) < 0.001f -> Mk3Colors.value(Mk3Colors.WHITE, 3)
                else -> Mk3Colors.value(Mk3Colors.WHITE, 0)
            })
        }
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText("PERFORM", 180f, 24f, if (which == Which.PERFORM) dOn else dLabel)
        l.drawText("ARRANGE", 300f, 24f, if (which == Which.ARRANGE) dOn else dLabel)
        l.drawText("STEP", 420f, 24f, if (which == Which.STEP) dOn else dLabel)
        r.drawText("NUDGE", 60f, 24f, if (which == Which.NUDGE) dOn else dLabel)
        l.drawText("GRID", 16f, 60f, title)
        val name = items.firstOrNull { kotlin.math.abs(it.second - cur) < 0.001f }?.first ?: "?"
        l.drawText(when (which) {
            Which.PERFORM -> "A launched scene takes over: " + if (name == "SCENE") "at its end" else if (name == "OFF") "at once" else "on the next $name"
            Which.ARRANGE -> "Pattern length steps: $name"
            Which.STEP -> "One STEP pad = $name"
            Which.NUDGE -> "EVENTS position moves by $name"
        }, 16f, 94f, label)
        l.drawText("Pads choose · FOLLOW: back", 16f, 116f, label)
        l.drawText("INPUT QUANTIZE", 60f, 238f, Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER))
        l.drawText(if (ui.inputQuant) "ON" else "OFF", 60f, 260f, Theme.text(16f, if (ui.inputQuant) Theme.ACCENT else Theme.FG, bold = true, align = Paint.Align.CENTER))
        l.drawText("(K5: recorded notes land on the STEP grid)", 130f, 258f, label)
        val w = 112f; val h = 44f
        for (i in 0 until 16) {
            val col = i % 4; val row = 3 - i / 4
            val x = 8f + col * (w + 4f); val y = 40f + row * (h + 4f)
            val it = items.getOrNull(i)
            val fill = when { it == null -> off; kotlin.math.abs(it.second - cur) < 0.001f -> on; else -> dim }
            r.drawRoundRect(RectF(x, y, x + w, y + h), 5f, 5f, fill)
            if (it != null) r.drawText(it.first, x + w / 2, y + 28f, cell)
        }
    }
}

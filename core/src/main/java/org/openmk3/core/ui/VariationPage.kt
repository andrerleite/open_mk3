package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import org.openmk3.core.AppLog
import kotlin.random.Random

/**
 * VARIATION (hold; D1 pins): changes the focused sound's notes in the playing pattern.
 *  D2 HUMANIZE · D3 RANDOM · D8 APPLY (undo with SHIFT + pad 1)
 *  HUMANIZE: K1 velocity range, K2 probability (how many notes change)
 *  RANDOM: K1 density (hits per step), K2 velocity range, K3 note range (KEYBOARD: in scale)
 * Timing variations need off-grid notes (planned with EVENTS editing).
 */
class VariationPage(ui: Ui, private val back: Page) : Page(ui) {

    override val playsPads = true

    private companion object {
        var random = false
        var velRange = 0.3f
        var probability = 0.5f
        var density = 0.3f
        var noteRange = 0
    }

    private var message = ""
    private val p = ui.engine.pattern
    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val ok = Theme.text(15f, Theme.OK)
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val dOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val kName = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val kValue = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)

    fun back() = ui.show(back)

    override fun lights(l: Lights) {
        l.set("VARIATION", true)
        l.set("D2", !random)
        l.set("D3", random)
        l.set("D8")
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (ui.padMode == Ui.PadMode.PAD) ui.selectedSound = slot
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (!down) return
        when (name) {
            "D2" -> random = false
            "D3" -> random = true
            "D8" -> apply()
        }
    }

    override fun onKnob(knob: Int, delta: Int) {
        val d = delta / 500f
        if (!random) when (knob) {
            0 -> velRange = (velRange + d).coerceIn(0f, 1f)
            1 -> probability = (probability + d).coerceIn(0f, 1f)
        } else when (knob) {
            0 -> density = (density + d).coerceIn(0f, 1f)
            1 -> velRange = (velRange + d).coerceIn(0f, 1f)
            2 -> noteRange = (noteRange + if (delta > 0) 1 else -1).coerceIn(0, 24)
        }
    }

    private fun apply() {
        val snd = ui.selectedIndex
        val len = p.length(ui.group)
        val rnd = Random.Default
        if (!random) {
            var n = 0
            for (t in 0 until len) {
                val v = p.vel(snd, t)
                if (v <= 0f || rnd.nextFloat() > probability) continue
                p.setVelocity(snd, t, (v + (rnd.nextFloat() * 2f - 1f) * velRange * 0.5f).coerceIn(0.05f, 1f))
                n++
            }
            message = "Humanized $n notes of ${ui.padLabel()}"
        } else {
            p.clear(snd)
            var n = 0
            val keys = ui.keys
            for (t in 0 until len) {
                // downbeats a little more likely, like a groove
                val chance = density * if (t % 4 == 0) 1.5f else 1f
                if (rnd.nextFloat() >= chance) continue
                val vel = (1f - rnd.nextFloat() * velRange).coerceIn(0.1f, 1f)
                val note = if (noteRange == 0) 60
                    else if (ui.padMode == Ui.PadMode.KEYBOARD || ui.padMode == Ui.PadMode.CHORDS) keys.noteFor(rnd.nextInt(noteRange + 1).coerceAtMost(15))
                    else 60 + rnd.nextInt(-noteRange, noteRange + 1)
                p.recordNote(snd, t, note, vel)
                n++
            }
            message = "Random: $n notes for ${ui.padLabel()}"
        }
        p.save(ui.ctx)
        ui.checkpoint()
        AppLog.i("Variation: $message")
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText("PIN", 60f, 24f, if ("VARIATION" in ui.pinned) dOn else dLabel)
        l.drawText("HUMANIZE", 180f, 24f, if (!random) dOn else dLabel)
        l.drawText("RANDOM", 300f, 24f, if (random) dOn else dLabel)
        r.drawText("APPLY", 420f, 24f, dLabel)
        l.drawText("VARIATION · ${ui.padLabel()}", 16f, 60f, title)
        l.drawText(if (!random) "Changes the velocity of existing notes" else "Replaces the sound's notes with random ones", 16f, 92f, label)
        l.drawText("D8 applies · SHIFT + pad 1 undoes", 16f, 114f, label)
        if (message.isNotEmpty()) l.drawText(message, 16f, 150f, ok)
        fun knob(c: Canvas, i: Int, n: String, v: String) {
            val x = 60f + (i % 4) * 120f
            c.drawText(n, x, 238f, kName); c.drawText(v, x, 260f, kValue)
        }
        if (!random) {
            knob(l, 0, "VELOCITY", "±${(velRange * 50).toInt()}%")
            knob(l, 1, "PROBABILITY", "${(probability * 100).toInt()}%")
        } else {
            knob(l, 0, "DENSITY", "${(density * 100).toInt()}%")
            knob(l, 1, "VELOCITY", "${(velRange * 100).toInt()}%")
            knob(l, 2, "NOTE RANGE", if (noteRange == 0) "OFF" else "$noteRange")
        }
    }
}

package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import org.openmk3.core.AppLog
import org.openmk3.core.audio.Keys
import org.openmk3.core.mk3.Mk3Colors
import kotlin.math.abs

/**
 * ZONES (SHIFT + SAMPLING): the focused sound's multisample zones.
 *  Encoder / ◀ ▶: select a zone · K1 root key · K2 lowest key · K3 highest key
 *  K4 lowest velocity · K5 highest velocity
 *  D5 auto-map (key ranges halfway between the roots) · D6 delete the zone · D7 delete all
 *  Add zones in the BROWSER: D7 adds the selected WAV, D8 adds every WAV of the folder
 *  (root keys from the file names, e.g. "Piano C3" — 60 = C3). Pads play (KEYBOARD to hear them).
 */
class ZonePage(ui: Ui) : Page(ui) {

    override val playsPads = true

    private companion object { var sel = 0 }

    private val zones = ui.engine.zones
    private val snd get() = ui.selectedIndex
    private val acc = IntArray(5)
    private var message = ""

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val ok = Theme.text(15f, Theme.OK)
    private val row = Theme.text(14f, Theme.FG)
    private val rowDim = Theme.text(14f, Theme.DIM)
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val kName = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val kValue = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val selFill = Theme.fill(android.graphics.Color.rgb(60, 60, 80))
    private val keyBg = Theme.fill(android.graphics.Color.rgb(32, 32, 40))

    override fun lights(l: Lights) {
        l.set("SAMPLING", true)
        l.set("D5", "D6", "D7")
        l.set("PAGE LEFT", "PAGE RIGHT")
    }

    private fun changed(msg: String?) {
        if (msg != null) { message = msg; AppLog.i("Zones: $msg") }
        saveAt = android.os.SystemClock.uptimeMillis()
    }

    private var saveAt = 0L
    override fun tick(now: Long): Boolean {
        if (saveAt != 0L && now - saveAt > 1500) { zones.save(ui.ctx); saveAt = 0L }
        return false
    }
    override fun onExit() { if (saveAt != 0L) zones.save(ui.ctx) }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (!down) return
        val n = zones.of(snd).size
        when (name) {
            "SAMPLING" -> ui.showHome()
            "PAGE LEFT" -> sel = (sel - 1).coerceAtLeast(0)
            "PAGE RIGHT" -> sel = (sel + 1).coerceAtMost(maxOf(0, n - 1))
            "D5" -> { zones.autoMap(snd); changed("Auto-mapped $n zones") }
            "D6" -> if (sel < n) { zones.remove(snd, sel); sel = sel.coerceAtMost(maxOf(0, n - 2)); changed("Zone deleted") }
            "D7" -> { zones.clear(snd); sel = 0; changed("All zones deleted") }
            "BROWSER" -> ui.show(BrowserPage(ui))
        }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (ui.padMode == Ui.PadMode.PAD) ui.selectedSound = slot
    }

    override fun onEncoder(delta: Int) {
        val n = zones.of(snd).size
        if (n > 0) sel = (sel + delta).coerceIn(0, n - 1)
    }

    override fun onKnob(knob: Int, delta: Int) {
        if (knob > 4 || sel >= zones.of(snd).size) return
        acc[knob] += delta
        val need = if (knob >= 3) 6 else 20
        if (abs(acc[knob]) < need) return
        val d = if (acc[knob] > 0) 1 else -1
        acc[knob] = 0
        zones.update(snd, sel) { z ->
            when (knob) {
                0 -> z.root += d
                1 -> { z.lo += d; if (z.lo > z.hi) z.hi = z.lo }
                2 -> z.hi = maxOf(z.lo, z.hi + d)
                3 -> { z.vlo += d; if (z.vlo > z.vhi) z.vhi = z.vlo }
                4 -> z.vhi = maxOf(z.vlo, z.vhi + d)
            }
        }
        changed(null)
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        r.drawText("AUTO-MAP", 60f, 24f, dLabel)
        r.drawText("DELETE", 180f, 24f, dLabel)
        r.drawText("DEL ALL", 300f, 24f, dLabel)
        val list = zones.of(snd)
        l.drawText("ZONES · ${ui.padLabel()}", 16f, 60f, title)
        if (list.isEmpty()) {
            l.drawText("No zones: the pad plays its own sample", 16f, 92f, label)
            l.drawText("BROWSER: D7 adds a WAV, D8 a whole folder", 16f, 114f, label)
        } else {
            val first = (sel / 6) * 6
            for (k in 0 until 6) {
                val i = first + k
                val z = list.getOrNull(i) ?: break
                val y = 74f + k * 24f
                if (i == sel) l.drawRect(8f, y, 472f, y + 22f, selFill)
                l.drawText("${i + 1}", 16f, y + 16f, rowDim)
                l.drawText(z.sample.name.take(20), 40f, y + 16f, row)
                l.drawText("${Keys.noteName(z.lo)}–${Keys.noteName(z.hi)}", 260f, y + 16f, row)
                l.drawText("v${z.vlo}–${z.vhi}", 380f, y + 16f, rowDim)
            }
        }
        if (message.isNotEmpty()) l.drawText(message, 16f, 226f, ok)
        // knobs
        list.getOrNull(sel)?.let { z ->
            fun knob(i: Int, n: String, v: String) { val x = 60f + i * 120f; val c = if (i < 4) l else r; c.drawText(n, if (i < 4) x else x - 480f, 244f, kName); c.drawText(v, if (i < 4) x else x - 480f, 264f, kValue) }
            knob(0, "ROOT", Keys.noteName(z.root)); knob(1, "LOW KEY", Keys.noteName(z.lo)); knob(2, "HIGH KEY", Keys.noteName(z.hi))
            knob(3, "LOW VEL", "${z.vlo}"); knob(4, "HIGH VEL", "${z.vhi}")
        }
        // right: key map (C0..C8) with the zones as bars
        val x0 = 16f; val w = 448f; val lo = 24; val hi = 108
        fun kx(n: Int) = x0 + (n - lo).coerceIn(0, hi - lo) * w / (hi - lo)
        r.drawRect(x0, 50f, x0 + w, 206f, keyBg)
        for ((i, z) in list.withIndex()) {
            val y = 54f + (i % 12) * 12f
            val c = Theme.fill(Mk3Colors.rgb(ui.soundColour(snd)))
            c.alpha = if (i == sel) 255 else 110
            r.drawRect(kx(z.lo), y, maxOf(kx(z.hi + 1), kx(z.lo) + 3f), y + 9f, c)
            r.drawRect(kx(z.root), y - 1f, kx(z.root) + 2f, y + 10f, Theme.fill(Theme.FG))
        }
        for (oct in 1..8) { val n = oct * 12 + 12; if (n in lo..hi) r.drawText(Keys.noteName(n), kx(n), 222f, rowDim) }
    }
}

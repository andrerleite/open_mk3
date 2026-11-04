package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import org.openmk3.core.AppLog
import org.openmk3.core.audio.AudioEngine
import org.openmk3.core.audio.Keys
import org.openmk3.core.mk3.Mk3Colors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ZONE (SAMPLING D4, or SHIFT + SAMPLING): the focused sound's multisample zones.
 *  Encoder: select a zone · ◀ ▶: pages MAP · PLAY RANGE · LOOP · TUNE / MIX · ENVELOPE
 *  MAP        K1 root key · K2 lowest key · K3 highest key · K4 lowest velocity · K5 highest
 *  PLAY RANGE K1 start · K2 end (of the zone's sample)
 *  LOOP       K1 loop (as the sound / off / on) · K2 loop start · K3 loop end · K4 crossfade
 *  TUNE / MIX K1 tune · K2 level · K3 pan
 *  ENVELOPE   K1 attack · K2 decay (as the sound, or the zone's own)
 *  D1–D3 RECORD / EDIT / SLICE · D5 auto-map · D6 delete the zone · D7 delete all · D8 add (BROWSER)
 *  Add zones in the BROWSER: D7 adds the selected WAV, D8 adds every WAV of the folder
 *  (root keys from the file names, e.g. "Piano C3" — 60 = C3). Pads play (KEYBOARD to hear them).
 */
class ZonePage(ui: Ui) : Page(ui) {

    override val playsPads = true

    private companion object {
        var sel = 0
        var page = 0
        val PAGES = listOf("MAP", "PLAY RANGE", "LOOP", "TUNE / MIX", "ENVELOPE")
    }

    private val zones = ui.engine.zones
    private val snd get() = ui.selectedIndex
    private val acc = IntArray(8)
    private var message = ""
    private var shift = false
    private val wave = WaveView()

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val ok = Theme.text(15f, Theme.OK)
    private val row = Theme.text(14f, Theme.FG)
    private val rowDim = Theme.text(14f, Theme.DIM)
    private val tabText = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val tabOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val dLabel = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val kName = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val kValue = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val pageText = Theme.text(14f, Theme.DIM, align = Paint.Align.RIGHT)
    private val selFill = Theme.fill(android.graphics.Color.rgb(60, 60, 80))
    private val keyBg = Theme.fill(android.graphics.Color.rgb(32, 32, 40))
    private val sep = Theme.fill(android.graphics.Color.rgb(50, 50, 60))

    override fun lights(l: Lights) {
        l.set("SAMPLING", true)
        l.set("D1", "D2", "D3")
        l.set("D4", true)
        l.set("D5", "D6", "D7", "D8")
        l.set("PAGE LEFT", page > 0)
        l.set("PAGE RIGHT", page < PAGES.size - 1)
        l.set("SHIFT", shift)
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
        if (name == "SHIFT") { shift = down; return }
        if (!down) return
        val n = zones.of(snd).size
        when (name) {
            "SAMPLING" -> ui.showHome()
            "D1" -> ui.show(SamplingPage(ui, 0))
            "D2" -> ui.show(SamplingPage(ui, 1))
            "D3" -> ui.show(SamplingPage(ui, 2))
            "PAGE LEFT" -> page = (page - 1).coerceAtLeast(0)
            "PAGE RIGHT" -> page = (page + 1).coerceAtMost(PAGES.size - 1)
            "D5" -> { zones.autoMap(snd); changed("Auto-mapped $n zones") }
            "D6" -> if (sel < n) { zones.remove(snd, sel); sel = sel.coerceAtMost(maxOf(0, n - 2)); changed("Zone deleted") }
            "D7" -> { zones.clear(snd); sel = 0; changed("All zones deleted") }
            "D8", "BROWSER" -> ui.show(BrowserPage(ui))
        }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (ui.padMode == Ui.PadMode.PAD) ui.selectedSound = slot
    }

    override fun onEncoder(delta: Int) {
        val n = zones.of(snd).size
        if (n > 0) sel = (sel + delta).coerceIn(0, n - 1)
    }

    /** Stepped knobs: one step per notch-sized turn. */
    private fun steps(knob: Int, delta: Int, need: Int): Int {
        acc[knob] += delta
        if (abs(acc[knob]) < need) return 0
        val d = if (acc[knob] > 0) 1 else -1
        acc[knob] = 0
        return d
    }

    override fun onKnob(knob: Int, delta: Int) {
        if (sel >= zones.of(snd).size) return
        val fine = if (shift) 0.1f else 1f
        val frac = delta * fine / 1000f
        zones.update(snd, sel) { z ->
            when (page) {
                0 -> {
                    val d = steps(knob, delta, if (knob >= 3) 6 else 20)
                    if (d != 0) when (knob) {
                        0 -> z.root += d
                        1 -> { z.lo += d; if (z.lo > z.hi) z.hi = z.lo }
                        2 -> z.hi = maxOf(z.lo, z.hi + d)
                        3 -> { z.vlo += d; if (z.vlo > z.vhi) z.vhi = z.vlo }
                        4 -> z.vhi = maxOf(z.vlo, z.vhi + d)
                    }
                }
                1 -> when (knob) {
                    0 -> z.start = (z.start + frac).coerceIn(0f, z.end - 0.001f)
                    1 -> z.end = (z.end + frac).coerceIn(z.start + 0.001f, 1f)
                }
                2 -> when (knob) {
                    0 -> { val d = steps(knob, delta, 25); if (d != 0) z.loop = (z.loop + d).coerceIn(-1, 1) }
                    1 -> { z.lstart = (z.lstart + frac).coerceIn(0f, z.lend - 0.001f); if (z.loop < 0) z.loop = 1 }
                    2 -> { z.lend = (z.lend + frac).coerceIn(z.lstart + 0.001f, 1f); if (z.loop < 0) z.loop = 1 }
                    3 -> { z.xfade = (z.xfade + frac).coerceIn(0f, 1f); if (z.loop < 0) z.loop = 1 }
                }
                3 -> when (knob) {
                    0 -> z.tune = (z.tune + delta * (if (shift) 0.01f else 0.05f)).coerceIn(-48f, 48f)
                    1 -> z.gain = (z.gain + delta * (if (shift) 0.01f else 0.1f)).coerceIn(-60f, 12f)
                    2 -> z.pan = (z.pan + delta * fine / 200f).coerceIn(-1f, 1f)
                }
                else -> when (knob) {
                    // turning down past zero returns to "as the sound"
                    0 -> z.attack = envStep(z.attack, delta)
                    1 -> z.decay = envStep(z.decay, delta)
                }
            }
        }
        changed(null)
    }

    private fun envStep(v: Float, delta: Int): Float {
        if (v < 0f) return if (delta > 0) 0.001f else -1f
        val nv = v * Math.pow(1.0 + (if (shift) 0.002 else 0.01), delta.toDouble()).toFloat() + delta * 0.0002f
        return if (nv <= 0.0005f && delta < 0) -1f else nv.coerceIn(0.0005f, 20f)
    }

    private fun secsOf(f: Float, frames: Int) = "%.3f s".format(f * frames / AudioEngine.SR.toDouble())
    private fun envText(v: Float) = if (v < 0f) "AS SOUND" else if (v < 1f) "${(v * 1000).roundToInt()} ms" else "%.2f s".format(v)

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        for ((i, t) in listOf("RECORD", "EDIT", "SLICE", "ZONE").withIndex()) l.drawText(t, 60f + i * 120f, 26f, if (i == 3) tabOn else tabText)
        r.drawText("AUTO-MAP", 60f, 24f, dLabel)
        r.drawText("DELETE", 180f, 24f, dLabel)
        r.drawText("DEL ALL", 300f, 24f, dLabel)
        r.drawText("ADD…", 420f, 24f, dLabel)
        l.drawRect(0f, 42f, 480f, 43f, sep)
        r.drawRect(0f, 42f, 480f, 43f, sep)
        val list = zones.of(snd)
        l.drawText("ZONES · ${ui.padLabel()}", 16f, 66f, title)
        l.drawText("${PAGES[page]}  ${page + 1}/${PAGES.size}", 470f, 66f, pageText)
        if (list.isEmpty()) {
            l.drawText("No zones: the pad plays its own sample", 16f, 98f, label)
            l.drawText("D8 / BROWSER: D7 adds a WAV, D8 a whole folder", 16f, 120f, label)
            l.drawText("SLICE → APPLY to a sound also makes zones", 16f, 142f, label)
        } else {
            val first = (sel / 5) * 5
            for (k in 0 until 5) {
                val i = first + k
                val z = list.getOrNull(i) ?: break
                val y = 78f + k * 24f
                if (i == sel) l.drawRect(8f, y, 472f, y + 22f, selFill)
                l.drawText("${i + 1}", 16f, y + 16f, rowDim)
                l.drawText(z.sample.name.take(20), 40f, y + 16f, row)
                l.drawText("${Keys.noteName(z.lo)}–${Keys.noteName(z.hi)}", 260f, y + 16f, row)
                l.drawText("v${z.vlo}–${z.vhi}", 380f, y + 16f, rowDim)
            }
        }
        if (message.isNotEmpty()) l.drawText(message, 16f, 222f, ok)
        val z = list.getOrNull(sel)
        if (z != null) {
            val fr = z.sample.frames
            fun knob(i: Int, n: String, v: String) {
                val c = if (i < 4) l else r
                val x = 60f + (i % 4) * 120f
                c.drawText(n, x, 248f, kName); c.drawText(v, x, 268f, kValue)
            }
            when (page) {
                0 -> { knob(0, "ROOT", Keys.noteName(z.root)); knob(1, "LOW KEY", Keys.noteName(z.lo)); knob(2, "HIGH KEY", Keys.noteName(z.hi))
                       knob(3, "LOW VEL", "${z.vlo}"); knob(4, "HIGH VEL", "${z.vhi}") }
                1 -> { knob(0, "START", secsOf(z.start, fr)); knob(1, "END", secsOf(z.end, fr)) }
                2 -> { knob(0, "LOOP", when (z.loop) { -1 -> "AS SOUND"; 0 -> "OFF"; else -> "ON" })
                       knob(1, "LOOP START", secsOf(z.lstart, fr)); knob(2, "LOOP END", secsOf(z.lend, fr)); knob(3, "XFADE", "${(z.xfade * 100).roundToInt()}%") }
                3 -> { knob(0, "TUNE", "%+.2f st".format(z.tune)); knob(1, "LEVEL", "%+.1f dB".format(z.gain)); knob(2, "PAN", if (abs(z.pan) < 0.005f) "C" else if (z.pan < 0) "L${(-z.pan * 100).roundToInt()}" else "R${(z.pan * 100).roundToInt()}") }
                else -> { knob(0, "ATTACK", envText(z.attack)); knob(1, "DECAY", envText(z.decay)) }
            }
        }
        if (z != null && (page == 1 || page == 2)) {
            // the zone's sample with its play range (page 2) or loop (page 3)
            val fr = z.sample.frames
            wave.set(z.sample.data, z.sample.channels)
            wave.setColour(Mk3Colors.rgb(ui.soundColour(snd)))
            if (page == 1) wave.draw(r, 16f, 56f, 210f, selA = (z.start * fr).toInt(), selB = (z.end * fr).toInt(), markers = intArrayOf((z.start * fr).toInt(), (z.end * fr).toInt()))
            else wave.draw(r, 16f, 56f, 210f, selA = (z.start * fr).toInt(), selB = (z.end * fr).toInt(),
                loop = if (z.loop == 1) intArrayOf((z.lstart * fr).toInt(), (z.lend * fr).toInt()) else null)
            return
        }
        // right: key map (C0..C8) with the zones as bars
        val x0 = 16f; val w = 448f; val lo = 24; val hi = 108
        fun kx(n: Int) = x0 + (n - lo).coerceIn(0, hi - lo) * w / (hi - lo)
        r.drawRect(x0, 50f, x0 + w, 206f, keyBg)
        for ((i, zn) in list.withIndex()) {
            val y = 54f + (i % 12) * 12f
            val c = Theme.fill(Mk3Colors.rgb(ui.soundColour(snd)))
            c.alpha = if (i == sel) 255 else 110
            r.drawRect(kx(zn.lo), y, maxOf(kx(zn.hi + 1), kx(zn.lo) + 3f), y + 9f, c)
            r.drawRect(kx(zn.root), y - 1f, kx(zn.root) + 2f, y + 10f, Theme.fill(Theme.FG))
        }
        for (oct in 1..8) { val n = oct * 12 + 12; if (n in lo..hi) r.drawText(Keys.noteName(n), kx(n), 222f, rowDim) }
    }
}

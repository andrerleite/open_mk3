package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import org.openmk3.core.audio.Sample
import org.openmk3.core.audio.SoundParams
import org.openmk3.core.audio.SoundParams.Companion.ATT
import org.openmk3.core.audio.SoundParams.Companion.CUT
import org.openmk3.core.audio.SoundParams.Companion.DEC
import org.openmk3.core.audio.SoundParams.Companion.END
import org.openmk3.core.audio.SoundParams.Companion.ENV
import org.openmk3.core.audio.SoundParams.Companion.FTYPE
import org.openmk3.core.audio.SoundParams.Companion.HOLD
import org.openmk3.core.audio.SoundParams.Companion.PAN
import org.openmk3.core.audio.SoundParams.Companion.RES
import org.openmk3.core.audio.SoundParams.Companion.REV
import org.openmk3.core.audio.SoundParams.Companion.START
import org.openmk3.core.audio.SoundParams.Companion.TUNE
import org.openmk3.core.audio.SoundParams.Companion.VOL
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Sampler parameters of the focused sound, a page of eight knobs at a time.
 *
 *  ◀ ▶ or D1–D3 = page (VOICE / ENVELOPE / FILTER) · knobs = parameters (SHIFT = fine)
 *  SHIFT + touch knob = back to default · PADS = focus and play a sound
 *  PLUG-IN = back
 */
class SoundPage(ui: Ui) : Page(ui) {

    override val playsPads = true

    private class PageDef(val name: String, val knobs: IntArray)

    private companion object {
        val PAGES = listOf(
            PageDef("VOICE", intArrayOf(TUNE, VOL, PAN, -1, START, END, REV, -1)),
            PageDef("ENVELOPE", intArrayOf(ENV, ATT, HOLD, DEC, -1, -1, -1, -1)),
            PageDef("FILTER", intArrayOf(FTYPE, CUT, RES, -1, -1, -1, -1, -1)),
        )
        var lastPage = 0
    }

    private var pageIdx = lastPage
    private val page get() = PAGES[pageIdx]
    private var shift = false
    private var dirtySince = 0L
    private val params = ui.engine.params
    private val slot get() = ui.selectedSound

    // waveform cache of the focused sample
    private var waveOf: Sample? = null
    private val wave = FloatArray(448)

    private val tab = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val tabOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val kName = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val kValue = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val big = Theme.text(22f, Theme.FG, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val sep = Theme.fill(Color.rgb(50, 50, 60))
    private val barBg = Theme.fill(Theme.CELL)
    private val barFg = Theme.fill(Theme.ACCENT)
    private val waveIn = Theme.fill(Color.rgb(90, 200, 120))
    private val waveOut = Theme.fill(Color.rgb(50, 70, 60))
    private val marker = Theme.fill(Theme.ACCENT)
    private val curve = Theme.stroke(Theme.ACCENT, 2f)
    private val grid = Theme.stroke(Color.rgb(50, 50, 60), 1f)

    override fun onEnter() {
        ui.buttonLed("PLUG-IN", 0x7F)
    }

    override fun onExit() {
        lastPage = pageIdx
        if (dirtySince != 0L) save()
    }

    private fun changed() {
        if (dirtySince == 0L) dirtySince = SystemClock.uptimeMillis()
    }

    private fun save() {
        params.save(ui.ctx)
        dirtySince = 0L
    }

    override fun tick(now: Long): Boolean {
        if (dirtySince != 0L && now - dirtySince > 1500) save()
        return false
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (name == "SHIFT") { shift = down; return }
        if (!down) return
        val e = ui.engine
        when (name) {
            "PAGE LEFT" -> pageIdx = (pageIdx + PAGES.size - 1) % PAGES.size
            "PAGE RIGHT" -> pageIdx = (pageIdx + 1) % PAGES.size
            "D1", "D2", "D3" -> pageIdx = name[1] - '1'
            "PLAY" -> if (e.playing) e.transportStop() else e.transportPlay()
            "STOP" -> e.transportStop()
            "RESTART" -> e.transportRestart()
            "PLUG-IN" -> ui.showHome()
            "BROWSER" -> ui.show(BrowserPage(ui))
            "SAMPLING" -> ui.show(SamplingPage(ui))
        }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        ui.selectedSound = slot
    }

    override fun onKnob(knob: Int, delta: Int) {
        val p = page.knobs.getOrNull(knob) ?: return
        if (p < 0) return
        params.nudge(slot, p, delta, shift)
        changed()
    }

    override fun onKnobTouch(knob: Int, touched: Boolean) {
        if (!touched || !shift) return
        val p = page.knobs.getOrNull(knob) ?: return
        val d = SoundParams.DEFS[p] ?: return
        params[slot, p] = d.default
        changed()
    }

    override fun onEncoder(delta: Int) {
        val d = if (delta > 0) 1 else PAGES.size - 1
        pageIdx = (pageIdx + d) % PAGES.size
    }

    // =====================================================================================

    private fun knobCell(c: Canvas, i: Int) {
        val p = page.knobs[i]
        if (p < 0) return
        val d = SoundParams.DEFS[p] ?: return
        val x = 60f + (i % 4) * 120f
        c.drawText(d.name, x, 232f, kName)
        c.drawText(params.display(slot, p), x, 252f, kValue)
        val v = params[slot, p]
        val frac = if (d.log) (ln(v / d.min) / ln(d.max / d.min)) else (v - d.min) / (d.max - d.min)
        c.drawRect(x - 44f, 260f, x + 44f, 265f, barBg)
        c.drawRect(x - 44f, 260f, x - 44f + 88f * frac.coerceIn(0f, 1f), 265f, barFg)
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        // ---- left ----
        l.drawColor(Theme.BG)
        for ((i, pg) in PAGES.withIndex()) l.drawText(pg.name, 60f + i * 120f, 26f, if (i == pageIdx) tabOn else tab)
        l.drawRect(0f, 40f, 480f, 41f, sep)
        l.drawText("SAMPLER · PAD ${slot + 1}", 16f, 74f, title)
        l.drawText(ellipsize(ui.engine.kit[slot].name, 30), 16f, 106f, big)
        l.drawText(ellipsize(ui.engine.kit[slot].info, 54), 16f, 130f, label)
        l.drawText("◀ ▶ pages · SHIFT fine · SHIFT + touch knob resets", 16f, 190f, label)
        l.drawRect(0f, 214f, 480f, 215f, sep)
        for (i in 0 until 4) knobCell(l, i)

        // ---- right ----
        r.drawColor(Theme.BG)
        r.drawRect(0f, 214f, 480f, 215f, sep)
        for (i in 4 until 8) knobCell(r, i)
        when (pageIdx) {
            0 -> drawWave(r)
            1 -> drawEnvelope(r)
            2 -> drawFilter(r)
        }
    }

    private fun drawWave(c: Canvas) {
        val s = ui.engine.kit[slot]
        if (s !== waveOf) {
            waveOf = s
            val n = s.frames
            for (col in wave.indices) {
                val a = (col.toLong() * n / wave.size).toInt()
                val b = max(a + 1, ((col + 1).toLong() * n / wave.size).toInt())
                var pk = 0f
                val stride = max(1, (b - a) / 64)
                var f = a
                while (f < min(b, n)) { pk = max(pk, abs(s.data[f * s.channels])); f += stride }
                wave[col] = pk
            }
        }
        val top = 20f; val bot = 190f; val mid = (top + bot) / 2; val half = (bot - top) / 2
        val sc = (params[slot, START] * wave.size).toInt()
        val ec = (params[slot, END] * wave.size).toInt()
        for (col in wave.indices) {
            val h = max(1f, wave[col].coerceAtMost(1f) * half)
            c.drawRect(16f + col, mid - h, 17f + col, mid + h, if (col in sc until ec) waveIn else waveOut)
        }
        c.drawRect(15f + sc, top - 6f, 17f + sc, bot + 6f, marker)
        c.drawRect(15f + ec, top - 6f, 17f + ec, bot + 6f, marker)
        if (params[slot, REV] > 0.5f) c.drawText("REVERSE", 16f, 206f, label)
    }

    private fun drawEnvelope(c: Canvas) {
        val top = 30f; val bot = 190f; val x0 = 24f; val w = 432f
        c.drawLine(x0, bot, x0 + w, bot, grid)
        val path = Path()
        if (params[slot, ENV] < 0.5f) {
            path.moveTo(x0, bot); path.lineTo(x0, top); path.lineTo(x0 + w, top); path.lineTo(x0 + w, bot)
            c.drawPath(path, curve)
            c.drawText("ONE-SHOT: plays the whole sample (start to end)", x0, 206f, label)
            return
        }
        val a = params[slot, ATT]; val h = params[slot, HOLD]; val d = params[slot, DEC]
        val total = a + h + d
        fun x(t: Float) = x0 + w * (t / total)
        path.moveTo(x0, bot)
        path.lineTo(x(a), top)
        path.lineTo(x(a + h), top)
        for (k in 1..40) {
            val t = d * k / 40f
            val lvl = Math.pow(1e-4, (t / d).toDouble()).toFloat() // to -80 dB
            path.lineTo(x(a + h + t), bot - (bot - top) * lvl)
        }
        c.drawPath(path, curve)
        c.drawText("total %.2f s".format(total), x0, 206f, label)
    }

    private fun drawFilter(c: Canvas) {
        val top = 20f; val bot = 190f; val x0 = 24f; val w = 432f
        val type = params[slot, FTYPE].toInt()
        val fc = params[slot, CUT]
        val k = 2f - 1.96f * params[slot, RES]
        for (f in intArrayOf(100, 1000, 10000)) {
            val gx = x0 + w * (ln(f / 20f) / ln(1000f))
            c.drawLine(gx, top, gx, bot, grid)
        }
        val path = Path()
        for (i in 0..216) {
            val f = 20f * Math.pow(1000.0, i / 216.0).toFloat()
            val wr = f / fc
            val den = sqrt((1 - wr * wr) * (1 - wr * wr) + (k * wr) * (k * wr))
            val mag = when (type) {
                1 -> 1f / den
                2 -> k * wr / den
                3 -> wr * wr / den
                else -> 1f
            }
            val db = (20f * kotlin.math.log10(max(mag, 1e-4f))).coerceIn(-48f, 24f)
            val y = top + (bot - top) * (24f - db) / 72f
            val x = x0 + w * i / 216f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, curve)
    }

    private fun ellipsize(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}

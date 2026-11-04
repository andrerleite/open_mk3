package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import org.openmk3.core.audio.Pattern
import org.openmk3.core.audio.SoundParams
import org.openmk3.core.audio.soundLabel
import org.openmk3.core.mk3.Mk3Colors
import kotlin.math.roundToInt

/**
 * MIXER (mix mode): eight channel strips, as on the hardware.
 *  4D encoder ▲ groups · ▼ the focused group's sounds (◀ ▶ page buttons: 1–8 / 9–16)
 *  D1–D8: focus the channel above · MUTE / SOLO + D1–D8: mute / solo it
 *  Knobs: the shown parameter of each channel · encoder push: VOLUME → PAN → SEND A → SEND B (groups)
 *  Encoder turn: that parameter of the focused channel · SHIFT + encoder: master volume
 *  SHIFT + D7 / D8: the returns' effects (PLUG-IN) · returns start as a reverb (A) and a delay (B)
 *  Pads / GROUP buttons: focus (the strips follow) · MIXER: back
 */
class MixerPage(ui: Ui) : Page(ui) {

    override val handlesTransport = true

    override val playsPads = true

    private companion object {
        var groupsLevel = false
        var panMode = false
        var sendMode = -1  // 0 / 1: knobs set the groups' send to return A / B
        var soundPage = 0
    }

    private val p = ui.engine.pattern
    private val params = ui.engine.params
    private var shift = false
    private var muteHeld = false
    private var soloHeld = false
    private var dirtySince = 0L

    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val dOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val name = Theme.text(14f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val nameDim = Theme.text(14f, Theme.DIM, align = Paint.Align.CENTER)
    private val value = Theme.text(14f, Theme.FG, align = Paint.Align.CENTER)
    private val title = Theme.text(14f, Theme.ACCENT, bold = true)
    private val barBg = Theme.fill(Theme.CELL)
    private val sel = Theme.fill(android.graphics.Color.rgb(60, 60, 80))

    /** Channel [i] (0..7) on the current level: group index or sound index. */
    private fun channel(i: Int) = if (groupsLevel) i else ui.snd(soundPage * 8 + i)

    private fun changed() { if (dirtySince == 0L) dirtySince = SystemClock.uptimeMillis() }

    // ---- VU meters: peak level per channel, falling ~24 dB/s, with a 1.5 s peak hold ----
    private val raw = FloatArray(Pattern.SOUNDS + Pattern.GROUPS)
    private val level = FloatArray(Pattern.SOUNDS + Pattern.GROUPS) { -60f }
    private val hold = FloatArray(Pattern.SOUNDS + Pattern.GROUPS) { -60f }
    private val holdAt = LongArray(Pattern.SOUNDS + Pattern.GROUPS)
    private var masterLevel = -60f
    private var lastMeter = 0L

    private fun meterIndex(ch: Int) = if (groupsLevel) Pattern.SOUNDS + ch else ch

    override fun tick(now: Long): Boolean {
        if (dirtySince != 0L && now - dirtySince > 1500) {
            p.save(ui.ctx); params.save(ui.ctx); ui.checkpoint(); dirtySince = 0L
        }
        val dt = if (lastMeter == 0L) 0.033f else ((now - lastMeter) / 1000f).coerceIn(0f, 0.2f)
        lastMeter = now
        ui.engine.meters(raw)
        for (i in raw.indices) {
            val db = if (raw[i] > 1e-5f) (20f * kotlin.math.log10(raw[i])) else -60f
            level[i] = if (db > level[i]) db else (level[i] - 24f * dt).coerceAtLeast(-60f)
            if (db >= hold[i] || now - holdAt[i] > 1500) { hold[i] = maxOf(db, level[i]); holdAt[i] = now }
        }
        val m = ui.engine.meter.let { if (it > 1e-5f) 20f * kotlin.math.log10(it) else -60f }
        masterLevel = if (m > masterLevel) m else (masterLevel - 24f * dt).coerceAtLeast(-60f)
        return true  // meters move
    }

    private val meterBg = Theme.fill(android.graphics.Color.rgb(24, 24, 30))
    private val meterGreen = Theme.fill(android.graphics.Color.rgb(60, 200, 90))
    private val meterYellow = Theme.fill(android.graphics.Color.rgb(230, 200, 60))
    private val meterRed = Theme.fill(android.graphics.Color.rgb(235, 70, 60))
    private val meterHold = Theme.fill(Theme.FG)

    /** Vertical meter, -48..+6 dB over [top, bot]; green to -12 dB, yellow to 0 dB, red above. */
    private fun drawMeter(c: Canvas, x0: Float, x1: Float, top: Float, bot: Float, db: Float, peak: Float) {
        c.drawRect(x0, top, x1, bot, meterBg)
        fun y(d: Float) = bot - (bot - top) * ((d + 48f) / 54f).coerceIn(0f, 1f)
        val yl = y(db)
        val yg = y(-12f); val yy = y(0f)
        if (yl < bot) c.drawRect(x0, maxOf(yl, yg), x1, bot, meterGreen)
        if (yl < yg) c.drawRect(x0, maxOf(yl, yy), x1, yg, meterYellow)
        if (yl < yy) c.drawRect(x0, yl, x1, yy, meterRed)
        if (peak > -47f) { val yp = y(peak); c.drawRect(x0, yp - 1f, x1, yp + 1f, if (peak > 0f) meterRed else meterHold) }
    }

    override fun onExit() {
        if (dirtySince != 0L) { p.save(ui.ctx); params.save(ui.ctx) }
    }

    override fun lights(l: Lights) {
        l.set("MIXER", true)
        for (i in 0 until 8) {
            val ch = channel(i)
            l.set("D${i + 1}", if (groupsLevel) ch == ui.group else ch == ui.selectedIndex)
        }
        l.set("MUTE", muteHeld); l.set("SOLO", soloHeld)
        if (!groupsLevel) { l.set("PAGE LEFT", soundPage == 1); l.set("PAGE RIGHT", soundPage == 0) }
        l.set("PLAY", ui.engine.playing)
        l.set("STOP")
        l.set("SHIFT", shift)
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        when (name) {
            "SHIFT" -> { shift = down; return }
            "MUTE" -> { muteHeld = down; return }
            "SOLO" -> { soloHeld = down; return }
        }
        if (!down) return
        val e = ui.engine
        if (name != null && name.length == 2 && name[0] == 'D' && name[1] in '1'..'8') {
            val i = name[1] - '1'
            val ch = channel(i)
            when {
                shift && (i == 6 || i == 7) -> { if (org.openmk3.core.Edition.full) { SoundPageNav.openReturn = i - 6; ui.show(SoundPage(ui)) } }
                muteHeld -> if (groupsLevel) p.groupMute[ch] = !p.groupMute[ch] else p.mute[ch] = !p.mute[ch]
                soloHeld -> if (groupsLevel) p.groupSolo[ch] = !p.groupSolo[ch] else p.solo[ch] = !p.solo[ch]
                groupsLevel -> ui.selectGroup(ch)
                else -> ui.selectedSound = ch % 16
            }
            changed()
            return
        }
        when (name) {
            "ENCODER UP" -> groupsLevel = true
            "ENCODER DOWN" -> { groupsLevel = false; soundPage = ui.selectedSound / 8 }
            "ENCODER PUSH" -> {
                // VOLUME → PAN → SEND A → SEND B (sends: groups only)
                val mode = when { sendMode >= 0 -> 2 + sendMode; panMode -> 1; else -> 0 }
                val next = (mode + 1) % (if (groupsLevel) 4 else 2)
                panMode = next == 1; sendMode = if (next >= 2) next - 2 else -1
            }
            "PAGE LEFT" -> soundPage = 0
            "PAGE RIGHT" -> soundPage = 1
            "PLAY" -> if (e.playing) e.transportStop() else e.transportPlay()
            "STOP" -> e.stopPressed()
            "MIXER" -> ui.showHome()
            "BROWSER" -> ui.show(BrowserPage(ui))
            "PLUG-IN" -> if (org.openmk3.core.Edition.full) ui.show(SoundPage(ui))
            "SAMPLING" -> ui.show(SamplingPage(ui))
        }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (ui.padMode != Ui.PadMode.PAD) return
        ui.selectedSound = slot
        soundPage = slot / 8
    }

    override fun onKnob(knob: Int, delta: Int) {
        val ch = channel(knob)
        val k = if (shift) 0.1f else 1f
        if (sendMode >= 0) {
            if (groupsLevel) p.setGroupSend(ch, sendMode, p.groupSend(ch, sendMode) + delta * 0.002f * k)
        } else if (groupsLevel) {
            if (panMode) p.setGroupPan(ch, p.groupPan(ch) + delta * 0.002f * k)
            else p.setGroupVolume(ch, p.groupVolume(ch) + delta * 0.05f * k)
        } else {
            if (panMode) params[ch, SoundParams.PAN] = (params[ch, SoundParams.PAN] + delta * 0.002f * k).coerceIn(-1f, 1f)
            else params[ch, SoundParams.VOL] = (params[ch, SoundParams.VOL] + delta * 0.05f * k).coerceIn(-60f, 12f)
            ui.autoWrite(ch, if (panMode) SoundParams.PAN else SoundParams.VOL)
        }
        changed()
    }

    override fun onKnobTouch(knob: Int, touched: Boolean) {
        if (!touched || !shift) return
        val ch = channel(knob)  // SHIFT + touch: back to 0 dB / centre
        if (groupsLevel) { if (panMode) p.setGroupPan(ch, 0f) else p.setGroupVolume(ch, 0f) }
        else { if (panMode) params[ch, SoundParams.PAN] = 0f else params[ch, SoundParams.VOL] = 0f }
        changed()
    }

    /** The encoder: the shown parameter of the focused channel. */
    override fun onEncoder(delta: Int) {
        // SHIFT + encoder: the master volume
        if (shift) { ui.engine.masterGain = (ui.engine.masterGain + delta * 0.02f).coerceIn(0f, 1.5f); return }
        val i = (0 until 8).firstOrNull { val ch = channel(it); if (groupsLevel) ch == ui.group else ch == ui.selectedIndex }
        if (i == null) { if (!groupsLevel) soundPage = ui.selectedSound / 8; return }
        onKnob(i, delta * 4)
    }

    override fun onGroup(g: Int) { ui.selectGroup(g) }

    private fun db(v: Float) = if (v <= -59.9f) "-∞" else "%+.1f".format(v)
    private fun pan(v: Float) = (v * 100).roundToInt().let { if (it == 0) "C" else if (it < 0) "L${-it}" else "R$it" }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        // D1–D8: the channel names above the strips (focus)
        for (i in 0 until 8) {
            val ch = channel(i)
            val c = if (i < 4) l else r
            val focused = if (groupsLevel) ch == ui.group else ch == ui.selectedIndex
            c.drawText(if (groupsLevel) "${'A' + ch}" else "${(ch % 16) + 1}", 60f + (i % 4) * 120f, 24f, if (focused) dOn else dLabel)
        }
        // master meter (after the master volume), horizontal
        run {
            val x0 = 8f; val x1 = 472f; val y0 = 40f; val y1 = 48f
            r.drawRect(x0, y0, x1, y1, meterBg)
            fun x(d: Float) = x0 + (x1 - x0) * ((d + 48f) / 54f).coerceIn(0f, 1f)
            val xl = x(masterLevel); val xg = x(-12f); val xy = x(0f)
            r.drawRect(x0, y0, minOf(xl, xg), y1, meterGreen)
            if (xl > xg) r.drawRect(xg, y0, minOf(xl, xy), y1, meterYellow)
            if (xl > xy) r.drawRect(xy, y0, xl, y1, meterRed)
        }
        val what = when { sendMode >= 0 -> "SEND ${'A' + sendMode}"; panMode -> "PAN"; else -> "VOLUME" }
        l.drawText((if (groupsLevel) "GROUPS" else "${ui.groupTitle()} · ${soundPage * 8 + 1}–${soundPage * 8 + 8}") + " · $what (push: next) · master ${"%.0f".format(ui.engine.masterGain * 100)}%", 8f, 52f, title)
        for (i in 0 until 8) {
            val c = if (i < 4) l else r
            val x = 60f + (i % 4) * 120f
            val ch = channel(i)
            val colour: Int
            val label: String
            val vol: Float
            val pn: Float
            val audible: Boolean
            val focused: Boolean
            if (groupsLevel) {
                colour = groupColour(ch); label = "GROUP ${'A' + ch}"
                vol = p.groupVolume(ch); pn = p.groupPan(ch); audible = p.groupAudible(ch); focused = ch == ui.group
            } else {
                colour = ui.soundColour(ch); label = soundLabel(ch)
                vol = params[ch, SoundParams.VOL]; pn = params[ch, SoundParams.PAN]; audible = p.audible(ch)
                focused = ch == ui.selectedIndex
            }
            if (focused) c.drawRect(x - 58f, 60f, x + 58f, 272f, sel)
            c.drawRect(x - 50f, 64f, x + 50f, 70f, Theme.fill(Mk3Colors.rgb(colour)))
            c.drawText(label, x, 90f, if (audible) name else nameDim)
            if (!groupsLevel) c.drawText(ui.soundName(ch).take(12).ifEmpty { "—" }, x, 108f, nameDim)
            // volume bar (0 dB at 5/6 of the height) and pan marker, VU meter beside it
            val top = 118f; val bot = 222f
            val mi = meterIndex(ch)
            drawMeter(c, x + 14f, x + 24f, top, bot, level[mi], hold[mi])
            c.drawRect(x - 8f, top, x + 8f, bot, barBg)
            val frac = ((vol + 60f) / 72f).coerceIn(0f, 1f)
            c.drawRect(x - 8f, bot - (bot - top) * frac, x + 8f, bot, Theme.fill(Mk3Colors.rgb(colour)))
            val px = x + pn * 40f
            c.drawRect(px - 2f, 226f, px + 2f, 234f, Theme.fill(Theme.FG))
            if (sendMode >= 0 && groupsLevel) {
                c.drawText("SEND ${'A' + sendMode}", x, 252f, nameDim)
                c.drawText("${(p.groupSend(ch, sendMode) * 100).roundToInt()}%", x, 268f, value)
            } else {
                c.drawText("${db(vol)} dB", x, 252f, if (!panMode) value else nameDim)
                c.drawText(pan(pn), x, 268f, if (panMode) value else nameDim)
            }
        }
    }
}

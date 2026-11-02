package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import org.openmk3.core.audio.SoundParams
import org.openmk3.core.audio.soundLabel
import org.openmk3.core.mk3.Mk3Colors
import kotlin.math.roundToInt

/**
 * MIXER: eight channel strips on the knobs.
 *  D1 GROUPS (the 8 groups) · D2 SOUNDS (the focused group's sounds, ◀ ▶: 1–8 / 9–16)
 *  D5 VOLUME / D6 PAN: what the knobs change · encoder: master volume
 *  Pads: focus (and play) a sound · GROUP buttons: focus a group · MIXER: back
 */
class MixerPage(ui: Ui) : Page(ui) {

    override val playsPads = true

    private companion object {
        var groupsLevel = false
        var panMode = false
        var soundPage = 0
    }

    private val p = ui.engine.pattern
    private val params = ui.engine.params
    private var shift = false
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

    override fun tick(now: Long): Boolean {
        if (dirtySince != 0L && now - dirtySince > 1500) {
            p.save(ui.ctx); params.save(ui.ctx); ui.checkpoint(); dirtySince = 0L
        }
        return false
    }

    override fun onExit() {
        if (dirtySince != 0L) { p.save(ui.ctx); params.save(ui.ctx) }
    }

    override fun lights(l: Lights) {
        l.set("MIXER", true)
        l.set("D1", groupsLevel)
        l.set("D2", !groupsLevel)
        l.set("D5", !panMode)
        l.set("D6", panMode)
        if (!groupsLevel) { l.set("PAGE LEFT", soundPage == 1); l.set("PAGE RIGHT", soundPage == 0) }
        l.set("PLAY", ui.engine.playing)
        l.set("STOP")
        l.set("SHIFT", shift)
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (name == "SHIFT") { shift = down; return }
        if (!down) return
        val e = ui.engine
        when (name) {
            "D1" -> groupsLevel = true
            "D2" -> groupsLevel = false
            "D5" -> panMode = false
            "D6" -> panMode = true
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
        if (groupsLevel) {
            if (panMode) p.setGroupPan(ch, p.groupPan(ch) + delta * 0.002f * k)
            else p.setGroupVolume(ch, p.groupVolume(ch) + delta * 0.05f * k)
        } else {
            if (panMode) params[ch, SoundParams.PAN] = (params[ch, SoundParams.PAN] + delta * 0.002f * k).coerceIn(-1f, 1f)
            else params[ch, SoundParams.VOL] = (params[ch, SoundParams.VOL] + delta * 0.05f * k).coerceIn(-60f, 12f)
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

    override fun onEncoder(delta: Int) {
        val e = ui.engine
        e.masterGain = (e.masterGain + delta * 0.02f).coerceIn(0f, 1.5f)
    }

    private fun db(v: Float) = if (v <= -59.9f) "-∞" else "%+.1f".format(v)
    private fun pan(v: Float) = (v * 100).roundToInt().let { if (it == 0) "C" else if (it < 0) "L${-it}" else "R$it" }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText("GROUPS", 60f, 24f, if (groupsLevel) dOn else dLabel)
        l.drawText("SOUNDS", 180f, 24f, if (!groupsLevel) dOn else dLabel)
        r.drawText("VOLUME", 60f, 24f, if (!panMode) dOn else dLabel)
        r.drawText("PAN", 180f, 24f, if (panMode) dOn else dLabel)
        val master = "MASTER ${"%.0f".format(ui.engine.masterGain * 100)}% (encoder)"
        l.drawText(if (groupsLevel) "Groups · $master" else "Group ${ui.groupLetter} sounds ${soundPage * 8 + 1}–${soundPage * 8 + 8} · $master", 8f, 52f, title)
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
            if (!groupsLevel) c.drawText(ui.engine.kit[ch].name.take(12).ifEmpty { "—" }, x, 108f, nameDim)
            // volume bar (0 dB at 5/6 of the height) and pan marker
            val top = 118f; val bot = 222f
            c.drawRect(x - 8f, top, x + 8f, bot, barBg)
            val frac = ((vol + 60f) / 72f).coerceIn(0f, 1f)
            c.drawRect(x - 8f, bot - (bot - top) * frac, x + 8f, bot, Theme.fill(Mk3Colors.rgb(colour)))
            val px = x + pn * 40f
            c.drawRect(px - 2f, 226f, px + 2f, 234f, Theme.fill(Theme.FG))
            c.drawText("${db(vol)} dB", x, 252f, if (!panMode) value else nameDim)
            c.drawText(pan(pn), x, 268f, if (panMode) value else nameDim)
        }
    }
}

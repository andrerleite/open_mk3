package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import org.openmk3.core.audio.SoundParams
import org.openmk3.core.audio.soundLabel
import kotlin.math.roundToInt

/**
 * CHANNEL: channel properties of the master, the focused group or the focused sound.
 *  D1 MASTER · D2 GROUP · D3 SOUND · ◀ ▶ OUTPUT / GROOVE
 *  OUTPUT: K1 volume, K2 pan · GROOVE: K1 swing (master: the project's; group: added on top)
 *  SHIFT: fine · CHANNEL: back
 */
class ChannelPage(ui: Ui) : Page(ui) {

    override val playsPads = true

    private companion object {
        var level = 2
        var page = 0
    }

    private val p = ui.engine.pattern
    private val sp = ui.engine.params
    private var shift = false
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val dOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val strip = Theme.text(13f, Theme.DIM)
    private val stripOn = Theme.text(13f, Theme.ACCENT, bold = true)
    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val kName = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val kValue = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)

    override fun lights(l: Lights) {
        l.set("CHANNEL", true)
        l.set("D1", level == 0); l.set("D2", level == 1); l.set("D3", level == 2)
        l.set("PAGE LEFT", "PAGE RIGHT")
        l.set("SHIFT", shift)
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (name == "SHIFT") { shift = down; return }
        if (!down) return
        when (name) {
            "D1" -> level = 0
            "D2" -> level = 1
            "D3" -> level = 2
            "PAGE LEFT", "PAGE RIGHT" -> page = 1 - page
            "CHANNEL" -> ui.showHome()
        }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (ui.padMode == Ui.PadMode.PAD) ui.selectedSound = slot
    }

    override fun onKnob(knob: Int, delta: Int) {
        val k = if (shift) 0.1f else 1f
        val g = ui.group
        val s = ui.selectedIndex
        if (page == 0) when (knob) {
            0 -> when (level) {
                0 -> ui.engine.masterGain = (ui.engine.masterGain + delta * 0.002f * k).coerceIn(0f, 1.5f)
                1 -> p.setGroupVolume(g, p.groupVolume(g) + delta * 0.05f * k)
                else -> { val b = sp[s, SoundParams.VOL]; sp[s, SoundParams.VOL] = (sp[s, SoundParams.VOL] + delta * 0.05f * k).coerceIn(-60f, 12f); ui.mirrorSound(SoundParams.VOL, b); ui.autoWrite(s, SoundParams.VOL) }
            }
            1 -> when (level) {
                1 -> p.setGroupPan(g, p.groupPan(g) + delta * 0.002f * k)
                2 -> { val b = sp[s, SoundParams.PAN]; sp[s, SoundParams.PAN] = (sp[s, SoundParams.PAN] + delta * 0.002f * k).coerceIn(-1f, 1f); ui.mirrorSound(SoundParams.PAN, b); ui.autoWrite(s, SoundParams.PAN) }
            }
        } else if (knob == 0) when (level) {
            0 -> p.swing = (p.swing + delta * 0.0005f * k).coerceIn(0f, 0.5f)
            1 -> p.setGroupSwing(g, p.groupSwing(g) + delta * 0.0005f * k)
            else -> { val b = sp[s, SoundParams.SWING]; sp[s, SoundParams.SWING] = (b + delta * 0.0005f * k).coerceIn(0f, 0.5f); ui.mirrorSound(SoundParams.SWING, b) }
        }
        saveAt = android.os.SystemClock.uptimeMillis()
    }

    private var saveAt = 0L

    override fun tick(now: Long): Boolean {
        if (saveAt != 0L && now - saveAt > 1500) { p.save(ui.ctx); sp.save(ui.ctx); ui.checkpoint(); saveAt = 0L }
        return false
    }

    override fun onExit() {
        if (saveAt != 0L) { p.save(ui.ctx); sp.save(ui.ctx) }
    }

    private fun db(v: Float) = if (v <= -59.9f) "-∞ dB" else "%+.1f dB".format(v)
    private fun pan(v: Float) = (v * 100).roundToInt().let { if (it == 0) "C" else if (it < 0) "L${-it}" else "R$it" }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText("MASTER", 60f, 24f, if (level == 0) dOn else dLabel)
        l.drawText("GROUP", 180f, 24f, if (level == 1) dOn else dLabel)
        l.drawText("SOUND", 300f, 24f, if (level == 2) dOn else dLabel)
        l.drawText("OUTPUT", 8f, 52f, if (page == 0) stripOn else strip)
        l.drawText("GROOVE", 80f, 52f, if (page == 1) stripOn else strip)
        val name = when (level) { 0 -> "MASTER"; 1 -> ui.groupTitle(); else -> "SOUND ${soundLabel(ui.selectedIndex)}" }
        l.drawText("CHANNEL · $name", 16f, 86f, title)
        fun knob(i: Int, n: String, v: String) { val x = 60f + i * 120f; l.drawText(n, x, 244f, kName); l.drawText(v, x, 264f, kValue) }
        if (page == 0) {
            when (level) {
                0 -> knob(0, "VOLUME", "${(ui.engine.masterGain * 100).roundToInt()}%")
                1 -> { knob(0, "VOLUME", db(p.groupVolume(ui.group))); knob(1, "PAN", pan(p.groupPan(ui.group))) }
                else -> { knob(0, "VOLUME", db(sp[ui.selectedIndex, SoundParams.VOL])); knob(1, "PAN", pan(sp[ui.selectedIndex, SoundParams.PAN])) }
            }
        } else {
            when (level) {
                0 -> knob(0, "SWING", "${(p.swing * 200).roundToInt()}%")
                1 -> knob(0, "SWING", "+${(p.groupSwing(ui.group) * 200).roundToInt()}%")
                else -> { knob(0, "SWING", "+${(sp[ui.selectedIndex, SoundParams.SWING] * 200).roundToInt()}%"); l.drawText("Added to the master and group swing", 16f, 150f, label) }
            }
        }
    }
}

package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.openmk3.core.AppLog
import org.openmk3.core.audio.SoundParams
import org.openmk3.core.audio.soundLabel
import org.openmk3.core.mk3.Mk3Colors

/**
 * Colour picker. SHIFT + GROUP opens it for that group, SHIFT + SELECT for the focused sound.
 * The 16 pads show the 16 colours: hit one to pick it. D1 / D2 switch between the group and
 * the focused sound, D8 sets the sound back to AUTO (its group's colour). Any page button,
 * or picking a colour, returns to the previous page.
 */
class ColourPage(ui: Ui, private val back: Page, initial: Target, index: Int) : Page(ui) {

    enum class Target { GROUP, SOUND }

    private var target = initial
    private val group = if (initial == Target.GROUP) index else ui.group
    private val sound = if (initial == Target.SOUND) index else ui.selectedIndex

    override val customPads = true
    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val big = Theme.text(22f, Theme.FG, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val dOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val num = Theme.text(16f, Theme.BG, bold = true, align = Paint.Align.CENTER)
    private val ring = Theme.stroke(Theme.FG, 3f)

    private fun current(): Int = when (target) {
        Target.GROUP -> Colours.group[group]
        Target.SOUND -> ui.engine.params[sound, SoundParams.COLOUR].toInt()  // 0 = AUTO
    }

    /** Pad slot (0 = bottom-left) <-> colour 1..16, laid out like the pad numbers. */
    private fun colourOfSlot(slot: Int) = slot + 1

    override fun onEnter() {
        for (slot in 0 until 16) {
            val c = colourOfSlot(slot)
            ui.padLed(slot, Mk3Colors.value(c, if (c == current()) 3 else 1))
        }
    }

    override fun tick(now: Long): Boolean {
        onEnter()
        return false
    }

    override fun lights(l: Lights) {
        l.set("D1", target == Target.GROUP)
        l.set("D2", target == Target.SOUND)
        if (target == Target.SOUND) l.set("D8")
        l.set("SHIFT", ui.shiftHeld)
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        val c = colourOfSlot(slot)
        when (target) {
            Target.GROUP -> {
                Colours.group[group] = c
                Colours.save(ui.ctx)
                AppLog.i("Group ${'A' + group} colour $c")
            }
            Target.SOUND -> {
                ui.engine.params[sound, SoundParams.COLOUR] = c.toFloat()
                ui.engine.params.save(ui.ctx)
                AppLog.i("${soundLabel(sound)} colour $c")
            }
        }
        ui.show(back)
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (!down) return
        when (name) {
            "D1" -> target = Target.GROUP
            "D2" -> target = Target.SOUND
            "D8" -> if (target == Target.SOUND) {
                ui.engine.params[sound, SoundParams.COLOUR] = 0f
                ui.engine.params.save(ui.ctx)
                ui.show(back)
            }
            "SHIFT", null -> {}
            else -> if (!name.startsWith("D") && !name.startsWith("ENCODER")) ui.show(back)
        }
        ui.invalidate()
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText("GROUP ${'A' + group}", 60f, 26f, if (target == Target.GROUP) dOn else dLabel)
        l.drawText("SOUND ${soundLabel(sound)}", 180f, 26f, if (target == Target.SOUND) dOn else dLabel)
        if (target == Target.SOUND) r.drawText("AUTO", 420f, 26f, dLabel)
        l.drawText("COLOUR", 16f, 80f, title)
        val what = if (target == Target.GROUP) "Group ${'A' + group}" else "Sound ${soundLabel(sound)}"
        l.drawText(what, 16f, 116f, big)
        val cur = current()
        l.drawText(if (cur == 0) "AUTO (group colour)" else "Colour $cur", 16f, 146f, label)
        l.drawText("Hit a pad to pick its colour", 16f, 200f, label)
        l.drawText("D1 group · D2 sound · D8 auto", 16f, 222f, label)
        // swatches in pad layout: pad 13–16 on top
        val w = 100f
        val h = 48f
        for (slot in 0 until 16) {
            val c = colourOfSlot(slot)
            val col = slot % 4
            val row = 3 - slot / 4
            val x = 40f + col * (w + 8f)
            val y = 52f + row * (h + 5f)
            val rect = RectF(x, y, x + w, y + h)
            r.drawRoundRect(rect, 6f, 6f, Theme.fill(Mk3Colors.rgb(c)))
            r.drawText("${slot + 1}", x + w / 2, y + h / 2 + 6f, num)
            if (c == cur) r.drawRoundRect(rect, 6f, 6f, ring)
        }
    }
}

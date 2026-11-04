package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.openmk3.core.audio.SoundParams
import org.openmk3.core.mk3.Mk3Colors

/**
 * NAVIGATE (SHIFT + VARIATION): getting around the views.
 *  General: D3 SONG (arranger) · D4 MIXER · D5 BROWSER · D8 FOLLOW on / off
 *           pads 1 / 3 scroll the pattern grid left / right, 2 / 6 zoom out / in; K5 zoom, K6 scroll
 *  SHIFT + D2 PAGE NAV: the focused sound's parameter pages on the pads; a pad opens that page
 *  VARIATION or SHIFT + VARIATION: back
 */
class NavigatePage(ui: Ui, private val back: Page) : Page(ui) {

    override val customPads = true
    private var pageNav = false
    private var shift = false
    private val acc = IntArray(2)

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val dOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val cellText = Theme.text(13f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val kName = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val kValue = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val empty = Theme.fill(android.graphics.Color.rgb(32, 32, 40))
    private val on = Theme.fill(android.graphics.Color.rgb(70, 70, 92))

    fun back() = ui.show(back)

    private val source get() = ui.engine.params[ui.selectedIndex, SoundParams.SRC].toInt()
    private fun pageNames() = SoundPageNav.sourcePages(source)

    override fun lights(l: Lights) {
        l.set("VARIATION", true)
        l.set("D2", pageNav)
        l.set("D3", "D4", "D5")
        l.set("D8", ui.follow)
    }

    private fun gridPages(): Int {
        val p = ui.engine.pattern
        return (p.length(ui.group) + 16 * HomePage.gridZoom - 1) / (16 * HomePage.gridZoom)
    }

    private fun scroll(d: Int) { ui.follow = false; HomePage.gridPage = (HomePage.gridPage + d).coerceIn(0, gridPages() - 1) }
    private fun zoom(d: Int) {
        val z = listOf(1, 2, 4, 8, 16)
        HomePage.gridZoom = z[(z.indexOf(HomePage.gridZoom) + d).coerceIn(0, z.size - 1)]
        HomePage.gridPage = HomePage.gridPage.coerceIn(0, gridPages() - 1)
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (name == "SHIFT") { shift = down; return }
        if (!down) return
        when (name) {
            "D2" -> if (shift || pageNav) pageNav = !pageNav
            "D3" -> ui.show(ArrangerPage(ui))
            "D4" -> ui.show(MixerPage(ui))
            "D5" -> ui.show(BrowserPage(ui))
            "D8" -> ui.follow = !ui.follow
            "VARIATION" -> back()
        }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (pageNav) {
            if (slot < pageNames().size) {
                SoundPageNav.wantPage = slot
                ui.show(SoundPage(ui))
            }
            return
        }
        when (slot) {
            0 -> scroll(-1)
            2 -> scroll(1)
            1 -> zoom(-1)
            5 -> zoom(1)
        }
    }

    override fun onKnob(knob: Int, delta: Int) {
        if (knob != 4 && knob != 5) return
        val k = knob - 4
        acc[k] += delta
        if (kotlin.math.abs(acc[k]) < 25) return
        val d = if (acc[k] > 0) 1 else -1
        acc[k] = 0
        if (knob == 4) zoom(d) else scroll(d)
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        val names = pageNames()
        for (i in 0 until 16) {
            val v = if (pageNav) (if (i < names.size) Mk3Colors.value(Mk3Colors.WHITE, 1) else 0)
                else if (i in intArrayOf(0, 1, 2, 5)) Mk3Colors.value(Mk3Colors.WHITE, 1) else 0
            ui.padLed(i, v)
        }
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText("PAGE NAV", 180f, 24f, if (pageNav) dOn else dLabel)
        l.drawText("SONG", 300f, 24f, dLabel)
        l.drawText("MIXER", 420f, 24f, dLabel)
        r.drawText("BROWSER", 60f, 24f, dLabel)
        r.drawText("FOLLOW", 420f, 24f, if (ui.follow) dOn else dLabel)
        l.drawText("NAVIGATE", 16f, 60f, title)
        if (pageNav) {
            l.drawText("Pads: the parameter pages of ${ui.padLabel()}", 16f, 92f, label)
            l.drawText("(SHIFT + D2 or D2 again: back to general)", 16f, 114f, label)
        } else {
            l.drawText("Pads 1 / 3: scroll the pattern · 2 / 6: zoom", 16f, 92f, label)
            l.drawText("Pattern view: ${16 * HomePage.gridZoom} steps, page ${HomePage.gridPage + 1}/${gridPages()}" + if (ui.follow) " (follows)" else "", 16f, 114f, label)
            l.drawText("SHIFT + D2: parameter pages on the pads", 16f, 136f, label)
            r.drawText("ZOOM", 60f, 238f, kName); r.drawText("${16 * HomePage.gridZoom}", 60f, 260f, kValue)
            r.drawText("SCROLL", 180f, 238f, kName); r.drawText("${HomePage.gridPage + 1}", 180f, 260f, kValue)
        }
        // pad layout on the right screen
        val w = 112f; val h = 40f
        val labels = if (pageNav) names else listOf("◀ SCROLL", "ZOOM −", "SCROLL ▶", "", "", "ZOOM +")
        for (i in 0 until 16) {
            val col = i % 4; val row = 3 - i / 4
            val x = 8f + col * (w + 4f); val y = 40f + row * (h + 4f)
            val t = labels.getOrNull(i).orEmpty()
            r.drawRoundRect(RectF(x, y, x + w, y + h), 5f, 5f, if (t.isEmpty()) empty else on)
            if (t.isNotEmpty()) r.drawText(t.take(14), x + w / 2, y + 25f, cellText)
        }
    }
}

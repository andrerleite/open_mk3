package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.openmk3.core.AppLog
import org.openmk3.core.mk3.Mk3Colors
import kotlin.math.abs

/**
 * ARRANGER (or SHIFT + SCENE): the song, a list of sections, each playing one scene.
 *  D1 SONG on / off · D2 LOOP the playing section (also SHIFT + RESTART) · D3 JUMP to the
 *  selected section at the end of the playing one · D5 duplicate · D6 delete the selected section
 *  Encoder / ◀ ▶: select a section · K1: move it earlier / later
 *  Pads: the 16 scenes. Hit one to add it after the selected section (an empty pad first makes
 *  a scene from the patterns playing now); SHIFT + pad: the selected section plays that scene
 */
class ArrangerPage(ui: Ui) : Page(ui) {

    override val customPads = true

    private companion object {
        var cursor = 0
    }

    private val p = ui.engine.pattern
    private var shift = false
    private var acc = 0
    private var message = ""

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val ok = Theme.text(15f, Theme.OK)
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val dOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val cell = Theme.text(13f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val small = Theme.text(11f, Theme.DIM, align = Paint.Align.CENTER)
    private val box = Theme.fill(android.graphics.Color.rgb(50, 50, 66))
    private val boxSel = Theme.fill(android.graphics.Color.rgb(90, 90, 130))
    private val boxPlay = Theme.fill(Theme.ACCENT)
    private val head = Theme.fill(android.graphics.Color.WHITE)
    private val outline = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = android.graphics.Color.WHITE; isAntiAlias = true }

    init {
        cursor = cursor.coerceIn(0, maxOf(0, p.song.size - 1))
    }

    override fun tick(now: Long): Boolean = true

    override fun lights(l: Lights) {
        l.set("ARRANGER", true)
        l.set("D1", p.songMode)
        l.set("D2", p.loop)
        l.set("D3", p.songJump >= 0)
        l.set("D5", "D6")
        l.set("PAGE LEFT", "PAGE RIGHT")
        l.set("SHIFT", shift)
    }

    private fun changed(msg: String) {
        message = msg
        AppLog.i("Arranger: $msg")
        p.save(ui.ctx)
        ui.checkpoint()
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (name == "SHIFT") { shift = down; return }
        if (!down) return
        val playing = ui.engine.playing
        when (name) {
            "D1" -> {
                p.songMode = !p.songMode
                if (p.songMode && !playing) p.songPos = cursor
                changed(if (p.songMode) "Song mode on" else "Song mode off: scenes loop as launched")
            }
            "D2" -> { p.loop = !p.loop; changed(if (p.loop) "Loop the playing section" else "Loop off") }
            "D3" -> if (cursor in p.song.indices) {
                if (playing) { p.songJump = cursor; changed("Section ${cursor + 1} next") }
                else { p.songPos = cursor; changed("Starts at section ${cursor + 1}") }
            }
            "D5" -> if (cursor in p.song.indices) { p.song.add(cursor + 1, p.song[cursor]); cursor++; changed("Section duplicated") }
            "D6" -> if (cursor in p.song.indices) {
                p.song.removeAt(cursor)
                if (p.songJump >= p.song.size) p.songJump = -1
                p.songPos = p.songPos.coerceIn(0, maxOf(0, p.song.size - 1))
                cursor = cursor.coerceIn(0, maxOf(0, p.song.size - 1))
                changed("Section deleted")
            }
            "PAGE LEFT" -> cursor = (cursor - 1).coerceAtLeast(0)
            "PAGE RIGHT" -> cursor = (cursor + 1).coerceAtMost(maxOf(0, p.song.size - 1))
        }
    }

    override fun onEncoder(delta: Int) {
        if (p.song.isEmpty()) return
        cursor = (cursor + delta).coerceIn(0, p.song.size - 1)
    }

    override fun onKnob(knob: Int, delta: Int) {
        if (knob != 0 || cursor !in p.song.indices) return
        acc += delta
        if (abs(acc) < 25) return
        val d = if (acc > 0) 1 else -1
        acc = 0
        val to = cursor + d
        if (to !in p.song.indices) return
        java.util.Collections.swap(p.song, cursor, to)
        cursor = to
        changed("Section moved to ${to + 1}")
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (p.scenes[slot] == null) p.captureScene(slot)
        if (shift && cursor in p.song.indices) {
            p.song[cursor] = slot
            changed("Section ${cursor + 1} = scene ${slot + 1}")
            return
        }
        val at = if (p.song.isEmpty()) 0 else cursor + 1
        p.song.add(at, slot)
        if (p.songJump >= at) p.songJump++
        if (ui.engine.playing && p.songPos >= at && p.song.size > 1) p.songPos++
        cursor = at
        changed("Scene ${slot + 1} added as section ${at + 1}")
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        val selScene = p.song.getOrNull(cursor) ?: -1
        val playScene = if (p.songMode) p.song.getOrNull(p.songPos) ?: -1 else p.currentScene
        for (i in 0 until 16) {
            ui.padLed(i, when {
                p.scenes[i] == null -> 0
                i == playScene && (now / 250) % 2 == 0L && ui.engine.playing -> Mk3Colors.value(Mk3Colors.WHITE, 3)
                i == selScene -> Mk3Colors.value(Mk3Colors.WHITE, 3)
                else -> Mk3Colors.value(Mk3Colors.WHITE, 0)
            })
        }
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText("SONG", 60f, 24f, if (p.songMode) dOn else dLabel)
        l.drawText("LOOP", 180f, 24f, if (p.loop) dOn else dLabel)
        l.drawText("JUMP", 300f, 24f, if (p.songJump >= 0) dOn else dLabel)
        r.drawText("DUPL", 60f, 24f, dLabel)
        r.drawText("DELETE", 180f, 24f, dLabel)
        l.drawText("ARRANGER", 16f, 60f, title)
        val totalBars = p.song.sumOf { p.sceneLength(it) } / 16f
        l.drawText(if (p.song.isEmpty()) "Hit scene pads to build the song" else "${p.song.size} sections · ${"%.1f".format(totalBars)} bars", 16f, 90f, label)
        l.drawText(if (p.songMode) "Song mode: sections play in order" else "Song mode off (D1)", 16f, 112f, label)
        p.song.getOrNull(cursor)?.let { l.drawText("Selected: section ${cursor + 1} · scene ${it + 1} · ${"%.2g".format(p.sceneLength(it) / 16f)} bars", 16f, 140f, label) }
        if (message.isNotEmpty()) l.drawText(message, 16f, 170f, ok)
        l.drawText("Pad: add · SHIFT+pad: replace · K1: move", 16f, 260f, label)
        // right screen: the sections as blocks, 4 rows of 6
        val first = (cursor / 24) * 24
        val w = 76f; val h = 50f
        for (k in 0 until 24) {
            val idx = first + k
            val sc = p.song.getOrNull(idx) ?: break
            val x = 8f + (k % 6) * (w + 4f); val y = 40f + (k / 6) * (h + 6f)
            val rect = RectF(x, y, x + w, y + h)
            val playingHere = p.songMode && idx == p.songPos && ui.engine.playing
            r.drawRoundRect(rect, 5f, 5f, if (playingHere) boxPlay else if (idx == cursor) boxSel else box)
            if (idx == cursor) r.drawRoundRect(rect, 5f, 5f, outline)
            r.drawText("S${sc + 1}", x + w / 2, y + 22f, cell)
            r.drawText("${idx + 1}" + if (idx == p.songJump) " ▶" else "", x + w / 2, y + 40f, small)
            if (playingHere) {
                val pr = p.sectionProgress()
                if (pr >= 0f) r.drawRect(x + 3f, y + h - 6f, x + 3f + (w - 6f) * pr, y + h - 3f, head)
            }
        }
    }
}

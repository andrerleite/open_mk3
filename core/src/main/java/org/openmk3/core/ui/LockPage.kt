package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.openmk3.core.AppLog
import org.openmk3.core.mk3.Mk3Colors

/**
 * Extended LOCK (SHIFT + LOCK): 64 snapshots, 16 on the pads per bank (D7 / D8: bank).
 *  Empty pad: store the current settings · stored pad: recall it (morphing if MORPH is on)
 *  ERASE + pad: delete · D5 update the selected snapshot with the current settings
 *  K1 morph on / off · K2 morph time (bars) · LOCK or SHIFT + LOCK: back
 */
class LockPage(ui: Ui, private val back: Page) : Page(ui) {

    override val customPads = true
    private companion object { var bank = 0 }
    private val lock = ui.lock
    private var erase = false
    private var deleting = false  // D6: the next pad deletes its snapshot
    private var acc = 0
    private var message = ""

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val ok = Theme.text(15f, Theme.OK)
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val kName = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val kValue = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val cellText = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val empty = Theme.fill(android.graphics.Color.rgb(32, 32, 40))

    fun back() = ui.show(back)

    override fun tick(now: Long): Boolean = true

    override fun lights(l: Lights) {
        l.set("LOCK", true)
        l.set("ERASE", erase)
        l.set("D5"); l.set("D6", deleting); l.set("D7", bank > 0); l.set("D8", bank < 3)
    }

    override fun onPadHit(pad: Int, hid: Int, velocity: Float, pressure: Int) {
        val slot = bank * 16 + pad
        val s = lock.slots[slot]
        when {
            erase || deleting -> if (s != null) { lock.slots[slot] = null; message = "Snapshot ${slot + 1} deleted" }
            s == null -> { lock.slots[slot] = lock.take(); lock.selected = slot; message = "Snapshot ${slot + 1} stored" }
            else -> { lock.selected = slot; lock.recall(s); message = "Snapshot ${slot + 1}" + if (lock.morph) " (morphing)" else "" }
        }
        deleting = false
        AppLog.i("Lock: $message")
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (name == "ERASE") { erase = down; return }
        if (!down) return
        when (name) {
            "D5" -> if (lock.selected >= 0) { lock.slots[lock.selected] = lock.take(); message = "Snapshot ${lock.selected + 1} updated" }
            "LOCK" -> back()
            "D6" -> { deleting = !deleting; message = if (deleting) "DELETE: press the snapshot to delete" else "" }
            "D7" -> bank = (bank - 1).coerceAtLeast(0)
            "D8" -> bank = (bank + 1).coerceAtMost(3)
        }
    }

    override fun onKnob(knob: Int, delta: Int) {
        acc += delta
        if (kotlin.math.abs(acc) < 25) return
        val d = if (acc > 0) 1 else -1
        acc = 0
        when (knob) {
            0 -> lock.morph = d > 0
            1 -> lock.morphBars = (lock.morphBars + d).coerceIn(1, 16)
            2 -> lock.target = d > 0
        }
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        for (p in 0 until 16) {
            val i = bank * 16 + p
            val v = when {
                i == lock.selected && lock.slots[i] != null -> Mk3Colors.value(Mk3Colors.WHITE, 3)
                lock.slots[i] != null -> Mk3Colors.value(Mk3Colors.WHITE, 0)
                else -> 0
            }
            ui.padLed(p, v)
        }
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        r.drawText("UPDATE", 60f, 24f, dLabel)
        r.drawText("DELETE", 180f, 24f, if (deleting) Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER) else dLabel)
        r.drawText("◀ BANK", 300f, 24f, dLabel)
        r.drawText("BANK ▶", 420f, 24f, dLabel)
        l.drawText("BANK ${bank + 1} / 4", 330f, 56f, label)
        l.drawText("EXTENDED LOCK", 16f, 56f, title)
        l.drawText("Empty pad: store · pad: recall · ERASE + pad: delete", 16f, 90f, label)
        l.drawText("LOCK alone: quick snapshot / recall", 16f, 112f, label)
        if (message.isNotEmpty()) l.drawText(message, 16f, 150f, ok)
        l.drawText("MORPH", 60f, 238f, kName)
        l.drawText(if (lock.morph) "ON" else "OFF", 60f, 260f, kValue)
        l.drawText("MODE", 300f, 238f, kName)
        l.drawText(if (lock.target) "TARGET" else "TRAVEL", 300f, 260f, kValue)
        l.drawText(if (lock.target) "GRID" else "TIME", 180f, 238f, kName)
        l.drawText("${lock.morphBars} bar${if (lock.morphBars > 1) "s" else ""}", 180f, 260f, kValue)
        val w = 112f
        val h = 44f
        for (p in 0 until 16) {
            val i = bank * 16 + p
            val c = p % 4
            val row = 3 - p / 4
            val x = 8f + c * (w + 4f)
            val y = 44f + row * (h + 4f)
            val fill = if (lock.slots[i] == null) empty else Theme.fill(if (i == lock.selected) Theme.ACCENT else android.graphics.Color.rgb(90, 90, 110))
            r.drawRoundRect(RectF(x, y, x + w, y + h), 5f, 5f, fill)
            r.drawText("${i + 1}", x + w / 2, y + 28f, cellText)
        }
    }
}

package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.openmk3.core.AppLog
import org.openmk3.core.mk3.Mk3Buttons
import org.openmk3.core.mk3.Mk3Device

/**
 * On-device setup: learns pad positions, button bits, button LED indices and pad LED
 * indices by asking the player to hit / press things on the MK3 itself.
 * Each pass is saved as soon as it finishes, so passes can be re-run individually.
 */
class WizardPage(ui: Ui, private val mode: Mode) : Page(ui) {

    override val customLeds = true

    enum class Mode { ALL, PADS, BUTTONS, ENCODER, BUTTON_LEDS, PAD_LEDS }
    private enum class Pass(val title: String) {
        PADS("Pad layout"), BUTTONS("Buttons"), BUTTON_LEDS("Button lights"), PAD_LEDS("Pad lights")
    }

    private val passes: List<Pass> = when (mode) {
        Mode.ALL -> listOf(Pass.PADS, Pass.BUTTONS, Pass.BUTTON_LEDS, Pass.PAD_LEDS)
        Mode.PADS -> listOf(Pass.PADS)
        Mode.BUTTONS -> listOf(Pass.BUTTONS)
        Mode.ENCODER -> listOf(Pass.BUTTONS)
        Mode.BUTTON_LEDS -> listOf(Pass.BUTTON_LEDS)
        Mode.PAD_LEDS -> listOf(Pass.PAD_LEDS)
    }
    private var passIdx = 0
    private var step = 0
    private var done = false
    private var doneAt = 0L
    private var feedback = ""
    private val history = ArrayList<String>()

    // working copies for the pad pass
    private val padSlots = IntArray(16) { -1 }

    private val pass get() = passes[passIdx]
    private val total get() = when (pass) {
        Pass.PADS -> 16
        Pass.BUTTONS -> names.size
        Pass.BUTTON_LEDS -> Mk3Device.BUTTON_LED_COUNT
        Pass.PAD_LEDS -> Mk3Device.PAD_LED_COUNT
    }

    private val title = Theme.text(22f, Theme.ACCENT, bold = true)
    private val small = Theme.text(16f, Theme.DIM)
    private val body = Theme.text(18f)
    private val big = Theme.text(44f, Theme.FG, bold = true)
    private val fb = Theme.text(18f, Theme.OK)
    private val cellPaint = Theme.fill(Theme.CELL)
    private val hiPaint = Theme.fill(Theme.ACCENT)
    private val donePaint = Theme.fill(Theme.OK)
    private val cellText = Theme.text(18f, Theme.FG, align = Paint.Align.CENTER)

    override fun onEnter() = beginPass()

    private fun beginPass() {
        step = 0
        feedback = ""
        when (pass) {
            Pass.PADS -> padSlots.fill(-1)
            Pass.BUTTONS -> {
                // The encoder-only pass keeps every other button's mapping.
                if (mode == Mode.ENCODER) names.forEach { ui.map.buttonBits.remove(it) } else ui.map.buttonBits.clear()
                held.clear()
                lastDown = -1
            }
            Pass.BUTTON_LEDS -> ui.map.buttonLeds.clear()
            Pass.PAD_LEDS -> { ui.map.padLeds.fill(-1); ui.map.otherPadReportLeds.clear() }
        }
        AppLog.i("Wizard: pass ${pass.title}")
        applyLeds()
    }

    private fun applyLeds() {
        ui.dev.clearLeds()
        when (pass) {
            Pass.BUTTON_LEDS -> if (step < total) ui.dev.setButtonLed(step, 0x7F)
            Pass.PAD_LEDS -> if (step < total) ui.dev.setPadLed(step, 0x2F)
            else -> {}
        }
    }

    private fun advance() {
        step++
        if (step >= total) finishPass() else applyLeds()
        ui.invalidate()
    }

    private fun finishPass() {
        if (pass == Pass.PADS) {
            for (s in 0 until 16) ui.map.padSlotToHid[s] = if (padSlots[s] >= 0) padSlots[s] else s
            ui.map.padsMapped = true
        }
        ui.map.save(ui.ctx)
        history.add("✓ ${pass.title} saved")
        if (passIdx + 1 < passes.size) {
            passIdx++
            beginPass()
        } else {
            done = true
            doneAt = System.currentTimeMillis()
            ui.dev.clearLeds()
            AppLog.i("Wizard: finished")
        }
    }

    override fun tick(now: Long): Boolean {
        if (done && System.currentTimeMillis() - doneAt > 2500) {
            done = false
            ui.showHome()
        }
        return false
    }

    // =====================================================================================

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (done) return
        when (pass) {
            Pass.PADS -> {
                val already = padSlots.indexOf(hid)
                if (already >= 0) {
                    feedback = "That pad is already slot ${already + 1}"
                    return
                }
                padSlots[step] = hid
                feedback = "Slot ${step + 1} = pad #$hid"
                advance()
            }
            Pass.BUTTONS -> {
                val name = names[step]
                feedback = "Skipped $name"
                AppLog.i("Wizard: $name skipped")
                advance()
            }
            Pass.BUTTON_LEDS -> {
                feedback = "Light $step: none"
                AppLog.i("Wizard: button LED $step -> none")
                advance()
            }
            Pass.PAD_LEDS -> {
                if (ui.map.padLeds[hid] < 0) ui.map.padLeds[hid] = step
                feedback = "Light $step = pad #$hid"
                AppLog.i("Wizard: pad LED $step -> pad hid $hid")
                advance()
            }
        }
    }

    /** Buttons to learn in this run (the encoder pass only asks for the 5 encoder controls). */
    private val names: List<String> =
        if (mode == Mode.ENCODER) Mk3Buttons.ALL.filter { it.startsWith("ENCODER") } else Mk3Buttons.ALL

    // Button pass: a control is learned when it is RELEASED, as the last bit that went down.
    // The 4D encoder has a touch sensor that fires as soon as a finger lands on it; any bit
    // still held when the real control is released (pressed before it) is that kind of sensor.
    private val held = LinkedHashSet<Int>()
    private var lastDown = -1

    private fun buttonPass(bit: Int, down: Boolean) {
        if (bit in ui.map.touchBits) return
        if (down) {
            held.add(bit)
            lastDown = bit
            return
        }
        held.remove(bit)
        if (bit != lastDown) return
        lastDown = -1
        for (t in held) {
            if (ui.map.touchBits.add(t)) {
                ui.map.buttonBits.entries.removeIf { it.value == t }
                AppLog.i("Wizard: bit $t is a touch sensor (ignored from now on)")
            }
        }
        held.clear()
        val want = names[step]
        if (want == "ENCODER TOUCH") {
            ui.map.touchBits.add(bit)
            ui.map.buttonBits.entries.removeIf { it.value == bit }
            feedback = "Touch sensor = bit $bit (ignored from now on)"
            AppLog.i("Wizard: encoder touch sensor = bit $bit")
            advance()
            return
        }
        val owner = ui.map.buttonBits.entries.firstOrNull { it.value == bit }?.key
        if (owner != null && owner != want) {
            feedback = "That button is already $owner"
            return
        }
        ui.map.buttonBits[want] = bit
        ui.map.rebuildIndex()
        feedback = "$want = bit $bit"
        AppLog.i("Wizard: $want = bit $bit")
        advance()
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (done) return
        if (pass == Pass.BUTTONS) { buttonPass(bit, down); return }
        if (!down) return
        when (pass) {
            Pass.PADS -> feedback = "Hit the highlighted pad (buttons are ignored here)"
            Pass.BUTTONS -> {}
            Pass.BUTTON_LEDS -> {
                val n = name ?: "BIT$bit"
                if (!ui.map.buttonLeds.containsKey(n)) ui.map.buttonLeds[n] = step
                else AppLog.i("Wizard: extra light $step also belongs to $n")
                feedback = "Light $step = $n"
                AppLog.i("Wizard: button LED $step -> $n")
                advance()
            }
            Pass.PAD_LEDS -> {
                ui.map.otherPadReportLeds.add(step)
                feedback = "Light $step = not a pad"
                AppLog.i("Wizard: pad-report LED $step -> other")
                advance()
            }
        }
    }

    // =====================================================================================

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        if (done) {
            l.drawText("SETUP COMPLETE", 24f, 70f, title)
            l.drawText("Mapping saved.", 24f, 130f, big.apply { textSize = 34f })
            l.drawText("Starting ${org.openmk3.core.Edition.title}…", 24f, 180f, body)
            return
        }
        l.drawText("SETUP ${passIdx + 1}/${passes.size} · ${pass.title.uppercase()}", 20f, 34f, title)
        l.drawText("${minOf(step + 1, total)} / $total", 460f, 34f, Theme.text(18f, Theme.DIM, align = Paint.Align.RIGHT))
        // progress bar
        l.drawRect(20f, 46f, 460f, 52f, cellPaint)
        l.drawRect(20f, 46f, 20f + 440f * step / total, 52f, hiPaint)

        big.textSize = 44f
        when (pass) {
            Pass.PADS -> {
                l.drawText("Hit the pad", 20f, 120f, body)
                l.drawText("shown in orange", 20f, 170f, big.apply { textSize = 34f })
                l.drawText("Pads are numbered from the bottom-left.", 20f, 220f, small)
                drawPadGrid(r, highlight = step)
            }
            Pass.BUTTONS -> {
                if (names[step] == "ENCODER TOUCH") {
                    l.drawText("Rest a finger on the 4D encoder", 20f, 100f, body)
                    l.drawText("TOUCH, don't press", 20f, 160f, big.apply { textSize = 30f })
                    l.drawText("then let go. No touch sensor? Hit a pad.", 20f, 220f, small)
                } else {
                    l.drawText("Press and release:", 20f, 100f, body)
                    l.drawText(names[step], 20f, 160f, big)
                    l.drawText("No such button? Hit any pad to skip.", 20f, 220f, small)
                }
                drawHistory(r)
            }
            Pass.BUTTON_LEDS -> {
                l.drawText("Light #$step is on.", 20f, 100f, body)
                l.drawText("Press the lit button", 20f, 150f, big.apply { textSize = 32f })
                l.drawText("Nothing lit, or several? Hit a pad to skip.", 20f, 200f, small)
                l.drawText("(Lights on pads or the strip: also hit a pad.)", 20f, 224f, small)
                drawHistory(r)
            }
            Pass.PAD_LEDS -> {
                l.drawText("Light #$step is on.", 20f, 100f, body)
                l.drawText("Hit the lit pad", 20f, 150f, big.apply { textSize = 32f })
                l.drawText("Something else lit (strip, group button)", 20f, 200f, small)
                l.drawText("or nothing? Press any button.", 20f, 224f, small)
                drawHistory(r)
            }
        }
        if (feedback.isNotEmpty()) l.drawText(feedback, 20f, 262f, fb)
    }

    private fun drawPadGrid(c: Canvas, highlight: Int) {
        val size = 60f
        val gap = 6f
        val x0 = (480f - 4 * size - 3 * gap) / 2
        val y0 = (272f - 4 * size - 3 * gap) / 2
        for (slot in 0 until 16) {
            val row = slot / 4
            val col = slot % 4
            val x = x0 + col * (size + gap)
            val y = y0 + (3 - row) * (size + gap) // row 0 at the bottom
            val p = when {
                slot == highlight -> hiPaint
                padSlots[slot] >= 0 -> donePaint
                else -> cellPaint
            }
            c.drawRoundRect(RectF(x, y, x + size, y + size), 8f, 8f, p)
            c.drawText("${slot + 1}", x + size / 2, y + size / 2 + 7f, cellText)
        }
    }

    private fun drawHistory(c: Canvas) {
        val lines = (history.takeLast(3) + listOf(feedback)).filter { it.isNotEmpty() }
        var y = 40f
        c.drawText("Mapped so far", 20f, y, title)
        y += 34f
        val items = when (pass) {
            Pass.BUTTONS -> ui.map.buttonBits.entries.toList().takeLast(8).map { "${it.key}  →  bit ${it.value}" }
            Pass.BUTTON_LEDS -> ui.map.buttonLeds.entries.toList().takeLast(8).map { "${it.key}  →  light ${it.value}" }
            Pass.PAD_LEDS -> ui.map.padLeds.withIndex().filter { it.value >= 0 }.takeLast(8).map { "pad #${it.index}  →  light ${it.value}" }
            else -> emptyList()
        }
        for (s in items) {
            c.drawText(s, 20f, y, body)
            y += 24f
        }
        if (lines.isNotEmpty()) c.drawText(lines.first(), 20f, 262f, small)
    }
}

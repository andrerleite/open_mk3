package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.openmk3.core.AppLog
import org.openmk3.core.Edition
import org.openmk3.core.PowerMonitor
import org.openmk3.core.audio.Pattern
import org.openmk3.core.mk3.Mk3Colors
import kotlin.math.roundToLong

/**
 * Main page: drum kit + 16-step sequencer, driven Maschine-style.
 *
 *  PAD MODE   pads play sounds (and record them while REC is on)
 *  STEP       pads are the 16 steps of the selected sound (step 1 = top-left)
 *  PLAY / STOP / RESTART / REC transport, FIXED VEL toggles full-velocity entry
 *  hold SELECT + pad = select sound, MUTE / SOLO + pad = mute / solo,
 *  ERASE + pad = clear that sound (pad mode) or that step (step mode), SHIFT + ERASE = clear all
 *  PAGE ◀ ▶ = previous / next sound, ENCODER = tempo, K1 volume, K2 pad sensitivity,
 *  K3 tempo, K4 swing, SETTINGS = LED palette
 */
class HomePage(ui: Ui) : Page(ui) {

    private enum class Mode { PAD, STEP }

    @Volatile private var mode = Mode.PAD
    @Volatile private var shift = false
    @Volatile private var erase = false
    @Volatile private var select = false
    @Volatile private var mute = false
    @Volatile private var solo = false
    private var rec = false
    private var fixedVel = false
    /** Pad LEDs flash on every trigger. SHIFT + PAD MODE toggles it (to test LED-related audio noise). */
    private var flash = true
    /** Shared with the browser, so it opens on the pad you were working on. */
    private var selected: Int
        get() = ui.selectedSound
        set(v) { ui.selectedSound = v }

    private var dirtySince = 0L
    private var power: PowerMonitor.Sample? = null
    private var powerAt = 0L
    private var lastStep = -2
    private var lastMeter = -1
    private val lastLed = IntArray(16) { -1 }
    private val pattern get() = ui.engine.pattern

    /** Pads only make sound in pad mode with no modifier held. Read from the input thread. */
    override val playsPads: Boolean
        get() = mode == Mode.PAD && !erase && !select && !mute && !solo

    // ---- look ----
    private val slotColour = intArrayOf(
        Mk3Colors.RED, Mk3Colors.ORANGE, Mk3Colors.YELLOW, Mk3Colors.WARM_YELLOW,
        Mk3Colors.MAGENTA, Mk3Colors.GREEN, Mk3Colors.LIME, Mk3Colors.MINT,
        Mk3Colors.CYAN, Mk3Colors.TURQUOISE, Mk3Colors.LIGHT_ORANGE, Mk3Colors.YELLOW,
        Mk3Colors.BLUE, Mk3Colors.VIOLET, Mk3Colors.PURPLE, Mk3Colors.PLUM,
    )
    private val colourPaint = Array(16) { Theme.fill(Mk3Colors.rgb(slotColour[it])) }
    private val dimPaint = Array(16) { Theme.fill(mix(Mk3Colors.rgb(slotColour[it]), 0.35f)) }
    private val title = Theme.text(22f, Theme.ACCENT, bold = true)
    private val label = Theme.text(15f, Theme.DIM)
    private val value = Theme.text(17f)
    private val big = Theme.text(30f, Theme.FG, bold = true)
    private val recPaint = Theme.text(30f, Theme.WARN, bold = true)
    private val hint = Theme.text(16f, Theme.ACCENT)
    private val rowLabel = Theme.text(12f, Theme.FG)
    private val rowLabelDim = Theme.text(12f, Theme.DIM)
    private val cellOff = Theme.fill(Color.rgb(30, 30, 38))
    private val cellOffBeat = Theme.fill(Color.rgb(42, 42, 52))
    private val selRow = Theme.fill(Color.rgb(60, 60, 75))
    private val playheadFill = Theme.fill(Color.WHITE)
    private val cell = Theme.fill(Theme.CELL)
    private val ok = Theme.fill(Theme.OK)
    private val warnFill = Theme.fill(Theme.WARN)
    private val soloMark = Theme.fill(Theme.OK)

    private fun mix(c: Int, f: Float) = Color.rgb(
        (Color.red(c) * f + 12).toInt(), (Color.green(c) * f + 12).toInt(), (Color.blue(c) * f + 16).toInt()
    )

    // =====================================================================================
    // lifecycle
    // =====================================================================================

    override fun onEnter() {
        lastLed.fill(-1)
        updateLeds(android.os.SystemClock.uptimeMillis())
    }

    override fun onExit() {
        if (dirtySince != 0L) save()
    }

    private fun changed() {
        if (dirtySince == 0L) dirtySince = android.os.SystemClock.uptimeMillis()
    }

    private fun save() {
        pattern.save(ui.ctx)
        dirtySince = 0L
    }

    override fun tick(now: Long): Boolean {
        var redraw = false
        if (dirtySince != 0L && now - dirtySince > 1500) save()
        if (now - powerAt > 5000) {
            power = PowerMonitor.sample(ui.ctx)
            powerAt = now
            redraw = true
        }
        val st = ui.engine.currentStep
        if (st != lastStep) { lastStep = st; redraw = true }
        val m = (ui.engine.meter * 20).toInt()
        if (m != lastMeter) { lastMeter = m; redraw = true }
        if (updateLeds(now)) redraw = true
        return redraw
    }

    // =====================================================================================
    // LEDs
    // =====================================================================================

    /** Returns true if any pad LED changed (the screen shows the same state). */
    private fun updateLeds(now: Long): Boolean {
        var any = false
        val p = pattern
        val step = ui.engine.currentStep
        for (slot in 0 until 16) {
            val c = slotColour[slot]
            val v = when (mode) {
                Mode.PAD -> {
                    val hot = flash && (now - ui.engine.lastTrigMs[slot] in 0..120 || ui.padPressure[slot] > 0)
                    when {
                        !p.audible(slot) -> if (hot) Mk3Colors.value(c, 0) else 0
                        hot -> Mk3Colors.value(c, 2)
                        slot == selected -> Mk3Colors.value(c, 1)
                        else -> Mk3Colors.value(c, 0)
                    }
                }
                Mode.STEP -> {
                    val s = stepForSlot(slot)
                    val on = p.vel[selected][s] > 0f
                    val sc = slotColour[selected]
                    when {
                        flash && s == step && on -> Mk3Colors.value(sc, 3)
                        flash && s == step -> Mk3Colors.value(Mk3Colors.WHITE, 0)
                        on -> Mk3Colors.value(sc, if (p.vel[selected][s] > 0.7f) 2 else 1)
                        else -> 0
                    }
                }
            }
            if (v != lastLed[slot]) {
                lastLed[slot] = v
                ui.padLed(slot, v)
                any = true
            }
        }
        val e = ui.engine
        val dim = 0x14
        ui.buttonLed("PLAY", if (e.playing) 0x7F else dim)
        ui.buttonLed("REC", if (rec) 0x7F else dim)
        ui.buttonLed("STOP", dim)
        ui.buttonLed("RESTART", dim)
        ui.buttonLed("STEP", if (mode == Mode.STEP) 0x7F else dim)
        ui.buttonLed("PAD MODE", if (mode == Mode.PAD) 0x7F else dim)
        ui.buttonLed("FIXED VEL", if (fixedVel) 0x7F else dim)
        ui.buttonLed("ERASE", if (erase) 0x7F else dim)
        ui.buttonLed("SELECT", if (select) 0x7F else dim)
        ui.buttonLed("MUTE", if (mute) 0x7F else dim)
        ui.buttonLed("SOLO", if (solo) 0x7F else dim)
        ui.buttonLed("SHIFT", if (shift) 0x7F else dim)
        ui.buttonLed("SETTINGS", dim)
        ui.buttonLed("PAGE LEFT", dim)
        ui.buttonLed("PAGE RIGHT", dim)
        ui.buttonLed("GROUP A", Mk3Colors.value(Mk3Colors.RED, 1))
        return any
    }

    /** Step i (0 = top-left, reading order) <-> pad slot (0 = bottom-left). Self-inverse. */
    private fun stepForSlot(slot: Int) = (3 - slot / 4) * 4 + slot % 4

    // =====================================================================================
    // input
    // =====================================================================================

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        val p = pattern
        when {
            select -> selected = slot
            mute -> { p.mute[slot] = !p.mute[slot]; changed() }
            solo -> { p.solo[slot] = !p.solo[slot]; p.updateSolo(); changed() }
            mode == Mode.PAD && erase -> {
                p.clear(slot); changed()
                AppLog.i("Erased ${ui.engine.kit[slot].name}")
            }
            mode == Mode.PAD -> {
                selected = slot
                if (rec && ui.engine.playing) record(slot, if (fixedVel) 1f else velocity)
            }
            else -> { // STEP mode
                val s = stepForSlot(slot)
                val row = p.vel[selected]
                row[s] = if (erase || row[s] > 0f) 0f else if (fixedVel) 1f else velocity
                changed()
            }
        }
    }

    /** Quantise a live hit to the nearest step of what the player actually heard. */
    private fun record(slot: Int, velocity: Float) {
        val e = ui.engine
        val heard = e.phase - e.outputLatencySteps()
        val n = heard.roundToLong()
        val step = ((n % Pattern.STEPS + Pattern.STEPS) % Pattern.STEPS).toInt()
        val p = pattern
        p.vel[slot][step] = maxOf(p.vel[slot][step], velocity)
        // Not yet played this loop: the live hit already sounded, don't play it twice.
        if (n >= e.phase) p.skipOnce[slot][step] = true
        changed()
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        when (name) {
            "SHIFT" -> shift = down
            "ERASE" -> {
                erase = down
                if (down && shift) {
                    for (s in 0 until Pattern.SOUNDS) pattern.clear(s)
                    changed()
                    AppLog.i("Pattern cleared")
                }
            }
            "SELECT" -> select = down
            "MUTE" -> mute = down
            "SOLO" -> solo = down
        }
        if (!down) return
        val e = ui.engine
        when (name) {
            "PLAY" -> if (e.playing) e.transportStop() else e.transportPlay()
            "STOP" -> { e.transportStop(); rec = false }
            "RESTART" -> e.transportRestart()
            "REC" -> { rec = !rec; if (rec && !e.playing) e.transportPlay() }
            "STEP" -> mode = if (mode == Mode.STEP) Mode.PAD else Mode.STEP
            "PAD MODE" -> if (shift) {
                flash = !flash
                AppLog.i("Pad LED flashing ${if (flash) "on" else "OFF"}")
            } else mode = Mode.PAD
            "FIXED VEL" -> fixedVel = !fixedVel
            "PAGE LEFT" -> selected = (selected + 15) % 16
            "PAGE RIGHT" -> selected = (selected + 1) % 16
            "SETTINGS" -> ui.show(PalettePage(ui))
            "BROWSER" -> ui.show(BrowserPage(ui))
        }
    }

    override fun onKnob(knob: Int, delta: Int) {
        val e = ui.engine
        when (knob) {
            0 -> e.masterGain = (e.masterGain + delta / 300f).coerceIn(0f, 1.5f)
            1 -> ui.padFullScale = (ui.padFullScale - delta * 4).coerceIn(500, 4095)
            2 -> { e.bpm = (e.bpm + delta / 5.0).coerceIn(40.0, 240.0); changed() }
            3 -> { pattern.swing = (pattern.swing + delta / 400f).coerceIn(0f, 0.5f); changed() }
        }
    }

    override fun onEncoder(delta: Int) {
        val e = ui.engine
        e.bpm = if (shift) (e.bpm + delta * 0.1).coerceIn(40.0, 240.0)
        else (Math.round(e.bpm) + delta).toDouble().coerceIn(40.0, 240.0)
        changed()
    }

    // =====================================================================================
    // screens
    // =====================================================================================

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        drawLeft(l)
        drawGrid(r)
    }

    private fun drawLeft(l: Canvas) {
        val e = ui.engine
        l.drawColor(Theme.BG)
        l.drawText(org.openmk3.core.Edition.title, 16f, 30f, title)
        l.drawText("v${Edition.version}", 464f, 30f, Theme.text(15f, Theme.DIM, align = Paint.Align.RIGHT))

        l.drawText(if (e.playing) "▶ PLAY" else "■ STOP", 16f, 74f, big)
        if (rec) l.drawText("● REC", 170f, 74f, recPaint)
        l.drawText("${"%.1f".format(e.bpm)} BPM", 300f, 74f, value.apply { textSize = 22f })
        value.textSize = 17f

        l.drawText(if (mode == Mode.STEP) "STEP MODE" else "PAD MODE", 16f, 108f, value)
        l.drawText("swing ${(pattern.swing * 200).toInt()}%", 170f, 108f, value)
        if (fixedVel) l.drawText("FIXED VEL", 300f, 108f, value)
        if (!flash) l.drawText("no flash", 400f, 108f, label)

        // selected sound
        l.drawRoundRect(RectF(16f, 122f, 40f, 146f), 4f, 4f, colourPaint[selected])
        l.drawText(e.kit[selected].name, 50f, 142f, value.apply { textSize = 20f })
        value.textSize = 17f
        val flags = buildString {
            if (pattern.mute[selected]) append("  muted")
            if (pattern.solo[selected]) append("  solo")
        }
        if (flags.isNotEmpty()) l.drawText(flags, 200f, 142f, label)

        val h = when {
            shift && erase -> "Pattern cleared"
            erase -> if (mode == Mode.STEP) "ERASE: hit steps to clear them" else "ERASE: hit a pad to clear that sound"
            select -> "SELECT: hit a pad to choose a sound"
            mute -> "MUTE: hit pads to mute / unmute"
            solo -> "SOLO: hit pads to solo / unsolo"
            mode == Mode.STEP -> "Pads = steps of ${e.kit[selected].name} · ◀ ▶ sound"
            rec -> "Recording: play the pads"
            else -> "K1 vol · K2 sens · K3 tempo · K4 swing"
        }
        l.drawText(h, 16f, 180f, hint)

        val p = power
        l.drawText(
            "${if (e.direct) "USB direct" else "Android audio"} ${"%.0f".format(e.bufferFrames * 1000.0 / 48000)} ms · glitches ${e.underruns}" +
                (p?.let { " · batt ${it.percent}% ${it.drainMa ?: "?"} mA" } ?: ""),
            16f, 230f, label
        )
        l.drawRect(16f, 250f, 464f, 262f, cell)
        l.drawRect(16f, 250f, 16f + 448f * e.meter.coerceIn(0f, 1f), 262f, if (e.meter > 0.95f) warnFill else ok)
    }

    /** [s] if it fits in [w] px, else "…" + as much of its end as fits. */
    private fun fitEnd(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var k = s.length - 1
        while (k > 1 && p.measureText("…" + s.takeLast(k)) > w) k--
        return "…" + s.takeLast(k)
    }

    private fun drawGrid(c: Canvas) {
        c.drawColor(Theme.BG)
        val p = pattern
        // 16 rows of 16 px under a 16 px playhead strip. The playhead is a small marker in
        // that strip, so each step only changes two tiny rectangles: far less USB traffic
        // to the MK3 than redrawing a full-height column every 16th note.
        val top = 16f
        val rowH = 16f
        val x0 = 80f
        val cw = 25f
        val step = ui.engine.currentStep
        for (t in 0 until Pattern.STEPS) {
            val x = x0 + t * cw
            c.drawRect(x + 1f, 6f, x + cw - 1f, 10f, if (t % 4 == 0) cellOffBeat else cellOff)
        }
        if (step >= 0) {
            val x = x0 + step * cw
            c.drawRect(x + 1f, 3f, x + cw - 1f, 13f, playheadFill)
        }
        for (s in 0 until Pattern.SOUNDS) {
            val y = top + s * rowH
            val audible = p.audible(s)
            if (s == selected) c.drawRect(0f, y, 480f, y + rowH, selRow)
            c.drawRect(2f, y + 3f, 7f, y + rowH - 3f, colourPaint[s])
            if (p.solo[s]) c.drawRect(9f, y + 3f, 12f, y + rowH - 3f, soloMark)
            // Clip to the label column; keep the end of long names (the distinctive part).
            c.save()
            c.clipRect(0f, y, x0 - 2f, y + rowH)
            val lp = if (audible) rowLabel else rowLabelDim
            c.drawText(fitEnd(ui.engine.kit[s].name, lp, x0 - 18f), 15f, y + 12f, lp)
            c.restore()
            for (t in 0 until Pattern.STEPS) {
                val x = x0 + t * cw
                val v = p.vel[s][t]
                val paint = when {
                    v > 0f && audible -> if (v > 0.5f) colourPaint[s] else dimPaint[s]
                    v > 0f -> cellOffBeat
                    (t / 4) % 2 == 0 -> cellOffBeat
                    else -> cellOff
                }
                c.drawRect(x + 1f, y + 2f, x + cw - 1f, y + rowH - 1f, paint)
            }
        }
    }
}

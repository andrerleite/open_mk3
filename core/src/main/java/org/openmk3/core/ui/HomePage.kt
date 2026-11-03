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
 *  ERASE + pad = clear that sound (pad mode) or that step (step mode)
 *  SHIFT + ERASE = REPLACE on / off: while recording, the first hit of a sound in each pass of
 *  the loop clears that sound's old notes
 *  PAGE ◀ ▶ = previous / next sound, ENCODER = tempo, K1 volume, K2 pad sensitivity,
 *  K3 tempo, K4 swing, SETTINGS = LED palette
 */
class HomePage(ui: Ui) : Page(ui) {

    override val handlesTransport = true

    private enum class Mode { PAD, STEP }

    @Volatile private var mode = Mode.PAD
    @Volatile private var shift = false
    private companion object { var replace = false }
    @Volatile private var erase = false
    @Volatile private var select = false
    @Volatile private var mute = false
    @Volatile private var solo = false
    private var rec = false
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
    /** Page of 16 steps shown on the pads (STEP) and the grid; follows the playhead with FOLLOW. */
    private var stepPage = 0
    private val pages get() = (pattern.length(ui.group) + 15) / 16
    private val viewPage: Int get() {
        val st = pattern.stepOf(ui.group)
        return (if (ui.follow && st >= 0) st / 16 else stepPage).coerceIn(0, pages - 1)
    }
    /** Playhead step within the page shown (-1 when not on it or stopped). */
    private val pageStep: Int get() {
        val st = pattern.stepOf(ui.group)
        return if (st >= 0 && st / 16 == viewPage) st % 16 else -1
    }
    // STEP pads follow the step grid (SHIFT + FOLLOW): 1/16 by default, finer or coarser
    private val g get() = ui.stepGrid
    private val stepPagesN get() = kotlin.math.ceil(pattern.length(ui.group) / (16f * g)).toInt().coerceAtLeast(1)
    private val stepViewPage: Int get() {
        val st = pattern.stepOf(ui.group)
        return (if (ui.follow && st >= 0) (st / (16f * g)).toInt() else stepPage).coerceIn(0, stepPagesN - 1)
    }
    /** Position (in 16ths) of a step pad. */
    private fun padPos(slot: Int) = (stepViewPage * 16 + stepForSlot(slot)) * g
    /** The step holding a note at [pos] (start matching within a hair), or null. */
    private fun noteAtPos(sound: Int, pos: Float): Int? {
        val c = kotlin.math.floor(pos).toInt()
        if (c >= pattern.length(ui.group)) return null
        return if (pattern.hasStep(sound, c) && kotlin.math.abs(pattern.noteOffset(sound, c) - (pos - c)) < 0.02f) c else null
    }
    /** Pad index (step order) under the playhead on the step page shown, or -1. */
    private val stepPlayPad: Int get() {
        val st = pattern.stepOf(ui.group)
        if (st < 0) return -1
        val i = kotlin.math.floor((st - stepViewPage * 16 * g) / g).toInt()
        return if (i in 0 until 16) i else -1
    }
    private var lastMeter = -1
    private val lastLed = IntArray(16) { -1 }
    private val pattern get() = ui.engine.pattern

    /** Pads only make sound in pad mode with no modifier held. Read from the input thread. */
    override val customPads = true

    /** MUTE / SOLO + group button mutes / solos the whole group; otherwise focuses it. */
    override fun onGroup(g: Int) {
        val p = pattern
        when {
            mute -> { p.groupMute[g] = !p.groupMute[g]; changed(); AppLog.i("Group ${'A' + g} ${if (p.groupMute[g]) "muted" else "unmuted"}") }
            solo -> { p.groupSolo[g] = !p.groupSolo[g]; changed(); AppLog.i("Group ${'A' + g} solo ${if (p.groupSolo[g]) "on" else "off"}") }
            else -> { ui.selectGroup(g); lastLed.fill(-1) }
        }
    }

    override val playsPads: Boolean
        get() = mode == Mode.PAD && !erase && !select && !mute && !solo && !shift && !duplicate

    // ---- look ----
    // Pads and rows use the focused group's colour.
    // Pads and rows use each sound's colour (its own, or its group's).
    private val colourPaints = Array(18) { Theme.fill(Mk3Colors.rgb(it)) }
    private val dimPaints = Array(18) { Theme.fill(mix(Mk3Colors.rgb(it), 0.35f)) }
    private fun paintOf(sound: Int) = colourPaints[ui.soundColour(sound).coerceIn(0, 17)]
    private fun dimOf(sound: Int) = dimPaints[ui.soundColour(sound).coerceIn(0, 17)]
    private val colourPaint get() = paintOf(sd(selected))
    private fun sd(slot: Int) = ui.snd(slot)
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
        ui.checkpoint()  // the finished edit becomes undoable
        dirtySince = 0L
    }

    private var engineRec = false

    override fun tick(now: Long): Boolean {
        var redraw = false
        val wantRec = rec && ui.engine.playing
        if (wantRec != engineRec) { engineRec = wantRec; ui.engine.setRecording(wantRec) }
        if (wantRec && ui.repeatActive) changed()  // engine writes repeat hits: autosave
        if (dirtySince != 0L && now - dirtySince > 1500) save()
        if (now - powerAt > 5000) {
            power = PowerMonitor.sample(ui.ctx)
            powerAt = now
            redraw = true
        }
        pattern.poll()
        if (ui.engine.takeRecorded()) { pattern.pullFromEngine(); changed() }
        val st = pattern.stepOf(ui.group) + 1000 * viewPage
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
        val step = pageStep
        for (slot in 0 until 16) {
            val c = ui.soundColour(sd(slot))
            val v = when (mode) {
                Mode.PAD -> if (mute || solo) {
                    // MUTE: audible pads fully lit, muted half lit · SOLO: soloed fully lit, the rest dim
                    val snd = sd(slot)
                    when {
                        !ui.hasSound(snd) -> 0
                        mute -> Mk3Colors.value(c, if (p.mute[snd]) 0 else 3)
                        else -> Mk3Colors.value(c, if (p.solo[snd]) 3 else 0)
                    }
                } else if (ui.padMode != Ui.PadMode.PAD) ui.keyPadLight(slot) else {
                    val hot = flash && (now - ui.engine.lastTrigMs[sd(slot)] in 0..120 || ui.padPressure[slot] > 0)
                    when {
                        !ui.hasSound(sd(slot)) -> 0
                        !p.audible(sd(slot)) -> if (hot) Mk3Colors.value(c, 0) else 0
                        hot -> Mk3Colors.value(c, 2)
                        slot == selected -> Mk3Colors.value(c, 1)
                        else -> Mk3Colors.value(c, 0)
                    }
                }
                Mode.STEP -> {
                    val s = stepForSlot(slot)
                    val pos = padPos(slot)
                    val cell = noteAtPos(sd(selected), pos)
                    val on = cell != null
                    val sc = ui.soundColour(sd(selected))
                    val head = s == stepPlayPad
                    when {
                        pos >= pattern.length(ui.group) -> 0
                        flash && head && on -> Mk3Colors.value(sc, 3)
                        flash && head -> Mk3Colors.value(Mk3Colors.WHITE, 0)
                        on -> Mk3Colors.value(sc, if (p.vel(sd(selected), cell!!) > 0.7f) 2 else 1)
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
        return any
    }

    override fun lights(l: Lights) {
        val e = ui.engine
        l.set("PLAY", e.playing)
        l.set("REC", rec)
        l.set("STOP", "RESTART", "SETTINGS", "PAGE LEFT", "PAGE RIGHT", "BROWSER", "SAMPLING")
        l.set("STEP", mode == Mode.STEP)
        l.set("PAD MODE", mode == Mode.PAD && ui.padMode == Ui.PadMode.PAD)
        l.set("KEYBOARD", mode == Mode.PAD && ui.padMode == Ui.PadMode.KEYBOARD)
        l.set("CHORDS", mode == Mode.PAD && ui.padMode == Ui.PadMode.CHORDS)
        l.set("ERASE", erase || (replace && (System.currentTimeMillis() / 500) % 2 == 0L))
        l.set("SELECT", select)
        l.set("MUTE", mute)
        l.set("SOLO", solo)
        l.set("SHIFT", shift)
        l.set("VOLUME", encMode == Enc.VOLUME || encMode == Enc.VELOCITY)
        l.set("SWING", encMode == Enc.SWING || encMode == Enc.POSITION)
        l.set("TEMPO", encMode == Enc.TEMPO || encMode == Enc.TUNE)
        l.set("DUPLICATE", duplicate)
        if (Edition.full) l.set("PLUG-IN")
    }

    /** Step i (0 = top-left, reading order) <-> pad slot (0 = bottom-left). Self-inverse. */
    private fun stepForSlot(slot: Int) = (3 - slot / 4) * 4 + slot % 4

    // =====================================================================================
    // input
    // =====================================================================================

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        val p = pattern
        when {
            shift && mode == Mode.PAD && ui.padMode == Ui.PadMode.PAD -> { shiftPad(slot) }
            duplicate -> {
                if (dupSource < 0) { dupSource = slot; message("Duplicate ${ui.padLabel(slot)} to… (hit the target pad)") }
                else { duplicateSound(dupSource, slot); dupSource = -1 }
            }
            select -> selected = slot
            mute -> { p.mute[sd(slot)] = !p.mute[sd(slot)]; changed() }
            solo -> { p.solo[sd(slot)] = !p.solo[sd(slot)]; changed() }
            mode == Mode.PAD && erase -> {
                p.clear(sd(slot)); changed()
                AppLog.i("Erased ${ui.padLabel(slot)} ${ui.engine.kit[sd(slot)].name}")
            }
            mode == Mode.PAD -> {
                if (ui.padMode == Ui.PadMode.PAD) selected = slot
                // NOTE REPEAT / ARP hits are recorded by the engine itself
                if (rec && ui.engine.playing && !ui.repeatActive) {
                    val v = ui.padVelocity(slot, pressure)
                    for ((snd, note) in ui.notesForPad(slot)) record(snd, v, note, slot)
                }
            }
            else -> { // STEP mode (pads = steps of the step grid)
                val pos = padPos(slot)
                if (pos >= pattern.length(ui.group)) return
                val sound = sd(selected)
                stepEdited = false
                val cell = noteAtPos(sound, pos)
                when {
                    erase -> { if (cell != null) { p.clearStep(sound, cell); changed() } }
                    // an existing step goes on release, unless it was edited while held
                    cell != null -> stepRemove[slot] = cell
                    else -> {
                        val c = kotlin.math.floor(pos).toInt()
                        if (p.hasStep(sound, c)) p.clearStep(sound, c)  // one note start per 16th cell
                        p.recordNote(sound, c, ui.keys.groupBase[ui.group], velocity, pos - c, g.coerceAtMost(1f))
                        changed()
                    }
                }
            }
        }
    }

    /** Quantise a live hit to the nearest step of what the player actually heard. */
    /** Notes being recorded per pad: sound, step and when they started (for their length). */
    private val recHeld = Array(16) { mutableListOf<Triple<Int, Int, Double>>() }

    /** Records a live hit where it was heard (off-grid; SHIFT + pad 5 quantises). */
    private fun record(slot: Int, velocity: Float, note: Int = Pattern.ROOT, pad: Int = -1) {
        val e = ui.engine
        val heard = e.phase - e.outputLatencySteps()
        if (heard < 0) return  // counting in
        val len = pattern.length(slot / Pattern.PADS)
        val whole = kotlin.math.floor(heard)
        val step = ((whole.toLong() % len + len) % len).toInt()
        val p = pattern
        if (replace) {
            // the first hit of this sound in this pass of the loop clears its old notes
            val pass = kotlin.math.floor(heard / len).toLong()
            if (replacePass[slot] != pass) {
                replacePass[slot] = pass
                p.clear(slot)
                for (r in recHeld) r.removeAll { it.first == slot }
            }
        }
        p.recordNote(slot, step, note, velocity, (heard - whole).toFloat(), 1f)
        if (pad >= 0) recHeld[pad].add(Triple(slot, step, heard))
        changed()
    }

    private val stepRemove = IntArray(16) { -1 }
    private val replacePass = HashMap<Int, Long>()

    override fun onPadRelease(slot: Int, hid: Int) {
        val rm = stepRemove[slot]
        if (rm >= 0) {
            stepRemove[slot] = -1
            if (!stepEdited) { pattern.clearStep(sd(selected), rm); changed() }
        }
        // a recorded note lasts as long as the pad was held
        val held = recHeld[slot]
        if (held.isEmpty()) return
        val e = ui.engine
        val now = e.phase - e.outputLatencySteps()
        for ((snd, step, start) in held) pattern.setNoteLength(snd, step, (now - start).toFloat().coerceAtLeast(0.1f))
        held.clear()
        changed()
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        when (name) {
            "SHIFT" -> shift = down
            "ERASE" -> {
                erase = down
                if (down && shift) {
                    replace = !replace
                    replacePass.clear()
                    message(if (replace) "Replace on: new takes replace a sound's notes" else "Replace off")
                }
            }
            "SELECT" -> select = down
            "DUPLICATE" -> {
                duplicate = down
                dupSource = -1
                if (down && shift) {  // SHIFT + DUPLICATE: double the pattern
                    message(if (pattern.double(ui.group)) "Pattern doubled: ${pattern.length(ui.group)} steps" else "Pattern is already 4 bars")
                    changed()
                }
            }
            "MUTE" -> mute = down
            "SOLO" -> solo = down
        }
        if (!down) return
        val e = ui.engine
        when (name) {
            "PLAY" -> if (e.playing) e.transportStop() else e.transportPlay()
            "STOP" -> { e.stopPressed(); rec = false }
            "RESTART" -> e.transportRestart()
            "REC" -> if (shift) {
                // SHIFT + REC: record after a one-bar count-in
                pattern.requestCountIn()
                rec = true
                if (e.playing) e.transportRestart() else e.transportPlay()
            } else { rec = !rec; if (rec && !e.playing) e.transportPlay() }
            // SHIFT + VOLUME = velocity, SHIFT + SWING = position of the focused sound's notes
            "VOLUME" -> encMode = if (shift) (if (encMode == Enc.VELOCITY) Enc.TEMPO else Enc.VELOCITY) else if (encMode == Enc.VOLUME) Enc.TEMPO else Enc.VOLUME
            "SWING" -> encMode = if (shift) (if (encMode == Enc.POSITION) Enc.TEMPO else Enc.POSITION) else if (encMode == Enc.SWING) Enc.TEMPO else Enc.SWING
            "TEMPO" -> encMode = if (shift) Enc.TUNE else Enc.TEMPO
            "STEP" -> mode = Mode.STEP  // PAD MODE / KEYBOARD / CHORDS leave it
            "KEYBOARD", "CHORDS" -> mode = Mode.PAD  // leave STEP: pads play notes / chords
            "D5", "D6", "D7", "D8" -> if (ui.padParams && ui.padMode == Ui.PadMode.PAD && mode == Mode.PAD) {
                val d = mapOf("D5" to -12, "D6" to -1, "D7" to 1, "D8" to 12)[name]!!
                ui.keys.groupBase[ui.group] = (ui.keys.groupBase[ui.group] + d).coerceIn(0, 127)
                ui.keys.save(ui.ctx)
            }
            "PAD MODE" -> mode = Mode.PAD
            "PAGE LEFT" -> if (mode == Mode.STEP) { stepPage = (stepViewPage - 1).coerceAtLeast(0); ui.follow = false } else selected = (selected + 15) % 16
            "PAGE RIGHT" -> if (mode == Mode.STEP) { stepPage = (stepViewPage + 1).coerceAtMost(stepPagesN - 1); ui.follow = false } else selected = (selected + 1) % 16
            "SETTINGS" -> ui.show(SettingsPage(ui))
            "BROWSER" -> ui.show(BrowserPage(ui))
            "SAMPLING" -> ui.show(SamplingPage(ui))
            "PLUG-IN" -> if (Edition.full) ui.show(SoundPage(ui))
        }
    }

    private var keyAcc = 0

    /** KEYBOARD / CHORDS knobs: K1 root, K2 scale, K3 octave; CHORDS: K5 mode, K6 type / set. */
    /** PAD MODE settings: K1 choke group, K2 link group (focused sound); D5-D8 base key. */
    private fun padSettingsKnob(knob: Int, delta: Int): Boolean {
        if (mode == Mode.STEP || !ui.padParams || ui.padMode != Ui.PadMode.PAD) return false
        keyAcc += delta
        if (kotlin.math.abs(keyAcc) < 25) return true
        val d = if (keyAcc > 0) 1 else -1
        keyAcc = 0
        val sp = ui.engine.params
        val snd = sd(selected)
        val idx = when (knob) { 0 -> org.openmk3.core.audio.SoundParams.CHOKE; 1 -> org.openmk3.core.audio.SoundParams.LINK; else -> return true }
        sp[snd, idx] = (sp[snd, idx] + d).coerceIn(0f, 16f)
        sp.save(ui.ctx)
        return true
    }

    private fun drawPadSettings(l: Canvas, r: Canvas) {
        val K = org.openmk3.core.audio.Keys
        val sp = ui.engine.params
        val e = ui.engine
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        val dl = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
        r.drawText("−OCT", 60f, 24f, dl); r.drawText("−1", 180f, 24f, dl); r.drawText("+1", 300f, 24f, dl); r.drawText("+OCT", 420f, 24f, dl)
        l.drawText("PAD MODE · GROUP ${ui.groupLetter}", 16f, 56f, title)
        l.drawText("Base key ${K.noteName(ui.keys.groupBase[ui.group])} (D5–D8)", 16f, 90f, value)
        l.drawRoundRect(RectF(16f, 104f, 40f, 128f), 4f, 4f, colourPaint)
        l.drawText("${ui.padLabel()}  ${e.kit[sd(selected)].name.ifEmpty { "(empty)" }}", 50f, 124f, value)
        l.drawText("Choke: a sound silences the others of its group", 16f, 160f, label)
        l.drawText("Link: linked pads play together", 16f, 180f, label)
        val choke = sp.display(sd(selected), org.openmk3.core.audio.SoundParams.CHOKE)
        val link = sp.display(sd(selected), org.openmk3.core.audio.SoundParams.LINK)
        knobLabel(l, 0, "CHOKE", choke)
        knobLabel(l, 1, "LINK", link)
        val w = 112f; val h = 44f
        for (slot in 0 until 16) {
            val col = slot % 4; val row = 3 - slot / 4
            val x = 8f + col * (w + 4f); val y = 40f + row * (h + 4f)
            val s = sd(slot)
            r.drawRoundRect(RectF(x, y, x + w, y + h), 5f, 5f, if (slot == selected) paintOf(s) else keyCell)
            r.drawText(fitEnd(e.kit[s].name.ifEmpty { "—" }, keyDim, w - 8f), x + w / 2, y + 18f, keyDim)
            val c = sp[s, org.openmk3.core.audio.SoundParams.CHOKE].toInt(); val k = sp[s, org.openmk3.core.audio.SoundParams.LINK].toInt()
            r.drawText((if (c > 0) "C$c " else "") + (if (k > 0) "L$k" else ""), x + w / 2, y + 36f, keyName)
        }
    }

    private fun keysKnob(knob: Int, delta: Int): Boolean {
        if (mode == Mode.STEP || !ui.padParams || (ui.padMode != Ui.PadMode.KEYBOARD && ui.padMode != Ui.PadMode.CHORDS)) return false
        keyAcc += delta
        if (kotlin.math.abs(keyAcc) < 25) return true
        val d = if (keyAcc > 0) 1 else -1
        keyAcc = 0
        val k = ui.keys
        when (knob) {
            0 -> k.root = Math.floorMod(k.root + d, 12)
            1 -> k.scale = (k.scale + d).coerceIn(0, org.openmk3.core.audio.Keys.SCALES.size - 1)
            2 -> k.octave = (k.octave + d).coerceIn(-1, 7)
            4 -> if (ui.padMode == Ui.PadMode.CHORDS) k.chordMode = (k.chordMode + d).coerceIn(0, 1)
            5 -> if (ui.padMode == Ui.PadMode.CHORDS) {
                if (k.chordMode == 0) k.chordType = (k.chordType + d).coerceIn(0, org.openmk3.core.audio.Keys.CHORD_TYPES.size - 1)
                else k.chordSet = (k.chordSet + d).coerceIn(0, org.openmk3.core.audio.Keys.CHORD_SETS.size - 1)
            }
            else -> return true
        }
        k.save(ui.ctx)
        return true
    }

    override fun onKnob(knob: Int, delta: Int) {
        if (keysKnob(knob, delta)) return
        if (padSettingsKnob(knob, delta)) return
        val e = ui.engine
        when (knob) {
            0 -> e.masterGain = (e.masterGain + delta / 300f).coerceIn(0f, 1.5f)
            1 -> ui.padFullScale = (ui.padFullScale - delta * 4).coerceIn(500, 4095)
            2 -> { e.bpm = (e.bpm + delta / 5.0).coerceIn(40.0, 240.0); changed() }
            3 -> { pattern.swing = (pattern.swing + delta / 400f).coerceIn(0f, 0.5f); changed() }
        }
    }

    /** What the encoder adjusts (VOLUME / SWING / TEMPO buttons; SHIFT + TEMPO = tune). */
    private enum class Enc { TEMPO, VOLUME, SWING, TUNE, VELOCITY, POSITION }
    private var encMode = Enc.TEMPO

    override fun onEncoder(delta: Int) {
        val e = ui.engine
        // STEP mode: hold a step and turn: VOLUME = velocity, SWING = position, TEMPO = pitch
        val heldStep = (0 until 16).firstOrNull { ui.padPressure[it] > 0 }
        if (mode == Mode.STEP && heldStep != null) {
            val snd = sd(selected)
            val st = noteAtPos(snd, padPos(heldStep)) ?: return
            when (encMode) {
                Enc.VOLUME -> pattern.editStep(snd, st, velDelta = delta * 0.03f)
                Enc.SWING -> pattern.editStep(snd, st, posDelta = delta * 0.05f)
                else -> pattern.editStep(snd, st, semis = delta)
            }
            stepEdited = true
            changed()
            return
        }
        // hold a pad or a group button: VOLUME / TEMPO change that sound or group only
        val heldPad = (0 until 16).firstOrNull { ui.padPressure[it] > 0 }
        val sp = e.params
        val k = if (shift) 0.1f else 1f
        if (heldPad != null && (encMode == Enc.VOLUME || encMode == Enc.TEMPO || encMode == Enc.TUNE)) {
            val snd = sd(heldPad)
            if (encMode == Enc.VOLUME) sp[snd, org.openmk3.core.audio.SoundParams.VOL] = (sp[snd, org.openmk3.core.audio.SoundParams.VOL] + delta * 0.5f * k).coerceIn(-60f, 12f)
            else sp[snd, org.openmk3.core.audio.SoundParams.TUNE] = (sp[snd, org.openmk3.core.audio.SoundParams.TUNE] + delta * k).coerceIn(-36f, 36f)
            sp.save(ui.ctx)
            return
        }
        if (ui.heldGroup >= 0 && (encMode == Enc.VOLUME || encMode == Enc.TEMPO || encMode == Enc.TUNE)) {
            val g = ui.heldGroup
            if (encMode == Enc.VOLUME) pattern.setGroupVolume(g, pattern.groupVolume(g) + delta * 0.5f * k)
            else for (i in 0 until 16) sp[g * 16 + i, org.openmk3.core.audio.SoundParams.TUNE] = (sp[g * 16 + i, org.openmk3.core.audio.SoundParams.TUNE] + delta * k).coerceIn(-36f, 36f)
            sp.save(ui.ctx); changed()
            return
        }
        when (encMode) {
            Enc.TEMPO -> e.bpm = if (shift) (e.bpm + delta * 0.1).coerceIn(40.0, 240.0)
                else (Math.round(e.bpm) + delta).toDouble().coerceIn(40.0, 240.0)
            Enc.VOLUME -> e.masterGain = (e.masterGain + delta * 0.02f).coerceIn(0f, 1.5f)
            Enc.SWING -> pattern.swing = (pattern.swing + delta * 0.01f).coerceIn(0f, 0.5f)
            Enc.VELOCITY -> pattern.addVelocity(setOf(sd(selected)), delta * (if (shift) 0.005f else 0.03f))
            Enc.POSITION -> pattern.move(setOf(sd(selected)), delta * (if (shift) 0.05f else ui.nudgeGrid))
            Enc.TUNE -> {
                val sp = e.params
                val snd = sd(selected)
                sp[snd, org.openmk3.core.audio.SoundParams.TUNE] =
                    (sp[snd, org.openmk3.core.audio.SoundParams.TUNE] + delta * (if (shift) 0.1f else 1f)).coerceIn(-36f, 36f)
                sp.save(ui.ctx)
            }
        }
        changed()
    }

    private var stepEdited = false

    // ---- DUPLICATE in pad mode, SHIFT + pad edits ----
    private var duplicate = false
    private var dupSource = -1
    private var msg = ""
    private var msgAt = 0L

    private fun message(t: String) {
        msg = t
        msgAt = android.os.SystemClock.uptimeMillis()
        AppLog.i(t)
    }

    /** DUPLICATE + pad + pad: copies a sound (sample, settings, colour and its notes). */
    private fun duplicateSound(from: Int, to: Int) {
        val e = ui.engine
        val a = sd(from)
        val b = sd(to)
        e.setSample(b, e.kit[a])
        for (i in 0 until org.openmk3.core.audio.SoundParams.COUNT) e.params[b, i] = e.params[a, i]
        e.poly.copy(a, b)
        e.zones.copy(a, b)
        e.zones.save(ui.ctx)
        e.poly.save(ui.ctx)
        pattern.copySound(a, b)
        e.params.save(ui.ctx)
        org.openmk3.core.audio.KitStore.save(ui.ctx, e.kit)
        changed()
        message("${ui.padLabel(from)} → ${ui.padLabel(to)}")
    }

    /** SHIFT + pads (labels printed on the pads). Returns true when handled. */
    private fun shiftPad(slot: Int): Boolean {
        val g = ui.group
        val snd = sd(selected)
        when (slot) {
            0, 2 -> message(if (ui.undo()) "Undo" else "Nothing to undo")
            1, 3 -> message(if (ui.redo()) "Redo" else "Nothing to redo")
            4, 5 -> {
                // SHIFT + pad 5: quantise the focused sound (pad 6: half way); with SELECT held: the group
                val sounds = if (select) (0 until 16).map { sd(it) } else listOf(snd)
                pattern.quantize(sounds, if (slot == 4) 1f else 0.5f)
                changed()
                message(if (slot == 4) "Quantised" else "Quantised 50 %")
            }
            6 -> { pattern.nudge(snd, -1); changed(); message("${ui.padLabel()} nudged left") }
            7 -> { pattern.nudge(snd, 1); changed(); message("${ui.padLabel()} nudged right") }
            8 -> { pattern.clearGroup(g); changed(); message("Pattern cleared") }
            9 -> { pattern.clearAutomation(g); changed(); message("Automation cleared (group ${ui.groupLetter})") }
            10 -> { ui.clipboard = pattern.copyOf(g); message(if (ui.clipboard != null) "Pattern copied" else "No pattern to copy") }
            11 -> ui.clipboard?.let { pattern.paste(g, it); changed(); message("Pattern pasted") } ?: message("Clipboard is empty")
            12 -> { pattern.transpose(snd, -1); changed(); message("${ui.padLabel()} notes −1 semitone") }
            13 -> { pattern.transpose(snd, 1); changed(); message("${ui.padLabel()} notes +1 semitone") }
            14 -> { pattern.transpose(snd, -12); changed(); message("${ui.padLabel()} notes −1 octave") }
            15 -> { pattern.transpose(snd, 12); changed(); message("${ui.padLabel()} notes +1 octave") }
            else -> return false
        }
        return true
    }

    // =====================================================================================
    // screens
    // =====================================================================================

    private val keyCell = Theme.fill(Color.rgb(36, 36, 46))
    private val keyName = Theme.text(15f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val keyDim = Theme.text(12f, Theme.DIM, align = Paint.Align.CENTER)
    private val knobName = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val knobVal = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)

    private fun knobLabel(c: Canvas, i: Int, name: String, v: String) {
        val x = 60f + (i % 4) * 120f
        c.drawText(name, x, 238f, knobName)
        c.drawText(v, x, 260f, knobVal)
    }

    /** KEYBOARD / CHORDS / 16 VEL: settings on the left, the pads' notes on the right. */
    private fun drawKeys(l: Canvas, r: Canvas) {
        val k = ui.keys
        val K = org.openmk3.core.audio.Keys
        val e = ui.engine
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        val modeName = when (ui.padMode) {
            Ui.PadMode.KEYBOARD -> "KEYBOARD"
            Ui.PadMode.CHORDS -> "CHORDS"
            else -> "16 VELOCITIES"
        }
        l.drawText(modeName, 16f, 30f, title)
        l.drawText(if (e.playing) "▶ PLAY" else "■ STOP", 330f, 30f, value)
        if (rec) l.drawText("● REC", 410f, 30f, Theme.text(17f, Theme.WARN, bold = true))
        l.drawRoundRect(RectF(16f, 46f, 40f, 70f), 4f, 4f, colourPaint)
        l.drawText("${ui.padLabel()}  ${e.kit[sd(selected)].name.ifEmpty { "(empty)" }}", 50f, 66f, value)
        if (ui.padMode != Ui.PadMode.VEL16) {
            l.drawText("Base ${K.noteName(k.baseNote)} · ${K.SCALES[k.scale].first}", 16f, 104f, value)
            l.drawText("SHIFT + pad 13/14: semitone −/+ · 15/16: octave −/+", 16f, 134f, label)
            if (ui.padMode == Ui.PadMode.CHORDS)
                l.drawText(if (k.chordMode == 0) "Harmonizer: chords built from the scale" else "Chord set: ${K.CHORD_SETS[k.chordSet].first}", 16f, 160f, label)
        } else {
            l.drawText("Pads play the focused sound at 16 velocities", 16f, 104f, label)
        }
        if (ui.repeatActive) l.drawText(if (ui.repeatKind == Ui.RepeatKind.ARP) "ARP ${Ui.REPEAT_RATES[ui.repeatRate]}" else "NOTE REPEAT ${Ui.REPEAT_RATES[ui.repeatRate]}", 16f, 190f, hint)
        if (ui.padMode != Ui.PadMode.VEL16) {
            knobLabel(l, 0, "ROOT", K.NOTE_NAMES[k.root])
            knobLabel(l, 1, "SCALE", K.SCALES[k.scale].first)
            knobLabel(l, 2, "OCTAVE", "${k.octave}")
            if (ui.padMode == Ui.PadMode.CHORDS) {
                knobLabel(r, 4, "CHORD MODE", if (k.chordMode == 0) "HARMONIZER" else "CHORD SET")
                if (k.chordMode == 0) knobLabel(r, 5, "TYPE", K.CHORD_TYPES[k.chordType].first)
                else knobLabel(r, 5, "SET", K.CHORD_SETS[k.chordSet].first)
            }
        }
        // the 16 pads, laid out like the hardware (pad 13–16 on top)
        val w = 112f
        val h = 44f
        val gc = Mk3Colors.rgb(ui.soundColour(sd(selected)))
        val hot = Theme.fill(gc)
        val rootFill = Theme.fill(mix(gc, 0.5f))
        for (slot in 0 until 16) {
            val col = slot % 4
            val row = 3 - slot / 4
            val x = 8f + col * (w + 4f)
            val y = 8f + row * (h + 4f)
            val (main, sub) = when (ui.padMode) {
                Ui.PadMode.KEYBOARD -> K.noteName(k.noteFor(slot)) to ""
                Ui.PadMode.CHORDS -> k.chordName(slot) to k.chordFor(slot).joinToString(" ") { K.noteName(it) }
                else -> "${((slot + 1) * 100 / 16)}%" to "vel"
            }
            val isRoot = when (ui.padMode) {
                Ui.PadMode.KEYBOARD -> k.isRoot(k.noteFor(slot))
                Ui.PadMode.CHORDS -> k.isRoot(k.chordFor(slot)[0])
                else -> false
            }
            val fill = if (ui.padPressure[slot] > 0) hot else if (isRoot) rootFill else keyCell
            r.drawRoundRect(RectF(x, y, x + w, y + h), 5f, 5f, fill)
            r.drawText(main, x + w / 2, y + 21f, keyName)
            if (sub.isNotEmpty()) r.drawText(sub, x + w / 2, y + 37f, keyDim)
        }
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        if (mode != Mode.STEP && ui.padParams && ui.padMode == Ui.PadMode.PAD) { drawPadSettings(l, r); return }
        if (mode != Mode.STEP && ui.padParams && ui.padMode != Ui.PadMode.PAD) { drawKeys(l, r); return }
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
        if (ui.fixedVel) l.drawText("FIXED VEL", 300f, 108f, value)
        if (!flash) l.drawText("no flash", 400f, 108f, label)

        // selected sound
        l.drawRoundRect(RectF(16f, 122f, 40f, 146f), 4f, 4f, colourPaint)
        l.drawText("${ui.padLabel()}  ${e.kit[sd(selected)].name.ifEmpty { "(empty)" }}", 50f, 142f, value.apply { textSize = 20f })
        value.textSize = 17f
        val flags = buildString {
            if (pattern.mute[sd(selected)]) append("  muted")
            if (pattern.solo[sd(selected)]) append("  solo")
            if (!pattern.groupAudible(ui.group)) append("  group muted")
        }
        if (flags.isNotEmpty()) l.drawText(flags, 200f, 142f, label)

        val h = when {
            msg.isNotEmpty() && android.os.SystemClock.uptimeMillis() - msgAt < 2500 -> msg
            pattern.countingIn -> "Count-in…"
            duplicate -> "DUPLICATE: hit the source pad, then the target"
            shift && mode == Mode.PAD -> "SHIFT + pad: 1 undo · 2 redo · 7/8 nudge · 9 clear · 11/12 copy/paste"
            encMode == Enc.VOLUME -> "Encoder: master volume"
            encMode == Enc.SWING -> "Encoder: swing"
            encMode == Enc.VELOCITY -> "Encoder: velocity of ${ui.padLabel()}'s notes"
            encMode == Enc.POSITION -> "Encoder: move ${ui.padLabel()}'s notes (nudge grid)"
            encMode == Enc.TUNE -> "Encoder: tune ${ui.padLabel()}"
            shift && erase -> "Pattern cleared"
            erase -> if (mode == Mode.STEP) "ERASE: hit steps to clear them" else "ERASE: hit a pad to clear that sound"
            select -> "SELECT: hit a pad to choose a sound"
            mute -> "MUTE: hit pads to mute / unmute"
            solo -> "SOLO: hit pads to solo / unsolo"
            mode == Mode.STEP -> "Pads = steps of ${ui.padLabel()} · ◀ ▶ sound"
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
        val step = pageStep
        val page = viewPage
        val len = pattern.length(ui.group)
        for (t in 0 until 16) {
            val x = x0 + t * cw
            c.drawRect(x + 1f, 6f, x + cw - 1f, 10f, if (t % 4 == 0) cellOffBeat else cellOff)
        }
        // pattern / page indicator
        val pi = pattern.current[ui.group]
        c.drawText((if (pi >= 0) "P${pi + 1}" else "—") + if (pages > 1) " ${page + 1}/$pages" else "", 2f, 12f, rowLabelDim)
        if (step >= 0) {
            val x = x0 + step * cw
            c.drawRect(x + 1f, 3f, x + cw - 1f, 13f, playheadFill)
        }
        for (row in 0 until Pattern.PADS) {
            val s = sd(row)
            val y = top + row * rowH
            val audible = p.audible(s)
            if (row == selected) c.drawRect(0f, y, 480f, y + rowH, selRow)
            c.drawRect(2f, y + 3f, 7f, y + rowH - 3f, paintOf(s))
            if (p.solo[s]) c.drawRect(9f, y + 3f, 12f, y + rowH - 3f, soloMark)
            // Clip to the label column; keep the end of long names (the distinctive part).
            c.save()
            c.clipRect(0f, y, x0 - 2f, y + rowH)
            val lp = if (audible) rowLabel else rowLabelDim
            c.drawText(fitEnd(ui.engine.kit[s].name.ifEmpty { "—" }, lp, x0 - 18f), 15f, y + 12f, lp)
            c.restore()
            for (tt in 0 until 16) {
                val t = page * 16 + tt
                val x = x0 + tt * cw
                if (t >= len) { c.drawRect(x + 1f, y + 2f, x + cw - 1f, y + rowH - 1f, cellOff); continue }
                val v = if (p.hasStep(s, t)) maxOf(p.vel(s, t), 0.4f) else 0f
                val paint = when {
                    v > 0f && audible -> if (v > 0.5f) paintOf(s) else dimOf(s)
                    v > 0f -> cellOffBeat
                    (tt / 4) % 2 == 0 -> cellOffBeat
                    else -> cellOff
                }
                c.drawRect(x + 1f, y + 2f, x + cw - 1f, y + rowH - 1f, paint)
            }
        }
    }
}

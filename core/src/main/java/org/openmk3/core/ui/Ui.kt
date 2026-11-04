package org.openmk3.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import org.openmk3.core.AppLog
import org.openmk3.core.audio.AudioEngine
import org.openmk3.core.mk3.Mk3Device
import org.openmk3.core.mk3.Mk3Listener
import org.openmk3.core.mk3.Mk3Map
import org.openmk3.core.mk3.Mk3Screen
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Owns the MK3 "UI": routes hardware events to the active [Page] on a single UI thread,
 * and redraws both screens (sending only what changed) when something is dirty.
 */
class Ui(val ctx: Context, val dev: Mk3Device, val engine: AudioEngine) : Mk3Listener {

    val map: Mk3Map = Mk3Map.load(ctx)
    init { Colours.load(ctx) }

    /** Colour of a sound: its own, or its group's (COLOUR = AUTO). */
    fun soundColour(sound: Int): Int {
        val c = engine.params[sound, org.openmk3.core.audio.SoundParams.COLOUR].toInt()
        return if (c in 1..16) c else groupColour(sound / 16)
    }

    /** True if a sound makes sound: it has a sample, or its source is the synth. */
    fun hasSound(sound: Int) = engine.kit[sound].frames > 0 || engine.params[sound, org.openmk3.core.audio.SoundParams.SRC] > 0.5f

    /** SHIFT, held on any page (for global SHIFT + group / SELECT shortcuts). */
    @Volatile var shiftHeld = false
        private set
    val left = Mk3Screen(0)
    val right = Mk3Screen(1)
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mk3-ui").apply { priority = Thread.MAX_PRIORITY - 1 }
    }

    @Volatile var page: Page = org.openmk3.core.Edition.homePage(this)
        private set
    @Volatile private var dirty = true

    /** Pad hit pressure that counts as full velocity (adjustable on the home page). */
    @Volatile var padFullScale = 3000
    var lastFrameBytes = 0
        private set
    var framesSent = 0L
        private set

    // Per-pad state, indexed by physical slot (0 = bottom-left).
    val padPressure = IntArray(16)
    val padFlash = LongArray(16)
    /** The pad being edited / loaded into (slot, 0 = bottom-left), within the focused group. */
    @Volatile var selectedSound = 0
    /** Focused group, 0..7 = A..H. Pads play and edit its 16 sounds. */
    @Volatile var group = 0
        private set
    /** FIXED VEL: pads play at full velocity (global, like the hardware workflow). */
    @Volatile var fixedVel = false
    // ---- undo history (song snapshots after each edit) and the pattern clipboard ----
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var lastState: String? = null
    var clipboard: org.openmk3.core.audio.PatternData? = null

    /** Call after a finished edit: the previous state becomes undoable. */
    fun checkpoint() {
        val now = engine.pattern.toJson().toString()
        if (now == lastState) return
        lastState?.let { undoStack.addLast(it); if (undoStack.size > 50) undoStack.removeFirst() }
        redoStack.clear()
        lastState = now
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        lastState?.let { redoStack.addLast(it) }
        restore(prev)
        AppLog.i("Undo")
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        lastState?.let { undoStack.addLast(it) }
        restore(next)
        AppLog.i("Redo")
        return true
    }

    private fun restore(state: String) {
        engine.pattern.loadJson(org.json.JSONObject(state))
        engine.pattern.save(ctx)
        lastState = state
        dirty = true
    }

    // ---- TAP tempo ----
    private val taps = ArrayDeque<Long>()

    private fun tap() {
        val now = SystemClock.uptimeMillis()
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        taps.addLast(now)
        if (taps.size > 5) taps.removeFirst()
        if (taps.size >= 2) {
            val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
            engine.bpm = (60000.0 / avg).coerceIn(40.0, 240.0)
            engine.pattern.save(ctx)
            AppLog.i("Tap tempo ${"%.1f".format(engine.bpm)} BPM")
        }
    }


    /** SHIFT + FILE: save the current project (or a new one). */
    private fun quickSave() {
        try {
            val name = org.openmk3.core.audio.Project.currentName(ctx) ?: org.openmk3.core.audio.Project.nextName()
            org.openmk3.core.audio.Project.save(ctx, engine, Colours.group, keys, name)
        } catch (e: Exception) {
            AppLog.i("Quick save failed: $e")
        }
    }

    // ---- touch strip: PITCH bend, MOD wheel, PERFORM (group filter), NOTES (strum) ----
    enum class StripMode { OFF, PITCH, MOD, PERFORM, NOTES }
    @Volatile var stripMode = StripMode.PITCH
    private var stripMin = Int.MAX_VALUE
    private var stripMax = Int.MIN_VALUE
    private var stripLogged = 0
    private var stripTouched = false
    private var stripAt = 0L
    private var stripValue = 0.5f
    private var stripNote = -1
    private var stripNoteSound = -1
    private var stripLedsShown = -2

    /** 0..1 from the raw position, calibrating to the range seen so far. */
    private fun stripNorm(raw: Int): Float {
        if (raw < stripMin) stripMin = raw
        if (raw > stripMax) stripMax = raw
        val span = (stripMax - stripMin).coerceAtLeast(200)
        return ((raw - stripMin).toFloat() / span).coerceIn(0f, 1f)
    }

    private fun stripMove(raw: Int) {
        if (stripLogged < 12) { stripLogged++; AppLog.i("Touch strip raw $raw") }
        val v = stripNorm(raw)
        stripTouched = true
        stripAt = SystemClock.uptimeMillis()
        stripValue = v
        val sp = engine.params
        when (stripMode) {
            StripMode.PITCH -> sp[selectedIndex, org.openmk3.core.audio.SoundParams.BEND] = (v - 0.5f) * 2f * sp[selectedIndex, org.openmk3.core.audio.SoundParams.PBRANGE]  // ± bend range
            StripMode.MOD -> sp[selectedIndex, org.openmk3.core.audio.SoundParams.MODW] = v
            StripMode.PERFORM -> if (engine.pattern.performType == 0) engine.pattern.setGroupFilter(group, (v - 0.5f) * 2f)
                else engine.pattern.setPerform(true, v)
            StripMode.NOTES -> {
                val i = (v * 15.99f).toInt()
                val note = keys.noteFor(i)
                if (note != stripNote) {
                    if (stripNote >= 0) engine.noteOff(stripNoteSound, stripNote)
                    stripNoteSound = selectedIndex
                    engine.noteOn(stripNoteSound, note, 0.8f)
                    stripNote = note
                }
            }
            StripMode.OFF -> {}
        }
    }

    /** No strip events for a moment = finger lifted: bend springs back, performance FX off. */
    private fun stripRelease() {
        stripTouched = false
        val sp = engine.params
        when (stripMode) {
            StripMode.PITCH -> { sp[selectedIndex, org.openmk3.core.audio.SoundParams.BEND] = 0f; stripValue = 0.5f }
            StripMode.PERFORM -> { engine.pattern.setGroupFilter(group, 0f); engine.pattern.setPerform(false, 0f); stripValue = 0.5f }
            StripMode.NOTES -> { if (stripNote >= 0) engine.noteOff(stripNoteSound, stripNote); stripNote = -1 }
            else -> {}
        }
    }

    private fun stripLeds() {
        val pos = (stripValue * 24.99f).toInt()
        val key = stripMode.ordinal * 100 + pos + if (stripTouched) 1000 else 0
        if (key == stripLedsShown) return
        stripLedsShown = key
        val c = soundColour(selectedIndex)
        for (i in 0 until 25) {
            val v = when (stripMode) {
                StripMode.OFF -> 0
                StripMode.MOD -> if (i <= pos) org.openmk3.core.mk3.Mk3Colors.value(c, 2) else 0
                StripMode.PITCH, StripMode.PERFORM -> when {
                    i == pos -> org.openmk3.core.mk3.Mk3Colors.value(c, 3)
                    i == 12 -> org.openmk3.core.mk3.Mk3Colors.value(org.openmk3.core.mk3.Mk3Colors.WHITE, 0)
                    else -> 0
                }
                StripMode.NOTES -> if (stripTouched && i == pos) org.openmk3.core.mk3.Mk3Colors.value(c, 3) else if (i % 3 == 0) org.openmk3.core.mk3.Mk3Colors.value(c, 0) else 0
            }
            dev.setPadLed(i, v)
        }
    }

    /** Step grid (16ths per STEP pad), nudge grid (EVENTS position) and arrange grid (pattern length). */
    @Volatile var stepGrid = 1f
    @Volatile var nudgeGrid = 1f
    @Volatile var arrangeGrid = 16

    // ---- MACRO: 8 macros per group, each (sound, parameter); saved as macros.json ----
    val macros = Array(8) { IntArray(16) { -1 } }
    /** Macro waiting for its parameter (group * 8 + macro), -1 = none. */
    @Volatile var macroLearn = -1

    private fun macroFile() = java.io.File(ctx.filesDir, "macros.json")
    fun saveMacros() {
        try { macroFile().writeText(org.json.JSONArray(macros.map { org.json.JSONArray(it.toList()) }).toString()) } catch (_: Exception) {}
    }
    private fun loadMacros() {
        try {
            val f = macroFile()
            if (!f.exists()) return
            val a = org.json.JSONArray(f.readText())
            for (g in 0 until minOf(8, a.length())) { val r = a.getJSONArray(g); for (i in 0 until minOf(16, r.length())) macros[g][i] = r.getInt(i) }
        } catch (_: Exception) {}
    }
    init { loadMacros() }

    // ---- AUTO: knob moves are recorded into the playing pattern ----
    @Volatile var autoHeld = false
    @Volatile var autoPinned = false
    val autoActive get() = autoHeld || autoPinned
    /** ERASE held (on any page): turning a knob removes that parameter's automation. */
    @Volatile var eraseHeld = false

    /** Call after a sound parameter changed on a knob: records or erases its automation. */
    fun autoWrite(sound: Int, param: Int) {
        // MACRO set: the parameter just turned becomes the waiting macro's target
        if (macroLearn >= 0) {
            val g = macroLearn / 8; val i = macroLearn % 8
            macros[g][i * 2] = sound; macros[g][i * 2 + 1] = param
            macroLearn = -1
            saveMacros()
            AppLog.i("Macro ${i + 1} (group ${'A' + g}) = ${org.openmk3.core.audio.soundLabel(sound)} ${org.openmk3.core.audio.SoundParams.DEFS[param]?.name}")
            show(MacroPage(this, true))
            return
        }
        val p = engine.pattern
        if (eraseHeld) { if (p.hasAutomation(sound, param)) { p.clearAutomation(sound / 16, sound, param); p.save(ctx) }; return }
        if (!autoActive || !engine.playing) return
        val st = p.stepOf(sound / 16)
        if (st < 0) return
        if (!p.writeAutomation(sound, param, st, engine.params[sound, param])) AppLog.i("AUTO: 16 lanes per pattern are in use")
    }

    /** FOLLOW: step pages follow the playhead. */
    @Volatile var follow = true
    /** Snap sample markers (start, end, loop, slices) to zero crossings. */
    @Volatile var zeroSnap = false

    /** Sound index (0..127) of pad [slot] in the focused group. */
    fun snd(slot: Int) = group * org.openmk3.core.audio.Pattern.PADS + slot
    /** Sound index of the focused pad. */
    val selectedIndex get() = snd(selectedSound)
    val groupLetter get() = 'A' + group
    /** e.g. "B7" for the focused pad. */
    fun padLabel(slot: Int = selectedSound) = "$groupLetter${slot + 1}"

    @Volatile var dupHeld = false
    @Volatile var selectHeld = false
    private var dupGroup = -1

    /** Copies a whole group: sounds (samples, settings, synth patches, zones) and patterns. */
    fun groupCopy(from: Int, to: Int) {
        if (from == to) return
        val e = engine
        for (k in 0 until 16) {
            val a = from * 16 + k; val b = to * 16 + k
            e.setSample(b, e.kit[a])
            for (i in 0 until org.openmk3.core.audio.SoundParams.COUNT) e.params[b, i] = e.params[a, i]
            e.poly.copy(a, b)
            e.zones.copy(a, b)
        }
        e.pattern.copyGroup(from, to)
        Colours.group[to] = Colours.group[from]
        saveAll()
        AppLog.i("Group ${'A' + from} copied to ${'A' + to}")
    }

    /** Clears a group: its sounds go back to the built-in kit and its patterns are removed. */
    fun groupClear(g: Int) {
        val e = engine
        for (k in 0 until 16) {
            val s = g * 16 + k
            e.resetSlot(s)
            e.params.reset(s)
            e.poly.reset(s)
            e.zones.clear(s)
        }
        e.pattern.removeGroup(g)
        saveAll()
        AppLog.i("Group ${'A' + g} cleared")
    }

    private fun saveAll() {
        val e = engine
        e.params.save(ctx); e.poly.save(ctx); e.zones.save(ctx); e.pattern.save(ctx)
        org.openmk3.core.audio.KitStore.save(ctx, e.kit)
        checkpoint()
    }

    fun selectGroup(g: Int) {
        group = g.coerceIn(0, 7)
        AppLog.i("Group ${'A' + group}")
        dirty = true
    }

    // ---- pad modes (buttons above the pads), global like on the hardware ----
    enum class PadMode { PAD, KEYBOARD, CHORDS, VEL16 }
    @Volatile var padMode = PadMode.PAD
        private set
    val keys: org.openmk3.core.audio.Keys = org.openmk3.core.audio.Keys.load(ctx)
    val lock = org.openmk3.core.audio.Lock(engine)

    /** The (sound, note) pairs pad [slot] plays in the current pad mode. */
    fun notesForPad(slot: Int): List<Pair<Int, Int>> = when (padMode) {
        PadMode.PAD -> {
            // the pad, plus every sound of the group in the same link group
            val base = keys.groupBase[group]
            val me = snd(slot)
            val link = engine.params[me, org.openmk3.core.audio.SoundParams.LINK].toInt()
            if (link <= 0) listOf(me to base)
            else (0 until 16).map { snd(it) }.filter { it == me || engine.params[it, org.openmk3.core.audio.SoundParams.LINK].toInt() == link }.map { it to base }
        }
        PadMode.KEYBOARD -> listOf(selectedIndex to keys.noteFor(slot))
        PadMode.CHORDS -> keys.chordFor(slot).map { selectedIndex to it }
        PadMode.VEL16 -> listOf(selectedIndex to keys.groupBase[group])
    }

    /** 16 VEL: pad 1 is the softest, pad 16 the hardest. */
    fun padVelocity(slot: Int, pressure: Int) =
        if (padMode == PadMode.VEL16) (slot + 1) / 16f else velocityFor(pressure)

    private fun setPadMode(m: PadMode) {
        padMode = m
        AppLog.i("Pad mode: $m")
        dirty = true
    }

    // ---- NOTE REPEAT / ARP ----
    enum class RepeatKind { REPEAT, ARP }
    @Volatile var repeatKind = RepeatKind.REPEAT
    @Volatile var repeatLatched = false
    @Volatile var repeatHeld = false
    val repeatActive get() = repeatHeld || repeatLatched
    /** Four rate presets on D5-D8 (indices into REPEAT_RATES); K3 changes the selected one. */
    val repeatPresets = intArrayOf(2, 4, 6, 5)
    var repeatPreset = 1
    val repeatRate get() = repeatPresets[repeatPreset]
    var repeatGate = 1f
    var arpType = 0
    var arpOctaves = 1
    /** K4 rhythm (which ticks play) and K5 dynamics (velocity shape), for repeat and arp. */
    var repeatRhythm = 0
    var repeatDyn = 0
    /** Arp HOLD: notes keep playing after the pads are released. */
    var arpHold = false
    private val arpHeldNotes = mutableListOf<Pair<Int, Int>>()
    private var repeatAcc = 0

    /** Modes that stay on after their button is released (held mode button + D1). */
    val pinned = mutableSetOf<String>()
    /** The left-column mode button being held (PATTERN / SCENE / NOTE REPEAT), for D1 pinning. */
    private var modeHeld: String? = null
    /** Pad-mode settings (KEYBOARD / CHORDS / 16 VEL) shown on the screens. */
    @Volatile var padParams = false
    /** GROUP button held (for VOLUME / TEMPO + encoder on that group), -1 = none. */
    @Volatile var heldGroup = -1

    companion object {
        val REPEAT_RATES = listOf("1/4", "1/4T", "1/8", "1/8T", "1/16", "1/16T", "1/32", "1/32T")
        /** Rates in 16th steps. */
        val REPEAT_STEPS = floatArrayOf(4f, 8f / 3f, 2f, 4f / 3f, 1f, 2f / 3f, 0.5f, 1f / 3f)
        val ARP_TYPES = listOf("UP", "DOWN", "UP-DOWN", "RANDOM", "AS PLAYED", "CHORD")
        /** Same order as kRhythms in the engine: ● plays, ○ rests. */
        val RHYTHMS = listOf("x", "x.", "xx.", "x.x", "xxx.", "x..x..x.", "xx.x.xx.", "x...x.x.").map { it.replace('x', '●').replace('.', '○') }
        val DYNAMICS = listOf("AS PLAYED", "FULL", "ACCENT", "RAMP UP", "RAMP DOWN", "RANDOM")
    }

    private fun pushRepeat() {
        // in KEYBOARD / CHORDS the repeat engine is the arpeggiator
        repeatKind = if (padMode == PadMode.KEYBOARD || padMode == PadMode.CHORDS) RepeatKind.ARP else RepeatKind.REPEAT
        if (!repeatActive && arpHold) applyArpHold(false)
        val mode = if (!repeatActive) 0 else if (repeatKind == RepeatKind.ARP) 2 else 1
        engine.repeatShape(repeatDyn, repeatRhythm)
        engine.repeatConfig(mode, REPEAT_STEPS[repeatRate], repeatGate, arpType, arpOctaves)
        dirty = true
    }

    private fun applyArpHold(on: Boolean) {
        arpHold = on
        if (!on) {  // let go of the notes HOLD was keeping
            for ((snd, note) in arpHeldNotes) engine.repeatHold(snd, note, 0f, 1, 4)
            arpHeldNotes.clear()
        }
    }

    /** The notes each physical pad started (and whether as repeat holds), for its note-off. */
    private val heldNotes = arrayOfNulls<List<Pair<Int, Int>>>(16)
    private val heldAsRepeat = BooleanArray(16)

    fun start() {
        lastState = engine.pattern.toJson().toString()
        if (!map.padsMapped || !map.buttonsMapped) {
            AppLog.i("No complete control mapping yet: starting the mapping wizard")
            page = WizardPage(this, WizardPage.Mode.ALL)
        }
        exec.execute { page.onEnter() }
        exec.scheduleWithFixedDelay({ frame() }, 0, 33, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        exec.shutdownNow()
        try {
            exec.awaitTermination(300, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
    }

    fun post(block: () -> Unit) {
        if (exec.isShutdown) return
        try {
            exec.execute {
                try {
                    block()
                } catch (e: Exception) {
                    AppLog.i("UI error: $e\n${e.stackTraceToString().take(800)}")
                }
            }
        } catch (_: Exception) {
        }
    }

    fun invalidate() {
        dirty = true
    }

    fun show(p: Page) = post {
        page.onExit()
        dev.clearLeds()
        page = p
        p.onEnter()
        dirty = true
        AppLog.i("Page: ${p.javaClass.simpleName}")
    }

    fun showHome() = show(org.openmk3.core.Edition.homePage(this))

    private fun frame() {
        try {
            val now = SystemClock.uptimeMillis()
            if (page.tick(now)) dirty = true
            if (lock.tick(now)) dirty = true
            engine.pattern.songTick(engine.playing)
            if (!page.customLeds) applyLights()
            if (!page.customPads) applyPadLights(now)
            if (page !is WizardPage) {
                if (stripTouched && now - stripAt > 350) stripRelease()
                stripLeds()
            }
            if (!dirty) return
            dirty = false
            page.draw(left.canvas, right.canvas, now)
            if (repeatHeld) drawRepeatOverlay(left.canvas, right.canvas)
            if (selectHeld && !page.customPads && page !is WizardPage) drawSelectOverlay(right.canvas)
            val a = left.flush(dev)
            val b = right.flush(dev)
            if (a > 0 || b > 0) {
                lastFrameBytes = maxOf(a, 0) + maxOf(b, 0)
                framesSent++
            }
        } catch (e: Exception) {
            AppLog.i("Frame error: $e\n${e.stackTraceToString().take(800)}")
        }
    }

    // =====================================================================================
    // events from the input thread
    // =====================================================================================

    fun velocityFor(pressure: Int): Float =
        if (fixedVel) 1f
        else (pressure.toFloat() / padFullScale).coerceIn(0f, 1f).pow(0.6f).coerceAtLeast(0.05f)

    override fun onPadHit(pad: Int, pressure: Int) {
        val slot = map.hidPadToSlot(pad)
        // KEYBOARD / CHORDS: SHIFT + pads 13-16 = semitone down / up, octave down / up
        if (shiftHeld && (padMode == PadMode.KEYBOARD || padMode == PadMode.CHORDS) && slot >= 12) {
            post {
                keys.transpose(intArrayOf(-1, 1, -12, 12)[slot - 12])
                keys.save(ctx)
                AppLog.i("Keyboard base note ${org.openmk3.core.audio.Keys.noteName(keys.baseNote)}")
                dirty = true
            }
            return
        }
        // SELECT + pad, on every page: focus that pad's sound without playing it
        if (selectHeld && !page.customPads && page !is WizardPage) {
            post {
                selectedSound = slot
                AppLog.i("Selected ${padLabel(slot)}")
                dirty = true
            }
            return
        }
        val vel = padVelocity(slot, pressure)
        // Sound first, straight from the input thread: no UI-thread hop on the audio path.
        if (page.playsPads) {
            val notes = notesForPad(slot)
            val chord = notes.size
            val repeat = repeatActive
            for ((snd, note) in notes) {
                if (repeat) engine.repeatHold(snd, note, vel, chord, 3) else engine.noteOn(snd, note, vel, chord)
            }
            heldNotes[slot] = notes
            heldAsRepeat[slot] = repeat
        }
        AppLog.i("Pad ${padLabel(slot)} hit (pressure $pressure, vel ${"%.2f".format(vel)})")
        post {
            padPressure[slot] = pressure
            padFlash[slot] = SystemClock.uptimeMillis()
            page.onPadHit(slot, pad, vel, pressure)
            dirty = true
        }
    }

    override fun onPadPressure(pad: Int, pressure: Int) {
        val slot = map.hidPadToSlot(pad)
        // NOTE REPEAT: the repeats follow the pad's pressure
        if (heldAsRepeat[slot] && padMode != PadMode.VEL16) {
            val v = velocityFor(pressure)
            heldNotes[slot]?.forEach { (snd, note) -> engine.repeatHold(snd, note, v, 1, 5) }
        }
        post { padPressure[slot] = pressure; page.onPadPressure(slot, pressure) }
    }

    override fun onPadRelease(pad: Int) {
        val slot = map.hidPadToSlot(pad)
        // always: a missed note-off would leave a sustained sound hanging
        val notes = heldNotes[slot]
        heldNotes[slot] = null
        if (notes == null) engine.release(snd(slot))
        else for ((snd, note) in notes) {
            if (heldAsRepeat[slot] && arpHold) arpHeldNotes.add(snd to note)  // HOLD keeps them
            else if (heldAsRepeat[slot]) engine.repeatHold(snd, note, 0f, 1, 4) else engine.noteOff(snd, note)
        }
        post { padPressure[slot] = 0; page.onPadRelease(slot, pad); dirty = true }
    }

    override fun onButton(bit: Int, down: Boolean) = post {
        // Touch sensors are learned by the wizard; the wizard itself sees them to do that.
        if (bit in map.touchBits && page !is WizardPage) return@post
        val name = map.nameForBit(bit)
        if (name == "SHIFT") shiftHeld = down
        if (name == "ERASE") eraseHeld = down
        if (name == "SELECT") { selectHeld = down; dirty = true }
        // AUTO: hold to record knob moves; SHIFT + AUTO pins it (leaving the mode unpins)
        if (name == "AUTO" && !page.customLeds) {
            if (down && shiftHeld) autoPinned = !autoPinned
            else autoHeld = down
            if (!down && autoPinned.not()) engine.pattern.save(ctx)
            dirty = true
            return@post
        }
        if (name == "DUPLICATE") { dupHeld = down; if (!down) dupGroup = -1 }
        if (name != null && name.startsWith("GROUP ") && name.length == 7) {
            heldGroup = if (down) name[6] - 'A' else -1
            // DUPLICATE + group + group: copy a group · ERASE + group: clear it (UNDO brings it back)
            if (down && !page.customLeds && (dupHeld || eraseHeld) && !shiftHeld) {
                val g = name[6] - 'A'
                if (eraseHeld) groupClear(g)
                else if (dupGroup < 0) { dupGroup = g; AppLog.i("Duplicate group ${'A' + g} to… (press the target group)") }
                else { groupCopy(dupGroup, g); dupGroup = -1 }
                dirty = true
                return@post
            }
        }
        // Mode buttons are momentary; holding one and pressing D1 pins it (and it stays
        // pinned on later presses until unpinned the same way).
        if (down && name == "D1" && modeHeld != null && !page.customLeds) {
            val m = modeHeld!!
            if (!pinned.remove(m)) pinned.add(m)
            if (m == "NOTE REPEAT") repeatLatched = m in pinned
            AppLog.i("$m ${if (m in pinned) "pinned" else "unpinned"}")
            pushRepeat()
            return@post
        }
        // NOTE REPEAT (arp in KEYBOARD / CHORDS); SHIFT + NOTE REPEAT: keyboard + arp
        if (name == "NOTE REPEAT" && !page.customLeds) {
            if (down) {
                modeHeld = name
                if (shiftHeld && padMode == PadMode.PAD) setPadMode(PadMode.KEYBOARD)
                if ("NOTE REPEAT" in pinned) repeatLatched = !repeatLatched
                repeatHeld = true
            } else {
                if (modeHeld == name) modeHeld = null
                repeatHeld = false
            }
            pushRepeat()
            return@post
        }
        // PATTERN / SCENE released: back, unless pinned
        if (!down && (name == "PATTERN" || name == "SCENE" || name == "VARIATION" || name == "EVENTS")) {
            if (modeHeld == name) modeHeld = null
            val cur = page
            if (cur is SlotPage && name !in pinned) cur.back()
            if (cur is VariationPage && name !in pinned) cur.back()
            if (cur is EventsPage && name !in pinned) cur.back()
            return@post
        }
        // while NOTE REPEAT is held: D3 arp hold, D4 gate reset, D5-D8 rate presets
        if (repeatHeld && down && name != null && name.length == 2 && name[0] == 'D' && name[1] in '2'..'8') {
            when (name[1]) {
                '3' -> if (repeatKind == RepeatKind.ARP) applyArpHold(!arpHold)
                '4' -> repeatGate = 1f
                in '5'..'8' -> repeatPreset = name[1] - '5'
            }
            pushRepeat()
            return@post
        }
        if (!page.customLeds && down && name != null && shiftHeld && page !is ColourPage) {
            // SHIFT + group: that group's colour · SHIFT + SELECT: the focused sound's colour
            if (name.startsWith("GROUP ") && name.length == 7) {
                show(ColourPage(this, page, ColourPage.Target.GROUP, name[6] - 'A'))
                return@post
            }
            if (name == "SELECT") {
                show(ColourPage(this, page, ColourPage.Target.SOUND, selectedIndex))
                return@post
            }
        }
        if (!page.customLeds && down && name != null) {
            // Global buttons, the same on every page.
            if (name == "FIXED VEL" && shiftHeld) {  // SHIFT + FIXED VEL = 16 velocities (group mode only)
                if (padMode == PadMode.PAD || padMode == PadMode.VEL16)
                    setPadMode(if (padMode == PadMode.VEL16) PadMode.PAD else PadMode.VEL16)
                padParams = padMode == PadMode.VEL16
                return@post
            }
            when (name) {
                "TAP" -> {
                    if (shiftHeld) {
                        engine.pattern.metronome = !engine.pattern.metronome
                        engine.pattern.save(ctx)
                        AppLog.i("Metronome ${if (engine.pattern.metronome) "on" else "off"}")
                    } else tap()
                    dirty = true
                    return@post
                }
                "FOLLOW" -> {
                    val cur = page
                    if (cur is GridPage) cur.back()
                    else if (shiftHeld) show(GridPage(this, cur))  // SHIFT + FOLLOW: grids (stays until pressed again)
                    else follow = !follow
                    dirty = true
                    return@post
                }
                "MIXER" -> { if (page is MixerPage) showHome() else show(MixerPage(this)); return@post }
                "ARRANGER" -> { if (page is ArrangerPage) showHome() else show(ArrangerPage(this)); return@post }
                "RESTART" -> if (shiftHeld) {  // SHIFT + RESTART: loop the playing section
                    engine.pattern.loop = !engine.pattern.loop
                    engine.pattern.save(ctx)
                    AppLog.i("Loop ${if (engine.pattern.loop) "on" else "off"}")
                    dirty = true
                    return@post
                } else if (!page.handlesTransport) { engine.transportRestart(); dirty = true; return@post }
                // transport on pages without their own handling
                "PLAY" -> if (!page.handlesTransport) {
                    if (engine.playing) engine.transportStop() else engine.transportPlay()
                    dirty = true
                    return@post
                }
                "STOP" -> if (!page.handlesTransport) { engine.stopPressed(); dirty = true; return@post }
                "CHANNEL" -> { if (page is ChannelPage) showHome() else show(ChannelPage(this)); return@post }
                "MACRO" -> { if (page is MacroPage) showHome() else show(MacroPage(this, shiftHeld)); return@post }
                "FILE" -> {
                    if (shiftHeld) quickSave() else if (page is FilePage) showHome() else show(FilePage(this))
                    return@post
                }
                "BROWSER" -> if (shiftHeld && org.openmk3.core.Edition.full) {  // SHIFT + BROWSER: instruments
                    if (page is InstrumentPage) showHome() else show(InstrumentPage(this))
                    return@post
                } else if (!page.handlesTransport && page !is InstrumentPage && page !is ZonePage) { show(BrowserPage(this)); return@post }
                "PLUG-IN" -> if (!page.handlesTransport && org.openmk3.core.Edition.full) { show(SoundPage(this)); return@post }
                "SAMPLING" -> if (shiftHeld) {  // SHIFT + SAMPLING: the focused sound's zones
                    if (page is ZonePage) showHome() else show(ZonePage(this))
                    return@post
                } else if (!page.handlesTransport && page !is ZonePage) { show(SamplingPage(this)); return@post }
                "PERFORM" -> if (shiftHeld) {  // SHIFT + PERFORM: choose the perform FX
                    if (page is PerformPage) showHome() else show(PerformPage(this))
                    return@post
                }
                else -> {}
            }
            when (name) {
                "PITCH", "MOD", "PERFORM", "NOTES" -> {
                    val m = StripMode.valueOf(name)
                    stripMode = if (stripMode == m) StripMode.OFF else m
                    stripLedsShown = -2
                    AppLog.i("Touch strip: $stripMode")
                    dirty = true
                    return@post
                }
                "PATTERN", "SCENE" -> {
                    if (name == "SCENE" && shiftHeld) {  // SHIFT + SCENE: song view
                        if (page is ArrangerPage) showHome() else show(ArrangerPage(this))
                        return@post
                    }
                    modeHeld = name
                    val kind = if (name == "PATTERN") SlotPage.Kind.PATTERN else SlotPage.Kind.SCENE
                    val cur = page
                    if (cur is SlotPage && cur.kind == kind) cur.back()  // pinned: press again to leave
                    else show(SlotPage(this, if (cur is SlotPage) org.openmk3.core.Edition.homePage(this) else cur, kind))
                    return@post
                }
                "EVENTS" -> {
                    modeHeld = name
                    val cur = page
                    if (cur is EventsPage) cur.back() else show(EventsPage(this, cur))
                    return@post
                }
                "VARIATION" -> {
                    modeHeld = name
                    val cur = page
                    if (cur is VariationPage) cur.back() else show(VariationPage(this, cur))
                    return@post
                }
                "LOCK" -> {
                    val cur = page
                    when {
                        cur is LockPage -> cur.back()
                        shiftHeld -> show(LockPage(this, cur))
                        lock.quick == null -> { lock.quick = lock.take(); AppLog.i("Lock: snapshot taken") }
                        else -> { lock.recall(lock.quick!!); lock.quick = null; AppLog.i("Lock: snapshot recalled") }
                    }
                    dirty = true
                    return@post
                }
                // Pad input modes: switch the pads and show the mode's settings; pressing the
                // lit button again hides the settings (the pads stay in that mode); SHIFT +
                // button switches without showing them.
                "KEYBOARD", "CHORDS" -> {
                    val m = if (name == "KEYBOARD") PadMode.KEYBOARD else PadMode.CHORDS
                    padParams = if (shiftHeld) false else if (padMode == m) !padParams else true
                    setPadMode(m)
                    pushRepeat()
                }
                // STEP from any page: the sequencer with the pads as steps
                "STEP" -> if (page !is HomePage) {
                    HomePage.stepMode = true
                    showHome()
                    return@post
                }
                "PAD MODE" -> {
                    padParams = if (shiftHeld) false else if (padMode == PadMode.PAD) !padParams else true
                    setPadMode(PadMode.PAD)
                    pushRepeat()
                }
            }
            if (name == "FIXED VEL" && !page.handlesFixedVel) {
                fixedVel = !fixedVel
                AppLog.i("Fixed velocity ${if (fixedVel) "on" else "off"}")
                dirty = true
                return@post
            }
            if (name.startsWith("GROUP ") && name.length == 7) {
                page.onGroup(name[6] - 'A')
                dirty = true
                return@post
            }
        }
        page.onButton(name, bit, down)
        dirty = true
    }

    override fun onKnob(knob: Int, delta: Int, value: Int) = post {
        if (repeatHeld) repeatKnob(knob, delta) else page.onKnob(knob, delta)
        dirty = true
    }

    /** Knobs while NOTE REPEAT is held: K1 gate, K2 arp type, K3 arp octaves. */
    private fun repeatKnob(knob: Int, delta: Int) {
        fun step(): Int {
            repeatAcc += delta
            if (kotlin.math.abs(repeatAcc) < 25) return 0
            val d = if (repeatAcc > 0) 1 else -1
            repeatAcc = 0
            return d
        }
        val arp = repeatKind == RepeatKind.ARP
        when (knob) {
            1 -> if (arp) arpType = (arpType + step()).coerceIn(0, ARP_TYPES.size - 1)
            2 -> repeatPresets[repeatPreset] = (repeatPresets[repeatPreset] + step()).coerceIn(0, REPEAT_RATES.size - 1)
            3 -> repeatRhythm = (repeatRhythm + step()).coerceIn(0, RHYTHMS.size - 1)
            4 -> repeatDyn = (repeatDyn + step()).coerceIn(0, DYNAMICS.size - 1)
            5 -> if (arp) arpOctaves = (arpOctaves + step()).coerceIn(1, 4)
            7 -> repeatGate = (repeatGate + delta / 500f).coerceIn(0.05f, 2f)
        }
        pushRepeat()
    }
    override fun onKnobTouch(knob: Int, touched: Boolean) = post { page.onKnobTouch(knob, touched); dirty = true }
    override fun onEncoder(delta: Int) = post { page.onEncoder(delta); dirty = true }
    override fun onTouchStrip(position: Int, counter: Int) = post {
        if (page is WizardPage) page.onTouchStrip(position) else stripMove(position)
        dirty = true
    }

    // =====================================================================================
    // LED helpers (UI thread)
    // =====================================================================================

    private val lights = Lights()

    /**
     * Every button the page handles is lit: dim when available, bright when active / selected.
     * Buttons that do nothing on this page stay dark, so the panel shows what works.
     */
    private fun applyLights() {
        lights.clear()
        // global: pad modes, NOTE REPEAT, FIXED VEL and the group buttons (pages may override)
        lights.set("PAD MODE", padMode == PadMode.PAD)
        lights.set("KEYBOARD", padMode == PadMode.KEYBOARD)
        lights.set("CHORDS", padMode == PadMode.CHORDS)
        lights.set("NOTE REPEAT", repeatActive)
        lights.set("TAP", engine.pattern.metronome)
        lights.set("AUTO", autoActive)
        lights.set("CHANNEL", page is ChannelPage)
        lights.set("ARRANGER", page is ArrangerPage)
        lights.set("MACRO", page is MacroPage || macroLearn >= 0)
        lights.set("PITCH", stripMode == StripMode.PITCH)
        lights.set("MOD", stripMode == StripMode.MOD)
        lights.set("PERFORM", stripMode == StripMode.PERFORM)
        lights.set("NOTES", stripMode == StripMode.NOTES)
        lights.set("MIXER", page is MixerPage)
        lights.set("FILE", page is FilePage)
        lights.set("FOLLOW", follow)
        lights.set("PATTERN", false)
        lights.set("SCENE", false)
        // every button that does something here is at least dim
        lights.set("STEP", page is HomePage && HomePage.stepMode)
        lights.set("EVENTS", page is EventsPage)
        lights.set("VARIATION", page is VariationPage)
        lights.set("SELECT", selectHeld)
        lights.set("SHIFT", shiftHeld)
        lights.set("DUPLICATE", dupHeld)
        lights.set("ERASE", eraseHeld)
        lights.set("LOCK", lock.quick != null || page is LockPage)
        lights.set("BROWSER", page is BrowserPage || page is InstrumentPage)
        lights.set("SAMPLING", page is SamplingPage || page is ZonePage)
        if (org.openmk3.core.Edition.full) lights.set("PLUG-IN", page is SoundPage)
        if (!page.handlesTransport) {
            lights.set("PLAY", engine.playing)
            lights.set("STOP", "RESTART")
        }
        page.lights(lights)
        lights.set("FIXED VEL", fixedVel || padMode == PadMode.VEL16)
        if (repeatHeld) {
            for (i in 0 until 4) lights.set("D${i + 5}", i == repeatPreset)
            lights.set("D1", "NOTE REPEAT" in pinned)
            if (repeatKind == RepeatKind.ARP) lights.set("D3", arpHold)
            lights.set("D4")
        }
        if (modeHeld == "PATTERN" || modeHeld == "SCENE" || modeHeld == "VARIATION" || modeHeld == "EVENTS") lights.set("D1", modeHeld in pinned)
        lights.set("LOCK", lock.quick != null || page is LockPage)
        if (page is VariationPage) lights.set("VARIATION", true)
        val p = engine.pattern
        for (g in 0 until 8) {
            val c = groupColour(g)
            // selected: bright; groups with content: dim; muted: off; empty: off
            val used = g == group || (0 until 16).any { hasSound(g * 16 + it) } || p.patterns[g].any { it != null }
            val v = when {
                g == group -> org.openmk3.core.mk3.Mk3Colors.value(c, 3)
                !used || !p.groupAudible(g) -> 0
                else -> org.openmk3.core.mk3.Mk3Colors.value(c, 0)
            }
            lights.raw("GROUP ${'A' + g}", v)
        }
        for ((name, idx) in map.buttonLeds) {
            val v = lights.values[name] ?: 0
            dev.setButtonLed(idx, v)
        }
    }

    /**
     * Pads on pages that don't draw their own: the focused group's colour, dim for sounds
     * that have a sample, brighter for the focused one, bright while playing.
     */
    private fun applyPadLights(now: Long) {
        for (slot in 0 until 16) {
            // SELECT held: the pads show the group's sounds (lit = loaded, brightest = focused)
            if (selectHeld) {
                val sound = snd(slot)
                val c = soundColour(sound)
                padLed(slot, when {
                    slot == selectedSound -> org.openmk3.core.mk3.Mk3Colors.value(c, 3)
                    hasSound(sound) -> org.openmk3.core.mk3.Mk3Colors.value(c, 1)
                    else -> 0
                })
                continue
            }
            val custom = page.padLight(slot, now) ?: if (padMode != PadMode.PAD) keyPadLight(slot) else null
            val v = custom ?: run {
                val sound = snd(slot)
                val c = soundColour(sound)
                val hot = now - engine.lastTrigMs[sound] in 0..120 || padPressure[slot] > 0
                when {
                    hot -> org.openmk3.core.mk3.Mk3Colors.value(c, 3)
                    slot == selectedSound -> org.openmk3.core.mk3.Mk3Colors.value(c, 2)
                    hasSound(sound) -> org.openmk3.core.mk3.Mk3Colors.value(c, 0)
                    else -> 0
                }
            }
            padLed(slot, v)
        }
    }

    /** Pad LED in KEYBOARD / CHORDS / 16 VEL: roots brighter, played pads brightest. */
    fun keyPadLight(slot: Int): Int {
        val c = soundColour(selectedIndex)
        if (padPressure[slot] > 0) return org.openmk3.core.mk3.Mk3Colors.value(c, 3)
        return when (padMode) {
            PadMode.KEYBOARD -> org.openmk3.core.mk3.Mk3Colors.value(c, if (keys.isRoot(keys.noteFor(slot))) 2 else 0)
            PadMode.CHORDS -> org.openmk3.core.mk3.Mk3Colors.value(c, if (keys.isRoot(keys.chordFor(slot)[0])) 2 else 0)
            PadMode.VEL16 -> org.openmk3.core.mk3.Mk3Colors.value(c, slot / 4)
            else -> 0
        }
    }

    private val ovBg = Theme.fill(Theme.BG)
    private val ovTab = Theme.text(15f, Theme.DIM, bold = true, align = android.graphics.Paint.Align.CENTER)
    private val ovOn = Theme.text(15f, Theme.ACCENT, bold = true, align = android.graphics.Paint.Align.CENTER)
    private val ovName = Theme.text(13f, Theme.DIM, align = android.graphics.Paint.Align.CENTER)
    private val ovVal = Theme.text(16f, Theme.FG, bold = true, align = android.graphics.Paint.Align.CENTER)
    private val ovTitle = Theme.text(14f, Theme.ACCENT, bold = true)

    /** While SELECT is held: the group's 16 sounds in pad layout on the right screen. */
    private fun drawSelectOverlay(r: Canvas) {
        r.drawColor(Theme.BG)
        r.drawText("SELECT · GROUP $groupLetter", 8f, 22f, ovTitle)
        val w = 114f; val h = 58f
        for (slot in 0 until 16) {
            val col = slot % 4; val row = 3 - slot / 4
            val x = 4f + col * (w + 4f); val y = 30f + row * (h + 2f)
            val sound = snd(slot)
            val loaded = hasSound(sound)
            val c = org.openmk3.core.mk3.Mk3Colors.rgb(soundColour(sound))
            val fill = Theme.fill(if (loaded) c else android.graphics.Color.rgb(32, 32, 40))
            if (loaded && slot != selectedSound) fill.alpha = 110
            r.drawRoundRect(android.graphics.RectF(x, y, x + w, y + h), 5f, 5f, fill)
            if (slot == selectedSound) r.drawRoundRect(android.graphics.RectF(x, y, x + w, y + h), 5f, 5f, Theme.stroke(Theme.FG, 3f))
            val src = engine.params[sound, org.openmk3.core.audio.SoundParams.SRC].toInt()
            val name = when (src) {
                1 -> "SYNTH"; 2 -> "FM"; 3 -> engine.poly.patchName[sound]
                else -> engine.kit[sound].name.ifEmpty { "—" }
            }
            r.drawText(padLabel(slot), x + w / 2, y + 18f, ovName)
            r.drawText(name.take(13), x + w / 2, y + 42f, ovVal)
        }
    }

    /** While NOTE REPEAT is held: pin, (arp) hold, gate reset, rate presets, knob settings. */
    private fun drawRepeatOverlay(l: Canvas, r: Canvas) {
        for (c in arrayOf(l, r)) {
            c.drawRect(0f, 0f, 480f, 36f, ovBg)
            c.drawRect(0f, 214f, 480f, 272f, ovBg)
        }
        val arp = repeatKind == RepeatKind.ARP
        l.drawText("PIN", 60f, 24f, if ("NOTE REPEAT" in pinned) ovOn else ovTab)
        if (arp) l.drawText("HOLD", 300f, 24f, if (arpHold) ovOn else ovTab)
        l.drawText("GATE RESET", 420f, 24f, ovTab)
        for (i in 0 until 4) r.drawText(REPEAT_RATES[repeatPresets[i]], 60f + i * 120f, 24f, if (i == repeatPreset) ovOn else ovTab)
        l.drawText(if (arp) "ARPEGGIATOR" else "NOTE REPEAT", 8f, 212f, ovTitle)
        if (arp) {
            l.drawText("TYPE", 180f, 232f, ovName)
            l.drawText(ARP_TYPES[arpType], 180f, 254f, ovVal)
        }
        l.drawText("RATE", 300f, 232f, ovName)
        l.drawText(REPEAT_RATES[repeatRate], 300f, 254f, ovVal)
        l.drawText("RHYTHM", 420f, 232f, ovName)
        l.drawText(RHYTHMS[repeatRhythm], 420f, 254f, ovVal)
        r.drawText("DYNAMICS", 60f, 232f, ovName)
        r.drawText(DYNAMICS[repeatDyn], 60f, 254f, ovVal)
        if (arp) {
            r.drawText("OCTAVES", 180f, 232f, ovName)
            r.drawText("$arpOctaves", 180f, 254f, ovVal)
        }
        r.drawText("GATE", 420f, 232f, ovName)
        r.drawText("${(repeatGate * 100).toInt()}%", 420f, 254f, ovVal)
    }

    fun buttonLed(name: String, value: Int) {
        map.buttonLeds[name]?.let { dev.setButtonLed(it, value) }
    }

    fun padLed(slot: Int, value: Int) {
        val hid = map.padSlotToHid[slot]
        val idx = map.padLeds[hid]
        if (idx >= 0) dev.setPadLed(idx, value)
    }
}

/** Colour (controller index 1..16) of group [g], set with SHIFT + group. */
fun groupColour(g: Int): Int = Colours.group[g.coerceIn(0, 7)]

/** Button lights requested by a page (see [Page.lights]). */
class Lights {
    val values = HashMap<String, Int>()
    fun clear() = values.clear()
    /** A button this page handles: dim, or bright when [on]. */
    fun set(name: String, on: Boolean = false) {
        values[name] = if (name.startsWith("GROUP ")) {
            org.openmk3.core.mk3.Mk3Colors.value(org.openmk3.core.mk3.Mk3Colors.RED, if (on) 2 else 0)
        } else if (on) BRIGHT else DIM
    }
    fun set(vararg names: String) = names.forEach { set(it, false) }
    /** Explicit LED value (colour buttons). */
    fun raw(name: String, value: Int) { values[name] = value }
    companion object {
        const val DIM = 0x10
        const val BRIGHT = 0x7F
    }
}

/** Base class for everything shown on the two MK3 screens. All calls happen on the UI thread. */
abstract class Page(val ui: Ui) {
    /** Pages that drive button LEDs themselves (setup wizard, palette) set this. */
    open val customLeds = false
    /** Declare the buttons this page handles and which are active (called every frame). */
    open fun lights(l: Lights) {}
    /** Pages that drive the pad LEDs themselves set this. */
    open val customPads = false
    /** Pages with their own PLAY / STOP / RESTART handling set this (otherwise they are global). */
    open val handlesTransport = false
    /** Override one pad's LED on a page with default pad lights (null = default). */
    open fun padLight(slot: Int, now: Long): Int? = null
    /** A page that gives FIXED VEL its own meaning sets this (none do yet). */
    open val handlesFixedVel = false
    /** GROUP A–H pressed: focus that group (pages may add MUTE / SOLO + group). */
    open fun onGroup(g: Int) { ui.selectGroup(g) }
    /** If true, pad hits play sounds directly from the input thread. */
    open val playsPads = false
    open fun onEnter() {}
    open fun onExit() {}
    abstract fun draw(l: Canvas, r: Canvas, now: Long)
    /** Return true when an animation needs a redraw. */
    open fun tick(now: Long): Boolean = false
    open fun onButton(name: String?, bit: Int, down: Boolean) {}
    open fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {}
    open fun onPadPressure(slot: Int, pressure: Int) {}
    open fun onPadRelease(slot: Int, hid: Int) {}
    open fun onKnob(knob: Int, delta: Int) {}
    open fun onKnobTouch(knob: Int, touched: Boolean) {}
    open fun onEncoder(delta: Int) {}
    open fun onTouchStrip(position: Int) {}
}

/** Shared paints and colours for the MK3 screens. */
object Theme {
    val BG = Color.rgb(12, 12, 16)
    val FG = Color.rgb(235, 235, 235)
    val DIM = Color.rgb(120, 120, 130)
    val ACCENT = Color.rgb(255, 140, 40)
    val OK = Color.rgb(90, 200, 120)
    val WARN = Color.rgb(240, 80, 70)
    val CELL = Color.rgb(40, 40, 50)

    fun text(size: Float, color: Int = FG, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = align
        }

    fun fill(color: Int) = Paint().apply { this.color = color; style = Paint.Style.FILL }
    fun stroke(color: Int, w: Float = 2f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = w }
}

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
    /** Snap sample markers (start, end, loop, slices) to zero crossings. */
    @Volatile var zeroSnap = false

    /** Sound index (0..127) of pad [slot] in the focused group. */
    fun snd(slot: Int) = group * org.openmk3.core.audio.Pattern.PADS + slot
    /** Sound index of the focused pad. */
    val selectedIndex get() = snd(selectedSound)
    val groupLetter get() = 'A' + group
    /** e.g. "B7" for the focused pad. */
    fun padLabel(slot: Int = selectedSound) = "$groupLetter${slot + 1}"

    fun selectGroup(g: Int) {
        group = g.coerceIn(0, 7)
        AppLog.i("Group ${'A' + group}")
        dirty = true
    }

    /** The sound each physical pad started, so its note-off reaches it even after a group change. */
    private val heldSound = IntArray(16) { -1 }

    fun start() {
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
            if (!page.customLeds) applyLights()
            if (!page.customPads) applyPadLights(now)
            if (!dirty) return
            dirty = false
            page.draw(left.canvas, right.canvas, now)
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
        val vel = velocityFor(pressure)
        val sound = snd(slot)
        // Sound first, straight from the input thread: no UI-thread hop on the audio path.
        if (page.playsPads) {
            engine.trigger(sound, vel)
            heldSound[slot] = sound
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
        post { padPressure[slot] = pressure; page.onPadPressure(slot, pressure) }
    }

    override fun onPadRelease(pad: Int) {
        val slot = map.hidPadToSlot(pad)
        // always: a missed note-off would leave a sustained sound hanging
        val held = heldSound[slot]
        heldSound[slot] = -1
        engine.release(if (held >= 0) held else snd(slot))
        post { padPressure[slot] = 0; page.onPadRelease(slot, pad); dirty = true }
    }

    override fun onButton(bit: Int, down: Boolean) = post {
        // Touch sensors are learned by the wizard; the wizard itself sees them to do that.
        if (bit in map.touchBits && page !is WizardPage) return@post
        val name = map.nameForBit(bit)
        if (!page.customLeds && down && name != null) {
            // Global buttons, the same on every page.
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

    override fun onKnob(knob: Int, delta: Int, value: Int) = post { page.onKnob(knob, delta); dirty = true }
    override fun onKnobTouch(knob: Int, touched: Boolean) = post { page.onKnobTouch(knob, touched); dirty = true }
    override fun onEncoder(delta: Int) = post { page.onEncoder(delta); dirty = true }
    override fun onTouchStrip(position: Int, counter: Int) = post { page.onTouchStrip(position); dirty = true }

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
        page.lights(lights)
        // global: FIXED VEL and the group buttons
        lights.set("FIXED VEL", fixedVel)
        val p = engine.pattern
        for (g in 0 until 8) {
            val c = groupColour(g)
            val v = when {
                g == group -> org.openmk3.core.mk3.Mk3Colors.value(c, 3)
                !p.groupAudible(g) -> 0
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
        val c = groupColour(group)
        for (slot in 0 until 16) {
            val custom = page.padLight(slot, now)
            val v = custom ?: run {
                val sound = snd(slot)
                val hot = now - engine.lastTrigMs[sound] in 0..120 || padPressure[slot] > 0
                when {
                    hot -> org.openmk3.core.mk3.Mk3Colors.value(c, 3)
                    slot == selectedSound -> org.openmk3.core.mk3.Mk3Colors.value(c, 2)
                    engine.kit[sound].frames > 0 -> org.openmk3.core.mk3.Mk3Colors.value(c, 0)
                    else -> 0
                }
            }
            padLed(slot, v)
        }
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

/** Default colour of each group A..H (pads and group buttons). */
fun groupColour(g: Int): Int = when (g) {
    0 -> org.openmk3.core.mk3.Mk3Colors.RED
    1 -> org.openmk3.core.mk3.Mk3Colors.ORANGE
    2 -> org.openmk3.core.mk3.Mk3Colors.YELLOW
    3 -> org.openmk3.core.mk3.Mk3Colors.GREEN
    4 -> org.openmk3.core.mk3.Mk3Colors.CYAN
    5 -> org.openmk3.core.mk3.Mk3Colors.BLUE
    6 -> org.openmk3.core.mk3.Mk3Colors.VIOLET
    else -> org.openmk3.core.mk3.Mk3Colors.MAGENTA
}

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

package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.openmk3.core.AppLog
import org.openmk3.core.Mk3Service
import org.openmk3.core.audio.AudioEngine
import org.openmk3.core.audio.KitStore
import org.openmk3.core.audio.NativeEngine
import org.openmk3.core.audio.Sample
import org.openmk3.core.audio.WavWriter
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sampling, in three tabs (D1–D3), like the hardware workflow:
 *
 *  RECORD  D5 source (INPUT / PHONE) · D6 start (MANUAL / THRESHOLD) · D8 start / stop
 *          K1 threshold · K2 max length · pads choose the target pad
 *  EDIT    edits the take, or the focused pad's sample (pads choose which)
 *          K1 start · K2 end · K3 fade in · K4 fade out
 *          D5 normalise · D6 reverse · D7 preview · D8 save to the focused pad
 *  SLICE   K1 mode (GRID / DETECT) · K2 slices or sensitivity · pads play the slices
 *          D8 apply: each slice is saved and loaded onto pads 1, 2, 3…
 *
 *  EDIT / SLICE: ENCODER turn = zoom · ENCODER left / right = scroll · D4 = zero-crossing snap
 *  ENCODER push = start / stop recording · SHIFT = fine knob steps · SAMPLING = back
 */
class SamplingPage(ui: Ui) : Page(ui) {

    private enum class Tab { RECORD, EDIT, SLICE }

    override val playsPads get() = tab != Tab.SLICE

    private companion object {
        const val SRC_INPUT = 0
        const val SRC_PHONE = 1
        const val IDLE = 0
        const val ARMED = 1
        const val RECORDING = 2
        val GRID = intArrayOf(2, 4, 8, 16)
        // Kept across visits.
        var source = SRC_INPUT
        var thresholdMode = false
        var threshold = 0.05f
        var maxSeconds = 30
        var lastTab = Tab.RECORD
        var sliceDetect = false
        var gridIdx = 2
        var sensitivity = 0.5f
    }

    private val n = NativeEngine
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sampling-io") }

    private var tab = lastTab
    private var state = IDLE
    private var meterIn = 0f
    private var meterPhone = 0f
    private var shift = false
    private var message = ""
    private var knobAcc = 0

    // The sample being edited (stereo interleaved, 48 kHz).
    private var take: FloatArray? = null
    private var takeFrames = 0
    private var takeName = "Rec"
    private var trimStart = 0
    private var trimEnd = 0
    private var fadeInMs = 0f
    private var fadeOutMs = 0f
    private var normalise = false
    private var reverse = false
    private val wave = WaveView()
    // Marker positions as turned; trimStart / trimEnd are these, snapped when zero-crossing snap is on.
    private var rawStart = 0
    private var rawEnd = 0
    /** The frame the zoom centres on: the marker edited last. */
    private var focus = 0
    // What the preview slot is playing, to draw its playhead on the take.
    private var previewBase = -1
    private var slices = IntArray(0)   // slice start frames within the edited region

    private val tabText = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val tabOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val dLabel = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val dValue = Theme.text(15f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val dOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val big = Theme.text(26f, Theme.FG, bold = true)
    private val recText = Theme.text(26f, Theme.WARN, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val value = Theme.text(16f)
    private val ok = Theme.text(16f, Theme.OK)
    private val warn = Theme.text(15f, Theme.WARN)
    private val cell = Theme.fill(Theme.CELL)
    private val okFill = Theme.fill(Theme.OK)
    private val warnFill = Theme.fill(Theme.WARN)
    private val thrFill = Theme.fill(Theme.ACCENT)
    private val waveIn = Theme.fill(Color.rgb(90, 200, 120))
    private val waveOut = Theme.fill(Color.rgb(50, 70, 60))
    private val marker = Theme.fill(Theme.ACCENT)
    private val sliceLine = Theme.fill(Color.rgb(80, 160, 255))
    private val sep = Theme.fill(Color.rgb(50, 50, 60))

    private val phoneActive get() = Mk3Service.instance?.phoneCaptureActive == true

    override fun onEnter() {
        ui.buttonLed("SAMPLING", 0x7F)
        if (tab != Tab.RECORD) loadFromPad(ui.selectedSound)
        if (source == SRC_PHONE && !phoneActive) Mk3Service.instance?.requestPhoneCapture()
    }

    override fun onExit() {
        lastTab = tab
        if (state != IDLE) n.nativeRecStop()
        worker.shutdown()
    }

    // =====================================================================================
    // the take
    // =====================================================================================

    private fun setTake(data: FloatArray, name: String) {
        take = data
        takeFrames = data.size / 2
        takeName = name
        trimStart = 0
        trimEnd = takeFrames
        rawStart = 0
        rawEnd = takeFrames
        focus = 0
        previewBase = -1
        fadeInMs = 0f
        fadeOutMs = 0f
        normalise = false
        reverse = false
        wave.set(data, 2)
        computeSlices()
    }

    /** Applies zero-crossing snap to the raw marker positions. */
    private fun snapTrim() {
        val d = take ?: return
        trimStart = if (ui.zeroSnap) WaveView.zeroCross(d, 2, rawStart) else rawStart
        trimEnd = if (ui.zeroSnap && rawEnd < takeFrames) WaveView.zeroCross(d, 2, rawEnd) else rawEnd
        if (trimEnd <= trimStart) trimEnd = min(takeFrames, trimStart + 48)
    }

    /** Edited-sample frame -> frame of the take (for drawing). */
    private fun editedToTake(e: Int) = if (reverse) trimEnd - 1 - e else trimStart + e

    /** Opens the sample on [slot] in the editor (mono becomes stereo). */
    private fun loadFromPad(slot: Int) {
        val s = ui.engine.kit[ui.snd(slot)]
        if (s.frames < 2) { message = "${ui.padLabel(slot)} is empty"; return }
        val st = if (s.channels == 2) s.data.copyOf() else FloatArray(s.frames * 2) { s.data[it / 2] }
        setTake(st, s.name)
        message = "Editing ${ui.padLabel(slot)}: ${s.name}"
    }

    /** The edited sample: trimmed, reversed, normalised, faded. */
    private fun edited(): FloatArray? {
        val d = take ?: return null
        val len = trimEnd - trimStart
        if (len <= 0) return null
        val out = d.copyOfRange(trimStart * 2, trimEnd * 2)
        if (reverse) {
            for (f in 0 until len / 2) {
                val g = len - 1 - f
                for (c in 0..1) { val t = out[f * 2 + c]; out[f * 2 + c] = out[g * 2 + c]; out[g * 2 + c] = t }
            }
        }
        var gain = 1f
        if (normalise) {
            var peak = 0f
            for (x in out) peak = max(peak, abs(x))
            if (peak > 1e-5f) gain = 0.98f / peak
        }
        // short anti-click fades at cut points, or the longer fades set with K3 / K4
        val fadeIn = max(if (trimStart > 0) 64 else 0, (fadeInMs * 48).toInt()).coerceAtMost(len)
        val fadeOut = max(if (trimEnd < takeFrames) 256 else 0, (fadeOutMs * 48).toInt()).coerceAtMost(len)
        for (f in 0 until len) {
            var g = gain
            if (f < fadeIn) g *= f.toFloat() / fadeIn
            if (f >= len - fadeOut) g *= (len - f).toFloat() / fadeOut
            out[f * 2] *= g
            out[f * 2 + 1] *= g
        }
        return out
    }

    // =====================================================================================
    // recording
    // =====================================================================================

    private fun startStop() {
        if (state != IDLE) { finish(); return }
        if (source == SRC_PHONE && !phoneActive) {
            Mk3Service.instance?.requestPhoneCapture()
            message = "Allow phone audio: see the phone (notification or app button)"
            return
        }
        n.nativeRecArm(source, thresholdMode, threshold, maxSeconds)
        state = if (thresholdMode) ARMED else RECORDING
        message = ""
        AppLog.i("Sampling: armed (${if (source == SRC_PHONE) "phone" else "input"}, ${if (thresholdMode) "threshold ${"%.2f".format(threshold)}" else "manual"}, max $maxSeconds s)")
    }

    private fun finish() {
        n.nativeRecStop()
        state = IDLE
        val data = n.nativeRecTake()
        if (data.size / 2 < 480) {
            message = "Nothing recorded"
            return
        }
        setTake(data, "Rec")
        message = "Recorded ${"%.2f".format(takeFrames.toDouble() / AudioEngine.SR)} s"
        AppLog.i("Sampling: $message")
        tab = Tab.EDIT
    }

    // =====================================================================================
    // slicing
    // =====================================================================================

    private fun computeSlices() {
        val len = trimEnd - trimStart
        if (take == null || len <= 0) { slices = IntArray(0); return }
        val raw = if (!sliceDetect) {
            val k = GRID[gridIdx]
            IntArray(k) { (it.toLong() * len / k).toInt() }
        } else detectOnsets()
        val d = if (ui.zeroSnap) edited() else null
        slices = if (d == null) raw else IntArray(raw.size) { i ->
            if (i == 0) raw[0] else WaveView.zeroCross(d, 2, raw[i], 1200).coerceIn(raw[i - 1] + 1, len - 1)
        }
    }

    /** Transient detection on the edited region: energy jumps between 5 ms hops. */
    private fun detectOnsets(): IntArray {
        val d = edited() ?: return IntArray(0)
        val len = d.size / 2
        val hop = 240
        val hops = len / hop
        if (hops < 2) return intArrayOf(0)
        val e = FloatArray(hops)
        for (h in 0 until hops) {
            var acc = 0f
            for (f in h * hop until (h + 1) * hop) { val m = (d[f * 2] + d[f * 2 + 1]) * 0.5f; acc += m * m }
            e[h] = acc / hop
        }
        val factor = 1.5f + (1f - sensitivity) * 10f
        val floor = 1e-5f + (1f - sensitivity) * 2e-4f
        val out = ArrayList<Int>()
        out.add(0)
        var last = 0
        for (h in 4 until hops) {
            var mean = 0f
            for (k in h - 4 until h) mean += e[k]
            mean /= 4f
            if (e[h] > floor && e[h] > mean * factor && h - last >= 12) { // at least 60 ms apart
                out.add(max(0, h * hop - hop / 2))
                last = h
                if (out.size >= 16) break
            }
        }
        return out.toIntArray()
    }

    private fun sliceData(i: Int): FloatArray? {
        val d = edited() ?: return null
        val len = d.size / 2
        if (i >= slices.size) return null
        val a = slices[i]
        val b = if (i + 1 < slices.size) slices[i + 1] else len
        if (b - a < 2) return null
        val out = d.copyOfRange(a * 2, b * 2)
        val fo = min(240, b - a)  // 5 ms fade at the slice end
        val n2 = b - a
        for (f in n2 - fo until n2) { val g = (n2 - f).toFloat() / fo; out[f * 2] *= g; out[f * 2 + 1] *= g }
        return out
    }

    private fun applySlices() {
        val count = min(slices.size, 16)
        if (count == 0) { message = "No slices"; return }
        val parts = (0 until count).mapNotNull { i -> sliceData(i)?.let { i to it } }
        val base = takeName
        message = "Saving $count slices…"
        worker.execute {
            try {
                val dir = uniqueDir(File(BrowserPage.ROOT, "Recordings"), "$base slices")
                val samples = parts.map { (i, data) ->
                    val f = File(dir, "%s s%02d.wav".format(base, i + 1))
                    WavWriter.write(f, data, 2)
                    i to Sample(f.nameWithoutExtension, data, 0, 2, f.absolutePath, info(data))
                }
                ui.post {
                    for ((i, s) in samples) {
                        ui.engine.setSample(ui.snd(i), s)
                        ui.engine.params.resetRange(ui.snd(i))
                    }
                    ui.engine.params.save(ui.ctx)
                    KitStore.save(ui.ctx, ui.engine.kit)
                    message = "${samples.size} slices → ${ui.groupLetter}1–${ui.groupLetter}${samples.size}"
                    AppLog.i("Sampling: ${samples.size} slices saved in ${dir.absolutePath}")
                    ui.invalidate()
                }
            } catch (ex: Exception) {
                ui.post { message = "Can't save: ${ex.message}"; ui.invalidate() }
            }
        }
    }

    // =====================================================================================
    // saving
    // =====================================================================================

    private fun info(d: FloatArray) = "48.0 kHz · 24-bit · stereo · ${"%.2f".format((d.size / 2).toDouble() / AudioEngine.SR)} s"

    private fun uniqueDir(parent: File, name: String): File {
        var d = File(parent, name)
        var i = 2
        while (d.exists()) d = File(parent, "$name $i").also { i++ }
        d.mkdirs()
        return d
    }

    private fun preview(data: FloatArray?, base: Int = 0) {
        val e = data ?: return
        previewBase = base
        ui.engine.preview(Sample("Preview", e, 0, 2, null, ""))
    }

    private fun save() {
        val e = edited() ?: run { message = "Record or open something first"; return }
        val slot = ui.selectedIndex
        val label = ui.padLabel()
        message = "Saving…"
        worker.execute {
            try {
                val dir = File(BrowserPage.ROOT, "Recordings")
                dir.mkdirs()
                var i = 1
                var f: File
                do { f = File(dir, "Rec %03d.wav".format(i++)) } while (f.exists())
                WavWriter.write(f, e, 2)
                val s = Sample(f.nameWithoutExtension, e, 0, 2, f.absolutePath, info(e))
                ui.post {
                    ui.engine.setSample(slot, s)
                    ui.engine.params.resetRange(slot)
                    ui.engine.params.save(ui.ctx)
                    KitStore.save(ui.ctx, ui.engine.kit)
                    message = "Saved ${f.name} → $label"
                    AppLog.i("Sampling: saved ${f.absolutePath} to $label")
                    ui.engine.trigger(slot, 0.8f)
                    ui.invalidate()
                }
            } catch (ex: Exception) {
                ui.post { message = "Can't save: ${ex.message}"; ui.invalidate() }
            }
        }
    }

    // =====================================================================================
    // input
    // =====================================================================================

    override fun tick(now: Long): Boolean {
        val pi = n.nativeRecTakePeak(SRC_INPUT)
        val pp = n.nativeRecTakePeak(SRC_PHONE)
        meterIn = max(pi, meterIn * 0.85f)
        meterPhone = max(pp, meterPhone * 0.85f)
        if (state != IDLE) {
            val s = n.nativeRecState()
            if (s == IDLE) finish()      // reached the maximum length
            else state = s               // ARMED -> RECORDING when the threshold is crossed
        }
        return true
    }

    /** D7: play the edit; press again while it plays to stop it. */
    private fun previewToggle() {
        if (ui.engine.isSounding(AudioEngine.PREVIEW_SLOT)) ui.engine.killAll(AudioEngine.PREVIEW_SLOT)
        else preview(edited(), 0)
    }

    private val gate get() = ui.engine.params[ui.selectedIndex, org.openmk3.core.audio.SoundParams.GATE] > 0.5f

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (name == "SHIFT") { shift = down; return }
        // GATE trigger: the preview only plays while D7 is held
        if (!down && name == "D7" && tab != Tab.RECORD && gate) ui.engine.killAll(AudioEngine.PREVIEW_SLOT)
        if (!down) return
        val e = ui.engine
        when (name) {
            "D1" -> tab = Tab.RECORD
            "D2" -> { tab = Tab.EDIT; if (take == null) loadFromPad(ui.selectedSound) }
            "D3" -> { tab = Tab.SLICE; if (take == null) loadFromPad(ui.selectedSound); computeSlices() }
            "ENCODER PUSH" -> startStop()
            "ENCODER LEFT" -> if (tab != Tab.RECORD) wave.scroll(-1)
            "ENCODER RIGHT" -> if (tab != Tab.RECORD) wave.scroll(1)
            "PLAY" -> if (e.playing) e.transportStop() else e.transportPlay()
            "STOP" -> e.stopPressed()
            "SAMPLING" -> ui.showHome()
            "BROWSER" -> ui.show(BrowserPage(ui))
            "PLUG-IN" -> if (org.openmk3.core.Edition.full) ui.show(SoundPage(ui))
        }
        when (tab) {
            Tab.RECORD -> when (name) {
                "D5" -> if (state == IDLE) {
                    source = 1 - source
                    message = if (source == SRC_PHONE && !phoneActive) {
                        Mk3Service.instance?.requestPhoneCapture()
                        "Allow phone audio: see the phone (notification or app button)"
                    } else ""
                }
                "D6" -> if (state == IDLE) thresholdMode = !thresholdMode
                "D8" -> startStop()
            }
            Tab.EDIT -> when (name) {
                "D4" -> toggleSnap()
                "D7" -> previewToggle()
                "D5" -> { normalise = !normalise; computeSlices() }
                "D6" -> { reverse = !reverse; computeSlices() }
                "D8" -> save()
            }
            Tab.SLICE -> when (name) {
                "D4" -> toggleSnap()
                "D5" -> { sliceDetect = !sliceDetect; computeSlices() }
                "D7" -> preview(edited(), 0)
                "D8" -> applySlices()
            }
        }
    }

    override fun lights(l: Lights) {
        l.set("SAMPLING", true)
        l.set("PLAY", ui.engine.playing)
        l.set("SHIFT", shift)
        l.set("STOP", "BROWSER")
        if (org.openmk3.core.Edition.full) l.set("PLUG-IN")
        l.set("D1", tab == Tab.RECORD)
        l.set("D2", tab == Tab.EDIT)
        l.set("D3", tab == Tab.SLICE)
        when (tab) {
            Tab.RECORD -> {
                l.set("D5", source == SRC_PHONE)
                l.set("D6", thresholdMode)
                l.set("D8", state != IDLE)
            }
            Tab.EDIT -> {
                l.set("D4", ui.zeroSnap)
                l.set("D5", normalise)
                l.set("D6", reverse)
                l.set("D7", "D8")
            }
            Tab.SLICE -> {
                l.set("D4", ui.zeroSnap)
                l.set("D5", sliceDetect)
                l.set("D7", "D8")
            }
        }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        when (tab) {
            Tab.RECORD -> ui.selectedSound = slot
            Tab.EDIT -> { ui.selectedSound = slot; loadFromPad(slot) }
            Tab.SLICE -> {
                // pad 1 = bottom-left = slot 0 plays slice 1
                if (slot < slices.size) sliceData(slot)?.let { preview(it, slices[slot]) }
            }
        }
    }

    override fun onKnob(knob: Int, delta: Int) {
        val step = wave.knobStep(shift)
        when (tab) {
            Tab.RECORD -> when (knob) {
                0 -> threshold = (threshold + delta / 1000f).coerceIn(0.002f, 0.9f)
                1 -> maxSeconds = (maxSeconds + if (delta > 0) 1 else -1).coerceIn(1, 30)
            }
            Tab.EDIT -> {
                when (knob) {
                    0 -> {
                        rawStart = (rawStart + delta * step).coerceIn(0, max(0, rawEnd - 48))
                        snapTrim(); focus = trimStart; wave.follow(trimStart)
                    }
                    1 -> {
                        rawEnd = (rawEnd + delta * step).coerceIn(min(takeFrames, rawStart + 48), takeFrames)
                        snapTrim(); focus = trimEnd; wave.follow(trimEnd)
                    }
                    2 -> fadeInMs = (fadeInMs + delta * (if (shift) 0.1f else 1f)).coerceIn(0f, 5000f)
                    3 -> fadeOutMs = (fadeOutMs + delta * (if (shift) 0.1f else 1f)).coerceIn(0f, 5000f)
                    4 -> if (stepped(delta)) {
                        // TRIGGER of the focused pad: play the whole sample, or only while held
                        val sp = ui.engine.params
                        sp[ui.selectedIndex, org.openmk3.core.audio.SoundParams.GATE] = if (gate) 0f else 1f
                        sp.save(ui.ctx)
                    }
                }
                if (knob <= 1) computeSlices()
            }
            Tab.SLICE -> when (knob) {
                0 -> if (stepped(delta)) { sliceDetect = !sliceDetect; computeSlices() }
                1 -> if (sliceDetect) {
                    sensitivity = (sensitivity + delta / 500f).coerceIn(0f, 1f); computeSlices()
                } else if (stepped(delta)) {
                    gridIdx = (gridIdx + if (delta > 0) 1 else -1).coerceIn(0, GRID.size - 1); computeSlices()
                }
            }
        }
    }

    /** Switch-type knobs move one step per notch-sized turn. */
    private fun stepped(delta: Int): Boolean {
        knobAcc += delta
        if (abs(knobAcc) < 25) return false
        knobAcc = 0
        return true
    }

    override fun onEncoder(delta: Int) {
        if (tab != Tab.RECORD) wave.zoom(delta, focus)
    }

    private fun toggleSnap() {
        ui.zeroSnap = !ui.zeroSnap
        snapTrim()
        computeSlices()
    }

    /** SLICE tab: pads with a slice are lit (bright while it plays); the rest are dark. */
    override fun padLight(slot: Int, now: Long): Int? {
        if (tab != Tab.SLICE) return null
        if (slot >= min(16, slices.size)) return 0
        val c = org.openmk3.core.mk3.Mk3Colors.BLUE
        val playing = ui.padPressure[slot] > 0
        return org.openmk3.core.mk3.Mk3Colors.value(c, if (playing) 3 else 1)
    }

    // =====================================================================================
    // screens
    // =====================================================================================

    private fun dButton(c: Canvas, i: Int, lbl: String, v: String, on: Boolean = false) {
        val x = 60f + (i % 4) * 120f
        c.drawText(lbl, x, 16f, dLabel)
        c.drawText(v, x, 34f, if (on) dOn else dValue)
    }

    private fun knob(c: Canvas, i: Int, lbl: String, v: String) {
        val x = 60f + (i % 4) * 120f
        c.drawText(lbl, x, 250f, dLabel)
        c.drawText(v, x, 268f, dValue)
    }

    private fun meter(c: Canvas, y: Float, lv: Float, showThr: Boolean) {
        c.drawRect(16f, y, 464f, y + 12f, cell)
        c.drawRect(16f, y, 16f + 448f * lv.coerceIn(0f, 1f), y + 12f, if (lv > 0.98f) warnFill else okFill)
        if (showThr) {
            val x = 16f + 448f * threshold
            c.drawRect(x - 1f, y - 3f, x + 1f, y + 15f, thrFill)
        }
    }

    private fun secs(frames: Int) = "%.3f s".format(frames.toDouble() / AudioEngine.SR)

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        // tabs on D1–D3
        for ((i, t) in Tab.values().withIndex()) l.drawText(t.name, 60f + i * 120f, 26f, if (t == tab) tabOn else tabText)
        l.drawRect(0f, 42f, 480f, 43f, sep)
        r.drawRect(0f, 42f, 480f, 43f, sep)
        l.drawRect(0f, 232f, 480f, 233f, sep)
        r.drawRect(0f, 232f, 480f, 233f, sep)
        when (tab) {
            Tab.RECORD -> drawRecord(l, r)
            Tab.EDIT -> drawEdit(l, r)
            Tab.SLICE -> drawSlice(l, r)
        }
        if (message.isNotEmpty()) {
            val bad = message.startsWith("Can't") || message.startsWith("Allow") || message.startsWith("No")
            l.drawText(ellipsize(message, 62), 16f, 222f, if (bad) warn else ok)
        }
    }

    private fun drawRecord(l: Canvas, r: Canvas) {
        dButton(r, 4, "SOURCE", if (source == SRC_PHONE) "PHONE" else "INPUT")
        dButton(r, 5, "START", if (thresholdMode) "THRESHOLD" else "MANUAL")
        dButton(r, 7, "RECORD", if (state == IDLE) "START" else "STOP", state != IDLE)
        l.drawText(if (source == SRC_PHONE) "Phone playback" else "Controller inputs", 16f, 70f, value)
        val st = when (state) {
            ARMED -> "WAITING FOR SIGNAL"
            RECORDING -> "● REC ${"%.1f".format(n.nativeRecFrames().toDouble() / AudioEngine.SR)} s"
            else -> "READY"
        }
        l.drawText(st, 16f, 106f, if (state == IDLE) big else recText)
        l.drawText("INPUT", 16f, 134f, label)
        meter(l, 140f, meterIn, thresholdMode && source == SRC_INPUT)
        l.drawText(if (phoneActive) "PHONE" else "PHONE (not allowed yet: confirm on the phone)", 16f, 172f, label)
        meter(l, 178f, meterPhone, thresholdMode && source == SRC_PHONE)
        knob(l, 0, "THRESHOLD", "${(threshold * 100).toInt()}%")
        knob(l, 1, "MAX LENGTH", "$maxSeconds s")
        drawWave(r, showTrim = false)
        knob(r, 7, "TARGET", ui.padLabel())
    }

    private fun drawEdit(l: Canvas, r: Canvas) {
        dButton(r, 4, "LEVEL", if (normalise) "NORMALISED" else "NORMALISE", normalise)
        dButton(r, 5, "DIRECTION", if (reverse) "REVERSED" else "REVERSE", reverse)
        dButton(r, 6, "PREVIEW", "PLAY")
        dButton(r, 7, "SAVE TO", ui.padLabel())
        dButton(l, 3, "SNAP", if (ui.zeroSnap) "ZERO-X ON" else "ZERO-X OFF", ui.zeroSnap)
        l.drawText("EDIT", 16f, 72f, title)
        l.drawText(ellipsize(takeName, 26), 80f, 72f, value)
        if (take != null) {
            l.drawText("Length ${secs(trimEnd - trimStart)} of ${secs(takeFrames)}", 16f, 106f, value)
            l.drawText("Pads open a pad's sample · D8 saves a new WAV", 16f, 136f, label)
            l.drawText("(the original file is kept) · Encoder: zoom, tilt: scroll", 16f, 156f, label)
        }
        knob(l, 0, "START", secs(trimStart))
        knob(l, 1, "END", secs(trimEnd))
        knob(l, 2, "FADE IN", "${fadeInMs.roundToInt()} ms")
        knob(l, 3, "FADE OUT", "${fadeOutMs.roundToInt()} ms")
        knob(r, 4, "TRIGGER", if (gate) "GATE" else "ONE-SHOT")
        drawWave(r, showTrim = true)
    }

    private fun drawSlice(l: Canvas, r: Canvas) {
        dButton(r, 4, "MODE", if (sliceDetect) "DETECT" else "GRID")
        dButton(r, 6, "PREVIEW", "PLAY")
        dButton(r, 7, "APPLY", "TO PADS")
        dButton(l, 3, "SNAP", if (ui.zeroSnap) "ZERO-X ON" else "ZERO-X OFF", ui.zeroSnap)
        l.drawText("SLICE", 16f, 72f, title)
        l.drawText(ellipsize(takeName, 26), 90f, 72f, value)
        val g = ui.groupLetter
        l.drawText("${slices.size} slices → $g" + "1–$g${min(16, slices.size)}", 16f, 106f, value)
        l.drawText("Pads play the slices · D8 replaces $g" + "1–$g${min(16, slices.size)}", 16f, 136f, label)
        l.drawText("Encoder: zoom · tilt ◀ ▶: scroll", 16f, 160f, label)
        knob(l, 0, "MODE", if (sliceDetect) "DETECT" else "GRID")
        if (sliceDetect) knob(l, 1, "SENSITIVITY", "${(sensitivity * 100).roundToInt()}%")
        else knob(l, 1, "SLICES", "${GRID[gridIdx]}")
        drawWave(r, showTrim = true, sliceMarks = true)
    }

    private fun drawWave(r: Canvas, showTrim: Boolean, sliceMarks: Boolean = false) {
        val top = 60f
        val bot = 200f
        if (take == null) {
            r.drawText("Nothing recorded yet", 16f, (top + bot) / 2, label)
            return
        }
        // playhead: the editor preview, or (EDIT tab) the focused pad when its sample is open
        var head = -1
        val pp = ui.engine.playPos(AudioEngine.PREVIEW_SLOT)
        if (pp >= 0f && previewBase >= 0) head = editedToTake(previewBase + pp.toInt())
        else if (tab == Tab.EDIT) {
            val ps = ui.engine.playPos(ui.selectedIndex)
            if (ps >= 0f && ui.engine.kit[ui.selectedIndex].frames == takeFrames) head = ps.toInt()
        }
        val marks = if (showTrim) intArrayOf(trimStart, trimEnd) else IntArray(0)
        val sl = if (sliceMarks) IntArray(slices.size) { editedToTake(slices[it]) } else IntArray(0)
        wave.setColour(org.openmk3.core.mk3.Mk3Colors.rgb(ui.soundColour(ui.selectedIndex)))
        wave.draw(r, 16f, top, bot,
            selA = if (showTrim) trimStart else -1, selB = trimEnd,
            markers = marks, slices = sl, playhead = head)
    }

    private fun ellipsize(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}

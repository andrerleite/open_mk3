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

/**
 * Sampling: record from the controller's audio inputs or from whatever the phone is playing,
 * trim, normalise, then save as a WAV and put it on the focused pad.
 *
 *  D1 source (INPUT / PHONE) · D2 start mode (MANUAL / THRESHOLD) · D4 start / stop
 *  D6 normalise · D7 preview · D8 save to the focused pad
 *  K1 threshold · K2 max length · K5 trim start · K6 trim end (SHIFT = fine)
 *  ENCODER PUSH start / stop · PADS choose the target pad · SAMPLING = back
 */
class SamplingPage(ui: Ui) : Page(ui) {

    override val playsPads = true

    private companion object {
        const val SRC_INPUT = 0
        const val SRC_PHONE = 1
        const val IDLE = 0
        const val ARMED = 1
        const val RECORDING = 2
        // Kept across visits, like the hardware workflow expects.
        var source = SRC_INPUT
        var thresholdMode = false
        var threshold = 0.05f
        var maxSeconds = 30
    }

    private val n = NativeEngine
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sampling-io") }

    private var state = IDLE
    private var meterIn = 0f
    private var meterPhone = 0f
    private var shift = false
    private var message = ""

    // The take being edited (stereo interleaved, 48 kHz).
    private var take: FloatArray? = null
    private var takeFrames = 0
    private var trimStart = 0
    private var trimEnd = 0
    private var normalise = false
    private var envMin = FloatArray(0)
    private var envMax = FloatArray(0)

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val dLabel = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val dValue = Theme.text(15f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val dOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
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
    private val sep = Theme.fill(Color.rgb(50, 50, 60))

    private val phoneActive get() = Mk3Service.instance?.phoneCaptureActive == true

    override fun onEnter() {
        ui.buttonLed("SAMPLING", 0x7F)
        if (source == SRC_PHONE && !phoneActive) Mk3Service.instance?.requestPhoneCapture()
    }

    override fun onExit() {
        if (state != IDLE) n.nativeRecStop()
        worker.shutdown()
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
        val frames = data.size / 2
        if (frames < 480) {
            message = "Nothing recorded"
            return
        }
        take = data
        takeFrames = frames
        trimStart = 0
        trimEnd = frames
        normalise = false
        buildEnvelope()
        message = "Recorded ${"%.2f".format(frames.toDouble() / AudioEngine.SR)} s"
        AppLog.i("Sampling: $message")
    }

    private fun buildEnvelope() {
        val d = take ?: return
        val cols = 448
        envMin = FloatArray(cols)
        envMax = FloatArray(cols)
        for (c in 0 until cols) {
            val a = (c.toLong() * takeFrames / cols).toInt()
            val b = max(a + 1, ((c + 1).toLong() * takeFrames / cols).toInt())
            var lo = 0f
            var hi = 0f
            val stride = max(1, (b - a) / 64) // enough points for the shape, cheap to compute
            var f = a
            while (f < b) {
                val v = (d[f * 2] + d[f * 2 + 1]) * 0.5f
                if (v < lo) lo = v
                if (v > hi) hi = v
                f += stride
            }
            envMin[c] = lo
            envMax[c] = hi
        }
    }

    /** The trimmed (and optionally normalised) take, with short fades at cut points. */
    private fun edited(): FloatArray? {
        val d = take ?: return null
        val len = trimEnd - trimStart
        if (len <= 0) return null
        val out = d.copyOfRange(trimStart * 2, trimEnd * 2)
        var gain = 1f
        if (normalise) {
            var peak = 0f
            for (x in out) peak = max(peak, abs(x))
            if (peak > 1e-5f) gain = 0.98f / peak
        }
        val fadeIn = if (trimStart > 0) min(64, len) else 0
        val fadeOut = if (trimEnd < takeFrames) min(256, len) else 0
        for (f in 0 until len) {
            var g = gain
            if (f < fadeIn) g *= f.toFloat() / fadeIn
            if (f >= len - fadeOut) g *= (len - f).toFloat() / fadeOut
            out[f * 2] *= g
            out[f * 2 + 1] *= g
        }
        return out
    }

    private fun preview() {
        val e = edited() ?: return
        ui.engine.preview(Sample("Preview", e, 0, 2, null, ""))
    }

    private fun save() {
        val e = edited() ?: run { message = "Record something first"; return }
        val slot = ui.selectedSound
        message = "Saving…"
        worker.execute {
            try {
                val dir = File(BrowserPage.ROOT, "Recordings")
                dir.mkdirs()
                var i = 1
                var f: File
                do { f = File(dir, "Rec %03d.wav".format(i++)) } while (f.exists())
                WavWriter.write(f, e, 2)
                val secs = (e.size / 2).toDouble() / AudioEngine.SR
                val s = Sample(f.nameWithoutExtension, e, 0, 2, f.absolutePath,
                    "48.0 kHz · 24-bit · stereo · ${"%.2f".format(secs)} s")
                ui.post {
                    ui.engine.setSample(slot, s)
                    ui.engine.params.resetRange(slot)
                    ui.engine.params.save(ui.ctx)
                    KitStore.save(ui.ctx, ui.engine.kit)
                    message = "Saved ${f.name} → pad ${slot + 1}"
                    AppLog.i("Sampling: saved ${f.absolutePath} to pad ${slot + 1}")
                    ui.engine.trigger(slot, 0.8f)
                    ui.invalidate()
                }
            } catch (ex: Exception) {
                ui.post { message = "Can't save: ${ex.message}"; ui.invalidate() }
            }
        }
    }

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

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (name == "SHIFT") { shift = down; return }
        if (!down) return
        val e = ui.engine
        when (name) {
            "D1" -> if (state == IDLE) {
                source = 1 - source
                if (source == SRC_PHONE && !phoneActive) {
                    Mk3Service.instance?.requestPhoneCapture()
                    message = "Allow phone audio: see the phone (notification or app button)"
                } else message = ""
            }
            "D2" -> if (state == IDLE) thresholdMode = !thresholdMode
            "D4", "ENCODER PUSH" -> startStop()
            "D6" -> normalise = !normalise
            "D7" -> preview()
            "D8" -> save()
            "PLAY" -> if (e.playing) e.transportStop() else e.transportPlay()
            "STOP" -> e.transportStop()
            "SAMPLING", "BROWSER" -> ui.showHome()
        }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        ui.selectedSound = slot
    }

    override fun onKnob(knob: Int, delta: Int) {
        val step = if (shift) max(1, takeFrames / 20000) else max(1, takeFrames / 1000)
        when (knob) {
            0 -> threshold = (threshold + delta / 1000f).coerceIn(0.002f, 0.9f)
            1 -> maxSeconds = (maxSeconds + if (delta > 0) 1 else -1).coerceIn(1, 30)
            4 -> trimStart = (trimStart + delta * step).coerceIn(0, max(0, trimEnd - 48))
            5 -> trimEnd = (trimEnd + delta * step).coerceIn(min(takeFrames, trimStart + 48), takeFrames)
        }
    }

    override fun onEncoder(delta: Int) = onKnob(5, delta)

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

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        // ---- left: record ----
        l.drawColor(Theme.BG)
        dButton(l, 0, "SOURCE", if (source == SRC_PHONE) "PHONE" else "INPUT")
        dButton(l, 1, "START", if (thresholdMode) "THRESHOLD" else "MANUAL")
        dButton(l, 3, "RECORD", if (state == IDLE) "START" else "STOP", state != IDLE)
        l.drawRect(0f, 42f, 480f, 43f, sep)
        l.drawText("SAMPLING", 16f, 72f, title)
        val srcName = if (source == SRC_PHONE) "Phone playback" else "Controller inputs"
        l.drawText(srcName, 140f, 71f, value)
        val st = when (state) {
            ARMED -> "WAITING FOR SIGNAL"
            RECORDING -> "● REC ${"%.1f".format(n.nativeRecFrames().toDouble() / AudioEngine.SR)} s"
            else -> "READY"
        }
        l.drawText(st, 16f, 112f, if (state == IDLE) big else recText)

        l.drawText("INPUT", 16f, 140f, label)
        meter(l, 146f, meterIn, thresholdMode && source == SRC_INPUT)
        l.drawText(if (phoneActive) "PHONE" else "PHONE (not allowed yet: confirm on the phone)", 16f, 178f, label)
        meter(l, 184f, meterPhone, thresholdMode && source == SRC_PHONE)
        if (message.isNotEmpty()) l.drawText(ellipsize(message, 62), 16f, 222f, if (message.startsWith("Can't") || message.startsWith("Allow")) warn else ok)
        l.drawRect(0f, 232f, 480f, 233f, sep)
        knob(l, 0, "THRESHOLD", "${(threshold * 100).toInt()}%")
        knob(l, 1, "MAX LENGTH", "$maxSeconds s")

        // ---- right: edit ----
        r.drawColor(Theme.BG)
        dButton(r, 5, "LEVEL", if (normalise) "NORMALISED" else "NORMALISE", normalise)
        dButton(r, 6, "PREVIEW", "PLAY")
        dButton(r, 7, "SAVE TO", "PAD ${ui.selectedSound + 1}")
        r.drawRect(0f, 42f, 480f, 43f, sep)

        val top = 60f
        val bot = 200f
        val mid = (top + bot) / 2
        val half = (bot - top) / 2
        if (take == null) {
            r.drawText("Nothing recorded yet", 16f, mid, label)
        } else {
            val cols = envMin.size
            val sCol = (trimStart.toLong() * cols / takeFrames).toInt()
            val eCol = (trimEnd.toLong() * cols / takeFrames).toInt()
            for (c in 0 until cols) {
                val x = 16f + c
                val y0 = mid - envMax[c].coerceIn(-1f, 1f) * half
                val y1 = mid - envMin[c].coerceIn(-1f, 1f) * half
                r.drawRect(x, y0, x + 1f, max(y1, y0 + 1f), if (c in sCol until eCol) waveIn else waveOut)
            }
            r.drawRect(16f + sCol - 1f, top - 6f, 16f + sCol + 1f, bot + 6f, marker)
            r.drawRect(16f + eCol - 1f, top - 6f, 16f + eCol + 1f, bot + 6f, marker)
            val len = (trimEnd - trimStart).toDouble() / AudioEngine.SR
            r.drawText("${"%.3f".format(len)} s selected of ${"%.2f".format(takeFrames.toDouble() / AudioEngine.SR)} s", 16f, 222f, label)
        }
        r.drawRect(0f, 232f, 480f, 233f, sep)
        knob(r, 4, "START", "%.3f s".format(trimStart.toDouble() / AudioEngine.SR))
        knob(r, 5, "END", "%.3f s".format(trimEnd.toDouble() / AudioEngine.SR))
        knob(r, 7, "TARGET", ellipsize(ui.engine.kit[ui.selectedSound].name, 12))
    }

    private fun ellipsize(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}

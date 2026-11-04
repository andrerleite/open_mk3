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
 *  RECORD  D5 source (INPUT / PHONE) · D6 start (MANUAL / THRESHOLD / SYNC) · D8 start / stop
 *          K1 threshold · K2 max length (SYNC: length in bars, or FREE) · pads choose the target
 *          pad; the recording is saved as a WAV and loaded onto it at once
 *  EDIT    the take, or the focused pad's sample (pads choose which); ◀ ▶ pages:
 *          1 PLAY RANGE  K1 start · K2 end (live on the pad) · K5 trigger
 *                        D7 preview · D8 save the range as a new WAV on the focused pad
 *          2 FUNCTIONS   K1 / K2 selection start / end · K3 or D5 / D6 function · K4 stretch
 *                        D7 preview the selection · D8 apply · SHIFT + D8 undo
 *                        (applied to a pad's sample: written to a new WAV and loaded on the pad)
 *  SLICE   ◀ ▶ pages: 1 SLICER (K1 mode SPLIT / GRID / DETECT / MANUAL, K2 slices,
 *          note value, sensitivity or auto-snap, K3 / K4 tempo) · 2 EDIT (K1 slice, K3 start,
 *          K4 end, D5 split, D6 remove, D7 delete all) · 3 APPLY (K1 mono, K2 pattern)
 *          Pads play the slices (SHIFT + D7 / D8: the previous / next 16); in MANUAL, pad 1
 *          starts the sample and the flashing next pad adds a slice where it plays (live slicing)
 *          D8 APPLY…: the focused sound (slices on its keys, pads switch to KEYBOARD) or the
 *          whole group (slices on pads 1–16) · D8 OK
 *
 *  D4 ZONE: the focused sound's zones (each with its own range, loop, tune, level, envelope)
 *  EDIT / SLICE: ENCODER turn = zoom · ENCODER left / right = scroll · D5 (first page) = zero-crossing snap
 *  ENCODER push = start / stop recording · SHIFT = fine knob steps · SAMPLING = back
 */
class SamplingPage(ui: Ui, startTab: Int = -1) : Page(ui) {

    override val handlesTransport = true

    private enum class Tab { RECORD, EDIT, SLICE }

    override val playsPads get() = tab != Tab.SLICE

    private companion object {
        const val SRC_INPUT = 0
        const val SRC_PHONE = 1
        const val IDLE = 0
        const val ARMED = 1
        const val RECORDING = 2
        const val SYNCED = 3
        const val MODE_MANUAL = 0
        const val MODE_THRESHOLD = 1
        const val MODE_SYNC = 2
        val SYNC_BARS = intArrayOf(1, 2, 4, 8, 16, 0)  // 0 = FREE (from the bar until stopped)
        val SPLITS = intArrayOf(4, 8, 16, 32)
        val GRIDS = intArrayOf(4, 8, 16, 32)  // note values
        val MODES = listOf("SPLIT", "GRID", "DETECT", "MANUAL")
        const val M_SPLIT = 0
        const val M_GRID = 1
        const val M_DETECT = 2
        const val M_MANUAL = 3
        // Kept across visits.
        var source = SRC_INPUT
        var startMode = MODE_MANUAL
        var syncIdx = 0
        var threshold = 0.05f
        var maxSeconds = 30
        var lastTab = Tab.RECORD
        var sliceMode = M_SPLIT
        var splitIdx = 2
        var gridIdx = 2
        var autoSnap = true
        var bpmAuto = true
        var bpmAdjust = 0      // AUTO: 0 as detected, 1 half, 2 double
        var bpmManual = 120.0
        var slicePage = 0
        var mono = false
        var patternReplace = true
        var sensitivity = 0.5f
        var editPage = 0
        var fnIdx = 0
        var stretchPct = 100
    }

    private val n = NativeEngine
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sampling-io") }

    private var tab = if (startTab in 0..2) Tab.values()[startTab] else lastTab
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
    private var sliceEnd = IntArray(0) // slice end frames (a slice can end before the next starts)
    private var selSlice = 0
    private var sliceBank = 0
    private var live = false           // MANUAL: the sample plays and pads add slice points
    private var applying = false       // APPLY…: choosing the target
    private var applyGroup = false
    private var onsetsAll: IntArray? = null  // for AUTO-SNAP
    // FUNCTIONS page: the selection (take frames), undo, and the WAV the edits of a pad go to
    private var selA = 0
    private var selB = 0
    private val undo = ArrayDeque<FloatArray>()
    private var busy = false
    private var editFile: File? = null

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
    private val pageText = Theme.text(14f, Theme.DIM, align = Paint.Align.RIGHT)

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
        selA = 0
        selB = takeFrames
        onsetsAll = null
        live = false
        if (sliceMode == M_MANUAL) { slices = IntArray(0); sliceEnd = IntArray(0) }
        undo.clear()
        editFile = null
        wave.set(data, 2)
        computeSlices()
    }

    /** The take after a destructive function: the play range becomes the whole sample. */
    private fun replaceTake(data: FloatArray, a: Int, b: Int) {
        take = data
        takeFrames = data.size / 2
        trimStart = 0; trimEnd = takeFrames
        rawStart = 0; rawEnd = takeFrames
        selA = a.coerceIn(0, takeFrames)
        selB = b.coerceIn(selA, takeFrames)
        if (selB == selA && selA == 0) selB = takeFrames
        focus = selA
        previewBase = -1
        wave.set(data, 2)
        computeSlices()
    }

    private val fn get() = org.openmk3.core.audio.SampleOps.Fn.values()[fnIdx]

    /** D8 on the FUNCTIONS page. */
    private fun applyFn() {
        val d = take ?: run { message = "Record or open something first"; return }
        if (busy) return
        busy = true
        val f = fn
        val a = selA; val b = selB
        val ratio = stretchPct / 100f
        message = "${f.label}…"
        worker.execute {
            val res = try { org.openmk3.core.audio.SampleOps.apply(f, d, a, b, ratio) } catch (e: Throwable) { null }
            ui.post {
                busy = false
                when {
                    res == null -> message = "Can't ${f.label.lowercase()}: out of memory?"
                    !res.changed -> message = when (f) {
                        org.openmk3.core.audio.SampleOps.Fn.COPY -> "Copied ${secs(b - a)}"
                        org.openmk3.core.audio.SampleOps.Fn.PASTE -> "Nothing copied yet"
                        org.openmk3.core.audio.SampleOps.Fn.STRETCH -> "Stretch: choose an amount (K4), select at least 0.1 s"
                        else -> "No change"
                    }
                    else -> {
                        if (take === d) {
                            undo.addLast(d)
                            while (undo.size > 4 || undo.sumOf { it.size.toLong() } > 24_000_000L) undo.removeFirst()
                        }
                        replaceTake(res.data, res.selA, res.selB)
                        message = "${f.label} done" + if (f == org.openmk3.core.audio.SampleOps.Fn.CUT) " (cut ${secs(b - a)})" else ""
                        writeToPad()
                    }
                }
                ui.invalidate()
            }
        }
    }

    private fun undoFn() {
        val prev = undo.removeLastOrNull() ?: run { message = "Nothing to undo"; return }
        replaceTake(prev, 0, prev.size / 2)
        message = "Undone"
        writeToPad()
    }

    /** A pad's sample being edited: the result goes to a new WAV (the original is kept) on the pad. */
    private fun writeToPad() {
        val snd = editSound
        if (snd < 0) return
        val data = take ?: return
        val base = takeName.removeSuffix(" edit")
        worker.execute {
            try {
                val f = editFile ?: run {
                    val dir = File(BrowserPage.ROOT, "Recordings").apply { mkdirs() }
                    var i = 1
                    var c: File
                    do { c = File(dir, if (i == 1) "$base edit.wav" else "$base edit $i.wav"); i++ } while (c.exists())
                    c
                }
                editFile = f
                WavWriter.write(f, data, 2)
                val smp = Sample(f.nameWithoutExtension, data, 0, 2, f.absolutePath, info(data))
                ui.post {
                    if (editSound == snd) {
                        ui.engine.setSample(snd, smp)
                        ui.engine.params.resetRange(snd)
                        ui.engine.params.save(ui.ctx)
                        KitStore.save(ui.ctx, ui.engine.kit)
                    }
                    ui.invalidate()
                }
            } catch (ex: Exception) {
                ui.post { message = "Can't save: ${ex.message}"; ui.invalidate() }
            }
        }
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

    /** Sound whose sample is in the editor (-1: a new recording). Its START / END follow the play range. */
    private var editSound = -1

    /** Opens the sample on [slot] in the editor (mono becomes stereo), with the pad's play range. */
    private fun loadFromPad(slot: Int) {
        val snd = ui.snd(slot)
        val s = ui.engine.kit[snd]
        if (s.frames < 2) { message = "${ui.padLabel(slot)} is empty"; editSound = -1; return }
        val st = if (s.channels == 2) s.data.copyOf() else FloatArray(s.frames * 2) { s.data[it / 2] }
        setTake(st, s.name)
        editSound = snd
        // the play range is the pad's own START / END (non-destructive)
        val sp = ui.engine.params
        rawStart = (sp[snd, org.openmk3.core.audio.SoundParams.START] * takeFrames).toInt().coerceIn(0, takeFrames - 1)
        rawEnd = (sp[snd, org.openmk3.core.audio.SoundParams.END] * takeFrames).toInt().coerceIn(rawStart + 1, takeFrames)
        snapTrim()
        message = "Editing ${ui.padLabel(slot)}: ${s.name}"
    }

    /** The play range goes to the pad at once: hitting the pad plays the trimmed sample. */
    private fun pushPlayRange() {
        if (editSound < 0 || takeFrames <= 0) return
        val sp = ui.engine.params
        sp[editSound, org.openmk3.core.audio.SoundParams.START] = trimStart.toFloat() / takeFrames
        sp[editSound, org.openmk3.core.audio.SoundParams.END] = trimEnd.toFloat() / takeFrames
        rangeDirtyAt = android.os.SystemClock.uptimeMillis()
    }
    private var rangeDirtyAt = 0L

    /** The edited sample: trimmed, reversed, normalised, faded. */
    private var editedKey: List<Any?>? = null
    private var editedCache: FloatArray? = null

    /** The edited sample, rebuilt only when the take or an edit setting changes. */
    private fun edited(): FloatArray? {
        val key = listOf(System.identityHashCode(take), trimStart, trimEnd, reverse, normalise, fadeInMs, fadeOutMs, takeFrames)
        if (key == editedKey) return editedCache
        editedCache = buildEdited()
        editedKey = key
        return editedCache
    }

    private fun buildEdited(): FloatArray? {
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
        if (startMode == MODE_SYNC) {
            val bars = SYNC_BARS[syncIdx]
            val frames = if (bars > 0) (bars * 4 * 60.0 / ui.engine.bpm * AudioEngine.SR).roundToInt() else maxSeconds * AudioEngine.SR
            n.nativeRecArmSync(source, frames)
            state = SYNCED
        } else {
            n.nativeRecArm(source, startMode == MODE_THRESHOLD, threshold, maxSeconds)
            state = if (startMode == MODE_THRESHOLD) ARMED else RECORDING
        }
        message = ""
        AppLog.i("Sampling: armed (${if (source == SRC_PHONE) "phone" else "input"}, ${when (startMode) { MODE_THRESHOLD -> "threshold ${"%.2f".format(threshold)}"; MODE_SYNC -> "sync ${syncText()}"; else -> "manual" }}, max $maxSeconds s)")
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
        editSound = -1
        AppLog.i("Sampling: recorded ${"%.2f".format(takeFrames.toDouble() / AudioEngine.SR)} s")
        tab = Tab.EDIT
        editPage = 0
        save()  // straight onto the target pad (as a new WAV), then edited from there
    }

    // =====================================================================================
    // slicing
    // =====================================================================================

    private var slicesDirty = false
    private var slicesAt = 0L
    /** Marks the slices for recomputing (at most every 120 ms, so knob turns stay smooth). */
    private fun computeSlices() { slicesDirty = true }
    private fun ensureSlices(force: Boolean = false) {
        if (!slicesDirty) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - slicesAt < 120) return
        slicesAt = now
        slicesDirty = false
        computeSlicesNow()
    }

    private fun computeSlicesNow() {
        val len = trimEnd - trimStart
        if (take == null || len <= 0) { setSlices(IntArray(0)); return }
        if (sliceMode == M_MANUAL) {  // the user's slices stay; only clamp them to the region
            val keep = slices.indices.filter { slices[it] < len - 1 }
            val st = IntArray(keep.size) { slices[keep[it]] }
            val en = IntArray(keep.size) { min(sliceEnd[keep[it]], len) }
            slices = st; sliceEnd = en
            selSlice = selSlice.coerceIn(0, max(0, slices.size - 1))
            return
        }
        val raw = when (sliceMode) {
            M_SPLIT -> { val k = SPLITS[splitIdx]; IntArray(k) { (it.toLong() * len / k).toInt() } }
            M_GRID -> {
                val step = AudioEngine.SR * 60.0 / sliceBpm() * 4.0 / GRIDS[gridIdx]
                val k = kotlin.math.ceil(len / step - 0.01).toInt().coerceIn(1, 128)
                IntArray(k) { (it * step).toInt() }.filter { it < len }.toIntArray()
            }
            else -> detectOnsets(63)
        }
        val d = if (ui.zeroSnap) edited() else null
        setSlices(if (d == null) raw else IntArray(raw.size) { i ->
            if (i == 0) raw[0] else WaveView.zeroCross(d, 2, raw[i], 1200).coerceIn(raw[i - 1] + 1, len - 1)
        })
    }

    /** Joined slices: each one ends where the next starts. */
    private fun setSlices(st: IntArray) {
        val len = trimEnd - trimStart
        slices = st
        sliceEnd = IntArray(st.size) { if (it + 1 < st.size) st[it + 1] else len }
        selSlice = selSlice.coerceIn(0, max(0, st.size - 1))
        sliceBank = sliceBank.coerceIn(0, max(0, (st.size - 1) / 16))
    }

    /** Tempo of the sample: detected from its length (a whole number of beats), or set by hand. */
    private fun detectedBpm(): Double {
        val secs = (trimEnd - trimStart).toDouble() / AudioEngine.SR
        if (secs <= 0.05) return 120.0
        var beats = 1.0
        while (beats * 60.0 / secs < 85.0) beats *= 2.0
        return beats * 60.0 / secs
    }
    private fun sliceBpm(): Double = if (!bpmAuto) bpmManual else detectedBpm() * when (bpmAdjust) { 1 -> 0.5; 2 -> 2.0; else -> 1.0 }

    /** Any change by hand makes the slices MANUAL (they're kept from then on). */
    private fun toManual() { if (sliceMode != M_MANUAL) { ensureSlices(force = true); sliceMode = M_MANUAL } }

    /** MANUAL, live: a slice point where the sample is playing (snapped to a nearby hit). */
    private fun addLiveSlice() {
        val pp = ui.engine.playPos(AudioEngine.PREVIEW_SLOT)
        if (pp < 0f || previewBase < 0) { live = false; return }
        var pos = previewBase + pp.toInt()
        val len = trimEnd - trimStart
        if (autoSnap) {
            val on = onsetsAll ?: detectOnsets(512, 0.85f).also { onsetsAll = it }
            on.minByOrNull { abs(it - pos) }?.let { if (abs(it - pos) < AudioEngine.SR / 10) pos = it }
        }
        if (slices.any { abs(it - pos) < 480 } || pos >= len - 480) return
        val st = (slices.toList() + pos).sorted().toIntArray()
        setSlices(st)
        selSlice = st.indexOf(pos)
        sliceBank = selSlice / 16
    }

    /**
     * Transient detection on the edited region. The attack edge of a hit is in its high
     * frequencies, so the detector follows the energy of the signal's first difference in 5 ms
     * hops, in dB. A hit is a local peak where that energy jumps above the level of the last
     * ~20 ms by the threshold SENSITIVITY sets (100 %: 3 dB, 0 %: 15 dB) and that is no more than
     * 50 dB below the loudest part. Hits are at least 50 ms apart; the 16 strongest are kept, in
     * time order, each placed at the quiet point just before its attack.
     */
    private fun detectOnsets(maxSlices: Int, sens: Float = sensitivity): IntArray {
        val d = edited() ?: return IntArray(0)
        val len = d.size / 2
        val hop = 240
        val hops = len / hop
        if (hops < 4) return intArrayOf(0)
        val lev = FloatArray(hops)
        var prev = 0f
        var top = -200f
        for (h in 0 until hops) {
            var acc = 0f
            for (f in h * hop until (h + 1) * hop) {
                val m = (d[f * 2] + d[f * 2 + 1]) * 0.5f
                val diff = m - prev
                prev = m
                acc += diff * diff
            }
            lev[h] = 10f * kotlin.math.log10(acc / hop + 1e-12f)
            top = max(top, lev[h])
        }
        val thr = 3f + (1f - sens) * 12f
        val floor = top - 50f
        val rise = FloatArray(hops)
        for (h in 1 until hops) {
            var ref = lev[h - 1]
            for (k in 2..4) if (h - k >= 0) ref = max(ref, lev[h - k])
            rise[h] = lev[h] - ref
        }
        val hits = ArrayList<Pair<Int, Float>>()
        var last = -100
        for (h in 1 until hops - 1) {
            if (rise[h] < thr || lev[h] < floor) continue
            if (rise[h] < rise[h - 1] || rise[h] < rise[h + 1]) continue  // the steepest point of the rise
            if (h - last < 10) {  // within 50 ms: keep the stronger one
                if (hits.isNotEmpty() && rise[h] > hits.last().second) { hits[hits.size - 1] = h to rise[h]; last = h }
                continue
            }
            hits.add(h to rise[h])
            last = h
        }
        // the strongest (the start of the sample counts as one), back in time order
        val kept = hits.sortedByDescending { it.second }.take(maxSlices - 1).map { it.first }.sorted()
        val out = ArrayList<Int>()
        out.add(0)
        for (h in kept) {
            // back up to the quietest hop just before the attack
            var b = h
            for (k in 1..3) if (h - k >= 0 && lev[h - k] < lev[b]) b = h - k
            val pos = b * hop
            if (pos - out.last() >= 2400) out.add(pos)
        }
        return out.toIntArray()
    }

    private fun sliceData(i: Int): FloatArray? {
        ensureSlices(force = true)
        val d = edited() ?: return null
        val len = d.size / 2
        if (i >= slices.size) return null
        val a = slices[i]
        val b = min(sliceEnd[i], len)
        if (b - a < 2) return null
        val out = d.copyOfRange(a * 2, b * 2)
        val fo = min(240, b - a)  // 5 ms fade at the slice end
        val n2 = b - a
        for (f in n2 - fo until n2) { val g = (n2 - f).toFloat() / fo; out[f * 2] *= g; out[f * 2 + 1] *= g }
        return out
    }

    /**
     * APPLY… OK. To a SOUND: the slices go on its keys as zones (each on the key its pad plays in
     * KEYBOARD mode, so pad 1 plays slice 1), the first one also as the sound's sample, and the
     * pads switch to KEYBOARD. To the GROUP: slices 1–16 on pads 1–16 (MONO: one voice each and
     * all in choke group 1). PATTERN: the pattern gets a note for each slice, at its place in the
     * loop, so it plays the original back (and follows the song tempo).
     */
    private fun applySlices() {
        ensureSlices(force = true)
        val count = min(slices.size, 16)
        if (count == 0) { message = "No slices"; return }
        val parts = (0 until count).mapNotNull { i -> sliceData(i)?.let { i to it } }
        val toGroup = applyGroup
        val target = ui.selectedIndex
        val keys = IntArray(count) { ui.keys.noteFor(it) }
        val starts = slices.copyOf(); val ends = sliceEnd.copyOf()
        val bpm = sliceBpm()
        val base = takeName
        applying = false
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
                    val e = ui.engine
                    val sp = e.params
                    val pat = e.pattern
                    val stepFrames = AudioEngine.SR * 60.0 / bpm / 4.0
                    if (toGroup) {
                        for ((i, smp) in samples) {
                            val snd = ui.snd(i)
                            e.setSample(snd, smp)
                            sp.resetRange(snd)
                            e.zones.clear(snd)
                            if (sp[snd, org.openmk3.core.audio.SoundParams.SRC].roundToInt() != 0) sp.setSource(snd, 0)
                            if (mono) { sp[snd, org.openmk3.core.audio.SoundParams.POLY] = 1f; sp[snd, org.openmk3.core.audio.SoundParams.CHOKE] = 1f }
                        }
                        if (patternReplace) {
                            for ((i, _) in samples) pat.clear(ui.snd(i))
                            for ((i, _) in samples) {
                                val st = starts[i] / stepFrames
                                val step = st.toInt()
                                if (step < org.openmk3.core.audio.Pattern.STEPS)
                                    pat.recordNote(ui.snd(i), step, org.openmk3.core.audio.Pattern.ROOT, 0.8f, (st - step).toFloat(), ((ends[i] - starts[i]) / stepFrames).toFloat())
                            }
                        }
                        ui.setPadMode(Ui.PadMode.PAD)
                        message = "${samples.size} slices → ${ui.groupLetter}1–${ui.groupLetter}${samples.size}" + if (mono) " (mono)" else ""
                    } else {
                        if (sp[target, org.openmk3.core.audio.SoundParams.SRC].roundToInt() != 0) sp.setSource(target, 0)
                        e.zones.clear(target)
                        samples.firstOrNull()?.let { e.setSample(target, it.second); sp.resetRange(target) }
                        for ((i, smp) in samples) {
                            val k = e.zones.add(target, smp, keys[i])
                            if (k >= 0) e.zones.update(target, k) { z -> z.lo = keys[i]; z.hi = keys[i] }
                        }
                        if (patternReplace) {
                            pat.clear(target)
                            for ((i, _) in samples) {
                                val st = starts[i] / stepFrames
                                val step = st.toInt()
                                if (step < org.openmk3.core.audio.Pattern.STEPS)
                                    pat.recordNote(target, step, keys[i], 0.8f, (st - step).toFloat(), ((ends[i] - starts[i]) / stepFrames).toFloat())
                            }
                        }
                        ui.setPadMode(Ui.PadMode.KEYBOARD)
                        message = "${samples.size} slices → ${ui.padLabel()} keys ${org.openmk3.core.audio.Keys.noteName(keys.first())}–${org.openmk3.core.audio.Keys.noteName(keys.last())}"
                    }
                    if (patternReplace) {
                        val total = ((trimEnd - trimStart) / stepFrames).let { kotlin.math.ceil(it - 0.05).toInt() }
                        pat.setLength(target / org.openmk3.core.audio.Pattern.PADS, total.coerceIn(1, org.openmk3.core.audio.Pattern.STEPS))
                        pat.save(ui.ctx)
                    }
                    sp.save(ui.ctx)
                    e.zones.save(ui.ctx)
                    KitStore.save(ui.ctx, e.kit)
                    AppLog.i("Sampling: ${samples.size} slices saved in ${dir.absolutePath}; $message")
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
        previewBase = base; selPreview = -1
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
                    loadFromPad(ui.selectedSound)  // the saved sample is now the pad's, full range
                    message = "Saved ${f.name} → $label"
                    AppLog.i("Sampling: saved ${f.absolutePath} to $label")
                    if (!ui.engine.playing) ui.engine.trigger(slot, 0.8f)  // audition (not over a running song)
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
        if (rangeDirtyAt != 0L && now - rangeDirtyAt > 1500) { ui.engine.params.save(ui.ctx); rangeDirtyAt = 0L }
        if (slicesDirty) { ensureSlices(); if (!slicesDirty) ui.invalidate() }
        if (live && !ui.engine.isSounding(AudioEngine.PREVIEW_SLOT)) live = false
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

    private val FNS = org.openmk3.core.audio.SampleOps.Fn.values().size

    /** D7 on the FUNCTIONS page: play the selection (press again to stop). */
    private fun previewSel() {
        if (ui.engine.isSounding(AudioEngine.PREVIEW_SLOT)) { ui.engine.killAll(AudioEngine.PREVIEW_SLOT); return }
        val d = take ?: return
        if (selB - selA < 2) return
        previewBase = -1
        selPreview = selA
        ui.engine.preview(Sample("Preview", d.copyOfRange(selA * 2, selB * 2), 0, 2, null, ""))
    }
    private var selPreview = -1

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
            "D4" -> { lastTab = tab; ui.show(ZonePage(ui)); return }
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
                "D6" -> if (state == IDLE) startMode = (startMode + 1) % 3
                "D8" -> startStop()
            }
            Tab.EDIT -> when (name) {
                "PAGE LEFT" -> editPage = 0
                "PAGE RIGHT" -> editPage = 1
                "D7" -> if (editPage == 0) previewToggle() else previewSel()
                "D5" -> if (editPage == 1) fnIdx = (fnIdx - 1 + FNS) % FNS else toggleSnap()
                "D6" -> if (editPage == 1) fnIdx = (fnIdx + 1) % FNS
                "D8" -> if (editPage == 0) save() else if (shift) undoFn() else applyFn()
            }
            Tab.SLICE -> when {
                applying -> when (name) {
                    "D5" -> applyGroup = false
                    "D6" -> applyGroup = true
                    "D7" -> applying = false
                    "D8" -> applySlices()
                }
                shift && name == "D7" -> sliceBank = max(0, sliceBank - 1)
                shift && name == "D8" -> sliceBank = min(max(0, (slices.size - 1) / 16), sliceBank + 1)
                name == "PAGE LEFT" -> slicePage = max(0, slicePage - 1)
                name == "PAGE RIGHT" -> slicePage = min(2, slicePage + 1)
                name == "D5" && slicePage == 0 -> toggleSnap()
                name == "D8" -> { ensureSlices(force = true); if (slices.isEmpty()) message = "No slices" else applying = true }
                slicePage == 1 -> when (name) {
                    "D5" -> {  // SPLIT: at the playhead while the sample plays, else the selected slice in half
                        if (live || (ui.engine.isSounding(AudioEngine.PREVIEW_SLOT) && previewBase == 0)) { toManual(); addLiveSlice() }
                        else if (selSlice < slices.size && sliceEnd[selSlice] - slices[selSlice] > 960) {
                            toManual()
                            val mid = (slices[selSlice] + sliceEnd[selSlice]) / 2
                            val st = (slices.toList() + mid).sorted().toIntArray()
                            val en = IntArray(st.size) { k -> val j = slices.indexOf(st[k]); if (j >= 0) (if (j == selSlice) mid else sliceEnd[j]) else sliceEnd[selSlice] }
                            slices = st; sliceEnd = en
                        }
                    }
                    "D6" -> if (selSlice in 1 until slices.size) {  // REMOVE: join with the previous slice
                        toManual()
                        val i = selSlice
                        sliceEnd[i - 1] = max(sliceEnd[i - 1], sliceEnd[i])
                        slices = slices.filterIndexed { k, _ -> k != i }.toIntArray()
                        sliceEnd = sliceEnd.filterIndexed { k, _ -> k != i }.toIntArray()
                        selSlice = i - 1
                    }
                    "D7" -> { sliceMode = M_MANUAL; slices = IntArray(0); sliceEnd = IntArray(0); selSlice = 0; sliceBank = 0; live = false; message = "All slices deleted: pad 1 starts live slicing" }
                }
                else -> when (name) {
                    "D7" -> preview(edited(), 0)
                }
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
        l.set("D4")
        when (tab) {
            Tab.RECORD -> {
                l.set("D5", source == SRC_PHONE)
                l.set("D6", startMode != MODE_MANUAL)
                l.set("D8", state != IDLE)
            }
            Tab.EDIT -> {
                l.set("PAGE LEFT", editPage == 1)
                if (editPage == 0) l.set("D5", ui.zeroSnap)
                l.set("PAGE RIGHT", editPage == 0)
                if (editPage == 1) l.set("D5", "D6")
                l.set("D7", "D8")
            }
            Tab.SLICE -> {
                l.set("PAGE LEFT", slicePage > 0)
                l.set("PAGE RIGHT", slicePage < 2)
                if (applying) { l.set("D5", !applyGroup); l.set("D6", applyGroup); l.set("D7"); l.set("D8", true) }
                else {
                    if (slicePage == 1) l.set("D5", "D6") else if (slicePage == 0) l.set("D5", ui.zeroSnap)
                    l.set("D7", "D8")
                }
            }
        }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (ui.padMode != Ui.PadMode.PAD) return  // KEYBOARD / CHORDS / 16 VEL: pads only play notes
        when (tab) {
            Tab.RECORD -> ui.selectedSound = slot
            // the pad being edited just plays (with its play range); another pad opens its sample
            Tab.EDIT -> if (ui.snd(slot) != editSound) { ui.selectedSound = slot; loadFromPad(slot) }
            Tab.SLICE -> {
                if (applying) { ui.selectedSound = slot; applyGroup = false; return }
                // pad 1 = bottom-left = slot 0 plays slice 1 (of the bank)
                val i = sliceBank * 16 + slot
                if (sliceMode == M_MANUAL && slices.isEmpty() && slot == 0) {
                    // live slicing: the sample plays; the next pads add slice points
                    setSlices(intArrayOf(0)); selSlice = 0; live = true
                    preview(edited(), 0)
                    return
                }
                if (sliceMode == M_MANUAL && live && i == slices.size) { addLiveSlice(); return }
                if (i < slices.size) { selSlice = i; sliceData(i)?.let { preview(it, slices[i]) } }
            }
        }
    }

    override fun onKnob(knob: Int, delta: Int) {
        val step = wave.knobStep(shift)
        when (tab) {
            Tab.RECORD -> when (knob) {
                0 -> threshold = (threshold + delta / 1000f).coerceIn(0.002f, 0.9f)
                1 -> if (startMode == MODE_SYNC) {
                    if (stepped(delta)) syncIdx = (syncIdx + if (delta > 0) 1 else -1).coerceIn(0, SYNC_BARS.size - 1)
                } else maxSeconds = (maxSeconds + if (delta > 0) 1 else -1).coerceIn(1, 30)
            }
            Tab.EDIT -> if (editPage == 1) {
                when (knob) {
                    0 -> { selA = (selA + delta * step).coerceIn(0, max(0, selB - 1)); snapSel(true); focus = selA; wave.follow(selA) }
                    1 -> { selB = (selB + delta * step).coerceIn(min(takeFrames, selA + 1), takeFrames); snapSel(false); focus = selB; wave.follow(selB) }
                    2 -> if (stepped(delta)) fnIdx = (fnIdx + if (delta > 0) 1 else -1).coerceIn(0, FNS - 1)
                    3 -> stretchPct = (stretchPct + if (shift) delta.coerceIn(-1, 1) else delta / 2).coerceIn(25, 400)
                }
            } else {
                when (knob) {
                    0 -> {
                        rawStart = (rawStart + delta * step).coerceIn(0, max(0, rawEnd - 48))
                        snapTrim(); focus = trimStart; wave.follow(trimStart); pushPlayRange()
                    }
                    1 -> {
                        rawEnd = (rawEnd + delta * step).coerceIn(min(takeFrames, rawStart + 48), takeFrames)
                        snapTrim(); focus = trimEnd; wave.follow(trimEnd); pushPlayRange()
                    }
                    4 -> if (stepped(delta)) {
                        // TRIGGER of the focused pad: play the whole sample, or only while held
                        val sp = ui.engine.params
                        sp[ui.selectedIndex, org.openmk3.core.audio.SoundParams.GATE] = if (gate) 0f else 1f
                        sp.save(ui.ctx)
                    }
                }
                if (knob <= 1) computeSlices()
            }
            Tab.SLICE -> if (applying) Unit else when (slicePage) {
                0 -> when (knob) {
                    0 -> if (stepped(delta)) {
                        val m = (sliceMode + if (delta > 0) 1 else -1).coerceIn(0, MODES.size - 1)
                        if (m != sliceMode) {
                            sliceMode = m; live = false
                            if (m == M_MANUAL) { slices = IntArray(0); sliceEnd = IntArray(0); message = "MANUAL: pad 1 plays the sample, the flashing pad adds a slice" }
                            computeSlices()
                        }
                    }
                    1 -> when (sliceMode) {
                        M_SPLIT -> if (stepped(delta)) { splitIdx = (splitIdx + if (delta > 0) 1 else -1).coerceIn(0, SPLITS.size - 1); computeSlices() }
                        M_GRID -> if (stepped(delta)) { gridIdx = (gridIdx + if (delta > 0) 1 else -1).coerceIn(0, GRIDS.size - 1); computeSlices() }
                        M_DETECT -> { sensitivity = (sensitivity + delta / 500f).coerceIn(0f, 1f); computeSlices() }
                        else -> if (stepped(delta)) autoSnap = !autoSnap
                    }
                    2 -> if (stepped(delta)) { bpmAuto = !bpmAuto; computeSlices() }
                    3 -> {
                        if (bpmAuto) { if (stepped(delta)) bpmAdjust = (bpmAdjust + if (delta > 0) 1 else -1).coerceIn(0, 2) }
                        else bpmManual = (bpmManual + delta * (if (shift) 0.01 else 0.1)).coerceIn(40.0, 300.0)
                        if (sliceMode == M_GRID) computeSlices()
                    }
                }
                1 -> {
                    val len = trimEnd - trimStart
                    when (knob) {
                        0 -> if (stepped(delta) && slices.isNotEmpty()) { selSlice = (selSlice + if (delta > 0) 1 else -1).coerceIn(0, slices.size - 1); sliceBank = selSlice / 16 }
                        2 -> if (selSlice < slices.size) {  // START: a joined previous slice's end moves with it
                            toManual()
                            val i = selSlice
                            val old = slices[i]
                            val lo = if (i > 0) slices[i - 1] + 48 else 0
                            val nv = (old + delta * step).coerceIn(lo, sliceEnd[i] - 48)
                            if (i > 0 && sliceEnd[i - 1] == old) sliceEnd[i - 1] = nv
                            slices[i] = nv
                            focus = editedToTake(nv); wave.follow(focus)
                        }
                        3 -> if (selSlice < slices.size) {  // END: on its own
                            toManual()
                            val i = selSlice
                            sliceEnd[i] = (sliceEnd[i] + delta * step).coerceIn(slices[i] + 48, len)
                            focus = editedToTake(sliceEnd[i]); wave.follow(focus)
                        }
                    }
                }
                else -> when (knob) {
                    0 -> if (stepped(delta)) mono = !mono
                    1 -> if (stepped(delta)) patternReplace = !patternReplace
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

    /** Zero-crossing snap for the selection markers. */
    private fun snapSel(start: Boolean) {
        val d = take ?: return
        if (!ui.zeroSnap) return
        if (start) selA = WaveView.zeroCross(d, 2, selA).coerceIn(0, max(0, selB - 1))
        else if (selB < takeFrames) selB = WaveView.zeroCross(d, 2, selB).coerceIn(min(takeFrames, selA + 1), takeFrames)
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
        val c = org.openmk3.core.mk3.Mk3Colors.BLUE
        if (applying) return if (applyGroup || slot == ui.selectedSound) org.openmk3.core.mk3.Mk3Colors.value(c, 3) else 0
        val i = sliceBank * 16 + slot
        val flash = (now / 250) % 2 == 0L
        // MANUAL: the pad that adds the next slice flashes (pad 1 before live slicing starts)
        if (sliceMode == M_MANUAL && ((slices.isEmpty() && slot == 0) || (live && i == slices.size)))
            return if (flash) org.openmk3.core.mk3.Mk3Colors.value(org.openmk3.core.mk3.Mk3Colors.WHITE, 3) else 0
        if (i >= slices.size) return 0
        val playing = ui.padPressure[slot] > 0
        return org.openmk3.core.mk3.Mk3Colors.value(c, if (playing || i == selSlice) 3 else 1)
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
        l.drawText("ZONE", 420f, 26f, tabText)
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

    private fun syncText() = SYNC_BARS[syncIdx].let { if (it == 0) "FREE" else if (it == 1) "1 BAR" else "$it BARS" }

    private fun drawRecord(l: Canvas, r: Canvas) {
        dButton(r, 4, "SOURCE", if (source == SRC_PHONE) "PHONE" else "INPUT")
        dButton(r, 5, "START", when (startMode) { MODE_THRESHOLD -> "THRESHOLD"; MODE_SYNC -> "SYNC"; else -> "MANUAL" })
        dButton(r, 7, "RECORD", if (state == IDLE) "START" else "STOP", state != IDLE)
        l.drawText(if (source == SRC_PHONE) "Phone playback" else "Controller inputs", 16f, 70f, value)
        val st = when (state) {
            ARMED -> "WAITING FOR SIGNAL"
            SYNCED -> if (ui.engine.playing) "WAITING FOR NEXT BAR" else "PRESS PLAY TO START"
            RECORDING -> "● REC ${"%.1f".format(n.nativeRecFrames().toDouble() / AudioEngine.SR)} s"
            else -> "READY"
        }
        l.drawText(st, 16f, 106f, if (state == IDLE) big else recText)
        l.drawText("INPUT", 16f, 134f, label)
        meter(l, 140f, meterIn, startMode == MODE_THRESHOLD && source == SRC_INPUT)
        l.drawText(if (phoneActive) "PHONE" else "PHONE (not allowed yet: confirm on the phone)", 16f, 172f, label)
        meter(l, 178f, meterPhone, startMode == MODE_THRESHOLD && source == SRC_PHONE)
        knob(l, 0, "THRESHOLD", "${(threshold * 100).toInt()}%")
        if (startMode == MODE_SYNC) knob(l, 1, "LENGTH", syncText())
        else knob(l, 1, "MAX LENGTH", "$maxSeconds s")
        drawWave(r, -1, -1)
        knob(r, 7, "TARGET", ui.padLabel())
    }

    private fun drawEdit(l: Canvas, r: Canvas) {
        if (editPage == 1) { drawFunctions(l, r); return }
        dButton(r, 4, "SNAP", if (ui.zeroSnap) "ZERO-X ON" else "ZERO-X OFF", ui.zeroSnap)
        dButton(r, 6, "PREVIEW", "PLAY")
        dButton(r, 7, "SAVE TO", ui.padLabel())
        l.drawText("EDIT · PLAY RANGE", 16f, 72f, title)
        l.drawText("1/2 ▶", 470f, 72f, pageText)
        l.drawText(ellipsize(takeName, 40), 16f, 98f, value)
        if (take != null) {
            l.drawText("Plays ${secs(trimEnd - trimStart)} of ${secs(takeFrames)}", 16f, 124f, value)
            l.drawText("The pad plays this range at once (the file is unchanged)", 16f, 150f, label)
            l.drawText("D8: save the range as a new WAV · ▶: functions", 16f, 170f, label)
            l.drawText("Pads open a pad's sample · Encoder: zoom, tilt: scroll", 16f, 190f, label)
        }
        knob(l, 0, "START", secs(trimStart))
        knob(l, 1, "END", secs(trimEnd))
        knob(r, 4, "TRIGGER", if (gate) "GATE" else "ONE-SHOT")
        drawWave(r, trimStart, trimEnd)
    }

    private fun drawFunctions(l: Canvas, r: Canvas) {
        val f = fn
        dButton(r, 4, "◀ FUNCTION", "PREV")
        dButton(r, 5, "FUNCTION ▶", "NEXT")
        dButton(r, 6, "PREVIEW", "SELECTION")
        dButton(r, 7, if (shift) "UNDO" else "APPLY", if (shift) "${undo.size} LEFT" else f.label, !shift)
        l.drawText("EDIT · FUNCTIONS", 16f, 72f, title)
        l.drawText("◀ 2/2", 470f, 72f, pageText)
        l.drawText(f.label, 16f, 104f, big)
        l.drawText(f.help, 16f, 128f, label)
        if (take != null) {
            l.drawText("Selection ${secs(selB - selA)} of ${secs(takeFrames)}", 16f, 154f, value)
            val clip = org.openmk3.core.audio.SampleOps.clipboard
            l.drawText((if (clip != null) "Copied ${secs(clip.size / 2)} · " else "") +
                (if (editSound >= 0) "applies to ${ui.padLabel()} (new WAV)" else "applies to the recording"), 16f, 178f, label)
            if (busy) l.drawText("working…", 16f, 200f, warn)
        }
        knob(l, 0, "SEL START", secs(selA))
        knob(l, 1, "SEL END", secs(selB))
        knob(l, 2, "FUNCTION", f.label)
        knob(l, 3, "STRETCH", if (f == org.openmk3.core.audio.SampleOps.Fn.STRETCH) "$stretchPct%" else "—")
        drawWave(r, selA, selB)
    }

    private fun drawSlice(l: Canvas, r: Canvas) {
        if (slicePage == 0 && !applying) dButton(r, 4, "SNAP", if (ui.zeroSnap) "ZERO-X ON" else "ZERO-X OFF", ui.zeroSnap)
        val g = ui.groupLetter
        val n = slices.size
        val bankTxt = if (n > 16) " · bank ${sliceBank + 1}/${(n - 1) / 16 + 1} (SHIFT+D7/D8)" else ""
        if (applying) {
            dButton(r, 4, "TO", "SOUND", !applyGroup)
            dButton(r, 5, "TO", "GROUP", applyGroup)
            dButton(r, 6, "", "CANCEL")
            dButton(r, 7, "APPLY", "OK", true)
            l.drawText("APPLY ${min(16, n)} SLICES TO…", 16f, 72f, title)
            if (applyGroup) {
                l.drawText("Group $g: pads $g" + "1–$g${min(16, n)}", 16f, 106f, big)
                l.drawText("Each slice replaces a pad's sample" + if (mono) " · MONO" else "", 16f, 136f, label)
                l.drawText("Group buttons choose the group", 16f, 156f, label)
            } else {
                l.drawText("Sound ${ui.padLabel()}", 16f, 106f, big)
                l.drawText("Slices on its keys, pad 1 = slice 1 (KEYBOARD mode)", 16f, 136f, label)
                l.drawText("Pads choose the sound", 16f, 156f, label)
            }
            if (n > 16) l.drawText("Only the first 16 slices are applied", 16f, 180f, warn)
            if (patternReplace) l.drawText("The pattern is replaced with the loop's notes", 16f, 200f, label)
            drawWave(r, -1, -1, sliceMarks = true)
            return
        }
        dButton(r, 7, "APPLY", "…")
        when (slicePage) {
            0 -> {
                dButton(r, 6, "PREVIEW", "PLAY")
                l.drawText("SLICE · SLICER", 16f, 72f, title)
                l.drawText("1/3 ▶", 470f, 72f, pageText)
                l.drawText("$n slices" + bankTxt, 16f, 104f, value)
                l.drawText(ellipsize(takeName, 40), 16f, 128f, label)
                if (sliceMode == M_MANUAL) l.drawText(if (live) "Live: press the flashing pad to add a slice" else if (n == 0) "Press pad 1 to play the sample and slice it" else "Pads play the slices · ▶ edit them", 16f, 156f, label)
                else l.drawText("Pads play the slices · ▶ edit · D8 apply", 16f, 156f, label)
                l.drawText("Tempo %.1f BPM".format(sliceBpm()), 16f, 180f, label)
                knob(l, 0, "MODE", MODES[sliceMode])
                when (sliceMode) {
                    M_SPLIT -> knob(l, 1, "SLICES", "${SPLITS[splitIdx]}")
                    M_GRID -> knob(l, 1, "SLICES", "1/${GRIDS[gridIdx]}")
                    M_DETECT -> knob(l, 1, "SENSITIVITY", "${(sensitivity * 100).roundToInt()}%")
                    else -> knob(l, 1, "AUTO-SNAP", if (autoSnap) "ON" else "OFF")
                }
                knob(l, 2, "BPM", if (bpmAuto) "AUTO" else "MANUAL")
                knob(l, 3, "ADJUST", if (bpmAuto) listOf("DETECTED", "HALF", "DOUBLE")[bpmAdjust] else "%.1f".format(bpmManual))
            }
            1 -> {
                dButton(r, 4, "SPLIT", if (live) "HERE" else "IN HALF")
                dButton(r, 5, "REMOVE", "JOIN")
                dButton(r, 6, "DELETE", "ALL")
                l.drawText("SLICE · EDIT", 16f, 72f, title)
                l.drawText("◀ 2/3 ▶", 470f, 72f, pageText)
                if (selSlice < n) {
                    l.drawText("Slice ${selSlice + 1} of $n" + bankTxt, 16f, 104f, value)
                    l.drawText("${secs(slices[selSlice])} – ${secs(sliceEnd[selSlice])} (${secs(sliceEnd[selSlice] - slices[selSlice])})", 16f, 128f, label)
                } else l.drawText("No slices", 16f, 104f, value)
                l.drawText("START moves a joined slice end with it", 16f, 156f, label)
                knob(l, 0, "SLICE", if (selSlice < n) "${selSlice + 1}" else "—")
                knob(l, 2, "START", if (selSlice < n) secs(slices[selSlice]) else "—")
                knob(l, 3, "END", if (selSlice < n) secs(sliceEnd[selSlice]) else "—")
            }
            else -> {
                dButton(r, 6, "PREVIEW", "PLAY")
                l.drawText("SLICE · APPLY", 16f, 72f, title)
                l.drawText("◀ 3/3", 470f, 72f, pageText)
                l.drawText("MONO (to a group): one voice per pad,", 16f, 104f, label)
                l.drawText("all pads choke each other", 16f, 124f, label)
                l.drawText("PATTERN: notes that play the loop back", 16f, 150f, label)
                knob(l, 0, "MONO", if (mono) "ON" else "OFF")
                knob(l, 1, "PATTERN", if (patternReplace) "REPLACE" else "NONE")
            }
        }
        if (slicePage == 1 && selSlice < n) drawWave(r, editedToTake(slices[selSlice]), editedToTake(sliceEnd[selSlice]), sliceMarks = true)
        else drawWave(r, trimStart, trimEnd, sliceMarks = true)
    }

    private fun drawWave(r: Canvas, a: Int, b: Int, sliceMarks: Boolean = false) {
        val showTrim = a >= 0
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
        else if (pp >= 0f && selPreview >= 0 && tab == Tab.EDIT && editPage == 1) head = selPreview + pp.toInt()
        else if (tab == Tab.EDIT) {
            val ps = ui.engine.playPos(ui.selectedIndex)
            if (ps >= 0f && ui.engine.kit[ui.selectedIndex].frames == takeFrames) head = ps.toInt()
        }
        val marks = if (showTrim) intArrayOf(a, b) else IntArray(0)
        val sl = if (sliceMarks) IntArray(slices.size) { editedToTake(slices[it]) } else IntArray(0)
        wave.setColour(org.openmk3.core.mk3.Mk3Colors.rgb(ui.soundColour(ui.selectedIndex)))
        wave.draw(r, 16f, top, bot,
            selA = if (showTrim) a else -1, selB = b,
            markers = marks, slices = sl, playhead = head)
    }

    private fun ellipsize(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}

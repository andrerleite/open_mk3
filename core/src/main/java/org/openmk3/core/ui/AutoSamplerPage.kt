package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.openmk3.core.AppLog
import org.openmk3.core.audio.AudioEngine
import org.openmk3.core.audio.KitStore
import org.openmk3.core.audio.NativeEngine
import org.openmk3.core.audio.Keys
import org.openmk3.core.audio.Sample
import org.openmk3.core.audio.SoundParams
import org.openmk3.core.audio.WavWriter
import org.openmk3.core.audio.Zones
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * AUTO SAMPLER (SAMPLING, RECORD tab, D7): plays the focused sound note by note (and at up to
 * four velocities), records Open MK3's own output for each and maps the takes as zones onto a
 * target pad, turning a synth patch (or a heavy effect chain) into a light sampler instrument.
 *  K1 low note · K2 high note · K3 step (semitones) · K4 velocity layers
 *  K5 hold time · K6 release tail · K8 target pad (pads also pick the target)
 *  D5 audition the low note · D8 start / stop · D7 or SAMPLING: back
 * Transport must be stopped: everything heard is recorded. At most 16 takes (the zone limit).
 */
class AutoSamplerPage(ui: Ui, private val back: Page) : Page(ui) {

    override val customPads = true
    override val handlesTransport = true

    private val source = ui.selectedIndex
    private val group = source / org.openmk3.core.audio.Pattern.PADS
    private var low = 36
    private var high = 84
    private var step = 12
    private var layers = 1
    private var holdMs = 1500
    private var tailMs = 1000
    private var target = group * 16 + (source % 16 + 1) % 16

    private val acc = IntArray(8)
    private var message = ""
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "autosampler-io") }

    // ---- run state ----
    private var running = false
    private var takeIdx = 0
    private var phase = 0          // 0 idle, 1 note held, 2 tail
    private var phaseAt = 0L
    private val takes = mutableListOf<Pair<Int, FloatArray>>()   // take index -> data

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val big = Theme.text(22f, Theme.FG, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val ok = Theme.text(15f, Theme.OK)
    private val warn = Theme.text(15f, Theme.WARN)
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val kName = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val kValue = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val cellText = Theme.text(13f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val bar = Theme.fill(Theme.ACCENT)
    private val empty = Theme.fill(android.graphics.Color.rgb(32, 32, 40))

    private val n = NativeEngine
    private var auditionUntil = 0L

    private fun notes(): List<Int> = (low..high step step).toList()
    private fun velocities(): List<Float> = (1..layers).map { it.toFloat() / layers }
    private fun count() = notes().size * layers

    fun back() { if (running) stop("Stopped"); ui.show(back) }

    override fun onExit() { if (running) stop("Stopped"); worker.shutdown() }

    override fun lights(l: Lights) {
        l.set("D5"); l.set("D7"); l.set("D8", running)
        l.set("SAMPLING", true)
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (!running) target = group * 16 + slot
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (!down) return
        when (name) {
            "D5" -> if (!running && !ui.engine.playing) audition()
            "D8", "ENCODER PUSH" -> if (running) stop("Stopped") else start()
            "D7", "SAMPLING" -> back()
            "STOP" -> if (running) stop("Stopped") else ui.engine.stopPressed()
        }
        ui.invalidate()
    }

    private fun audition() {
        if (auditionUntil != 0L) ui.engine.noteOff(source, low)
        ui.engine.noteOn(source, low, 0.8f)
        auditionUntil = android.os.SystemClock.uptimeMillis() + holdMs
    }

    override fun onKnob(knob: Int, delta: Int) {
        if (running || knob !in 0..7) return
        acc[knob] += delta
        val unit = if (knob == 4 || knob == 5) 8 else 25
        if (abs(acc[knob]) < unit) return
        val d = if (acc[knob] > 0) 1 else -1
        acc[knob] = 0
        when (knob) {
            0 -> low = (low + d).coerceIn(0, high)
            1 -> high = (high + d).coerceIn(low, 127)
            2 -> step = STEPS[(STEPS.indexOf(step) + d).coerceIn(0, STEPS.size - 1)]
            3 -> layers = (layers + d).coerceIn(1, 4)
            4 -> holdMs = (holdMs + d * 100).coerceIn(100, 10000)
            5 -> tailMs = (tailMs + d * 100).coerceIn(100, 10000)
            7 -> target = group * 16 + Math.floorMod(target % 16 + d, 16)
        }
        message = if (count() > Zones.MAX) "Too many takes (${count()}): at most ${Zones.MAX}, raise the step or narrow the range" else ""
        ui.invalidate()
    }

    override fun onEncoder(delta: Int) = onKnob(1, delta * 25)

    private fun start() {
        if (ui.engine.playing) { message = "Stop the transport first: everything heard is recorded"; return }
        if (count() > Zones.MAX) { message = "Too many takes (${count()}): at most ${Zones.MAX}"; return }
        takes.clear()
        takeIdx = 0
        running = true
        message = ""
        AppLog.i("Auto sampler: ${ui.soundName(source)} ${Keys.noteName(low)}–${Keys.noteName(high)} every $step, $layers layer(s) → ${ui.padLabel(target % 16)}")
        beginTake(android.os.SystemClock.uptimeMillis())
    }

    private fun noteOf(i: Int) = notes()[i / layers]
    private fun velOf(i: Int) = velocities()[i % layers]

    private fun beginTake(now: Long) {
        ui.engine.killAll(source)   // a clean start: no tail from the previous take
        n.nativeRecArm(2 /* master */, false, 0f, ((holdMs + tailMs) / 1000 + 2))
        ui.engine.noteOn(source, noteOf(takeIdx), velOf(takeIdx))
        phase = 1; phaseAt = now
    }

    private fun stop(why: String) {
        if (phase == 1) ui.engine.noteOff(source, noteOf(takeIdx))
        n.nativeRecStop(); n.nativeRecTake()
        running = false; phase = 0
        message = why
    }

    override fun tick(now: Long): Boolean {
        if (auditionUntil != 0L && now >= auditionUntil) { ui.engine.noteOff(source, low); auditionUntil = 0L }
        if (!running) return false
        when (phase) {
            1 -> if (now - phaseAt >= holdMs) { ui.engine.noteOff(source, noteOf(takeIdx)); phase = 2; phaseAt = now }
            2 -> if (now - phaseAt >= tailMs) {
                n.nativeRecStop()
                val data = trim(n.nativeRecTake())
                if (data != null) takes.add(takeIdx to data)
                takeIdx++
                if (takeIdx >= count()) { running = false; phase = 0; finish() }
                else beginTake(now)
            }
        }
        return true
    }

    /** Drops the silence before the note and after its tail (with a short fade out). */
    private fun trim(d: FloatArray): FloatArray? {
        val frames = d.size / 2
        if (frames < 256) return null
        val thr = 0.001f   // -60 dB
        var a = 0
        while (a < frames && abs(d[a * 2]) < thr && abs(d[a * 2 + 1]) < thr) a++
        if (a >= frames) return null
        var b = frames - 1
        while (b > a && abs(d[b * 2]) < thr && abs(d[b * 2 + 1]) < thr) b--
        a = (a - 32).coerceAtLeast(0)
        val out = d.copyOfRange(a * 2, (b + 1) * 2)
        val len = out.size / 2
        val fade = minOf(len / 4, AudioEngine.SR / 50)
        for (i in 0 until fade) { val g = i.toFloat() / fade; val k = len - 1 - i; out[k * 2] *= g; out[k * 2 + 1] *= g }
        return out
    }

    private fun finish() {
        if (takes.isEmpty()) { message = "Nothing recorded (is the sound silent?)"; return }
        val base = ui.soundName(source).ifEmpty { "Sound" }
        val ns = notes(); val vs = velocities()
        val got = takes.toList()
        val tgt = target
        message = "Saving ${got.size} takes…"
        worker.execute {
            try {
                var dir = File(File(BrowserPage.ROOT, "Recordings"), "$base auto")
                var k = 2
                while (dir.exists()) dir = File(File(BrowserPage.ROOT, "Recordings"), "$base auto ${k++}")
                val samples = got.map { (i, data) ->
                    val note = ns[i / layers]; val layer = i % layers
                    val f = File(dir, "%s %s%s.wav".format(base, Keys.noteName(note), if (layers > 1) " v${layer + 1}" else ""))
                    WavWriter.write(f, data, 2)
                    Triple(i, Sample(f.nameWithoutExtension, data, 0, 2, f.absolutePath, "auto sampled"), note)
                }
                ui.post { install(tgt, samples, ns, vs.size); ui.invalidate() }
            } catch (ex: Exception) {
                ui.post { message = "Can't save: ${ex.message}"; ui.invalidate() }
            }
        }
    }

    private fun install(tgt: Int, samples: List<Triple<Int, Sample, Int>>, ns: List<Int>, nl: Int) {
        val e = ui.engine
        val sp = e.params
        val colour = sp[tgt, SoundParams.COLOUR]
        sp.reset(tgt)
        sp[tgt, SoundParams.COLOUR] = colour
        sp.setSource(tgt, SoundParams.SRC_SAMPLER)
        sp[tgt, SoundParams.ENV] = 2f       // ADSR: the key's release fades the take
        sp[tgt, SoundParams.ATT] = 0.001f
        sp[tgt, SoundParams.SUS] = 1f
        sp[tgt, SoundParams.REL] = (tailMs / 1000f).coerceIn(0.05f, 3f)
        sp[tgt, SoundParams.POLY] = 8f
        e.zones.clear(tgt)
        samples.firstOrNull()?.let { e.setSample(tgt, it.second); sp.resetRange(tgt) }
        for ((i, smp, note) in samples) {
            val ni = i / nl; val layer = i % nl
            val lo = if (ni == 0) 0 else ns[ni - 1] + (step + 1) / 2
            val hi = if (ni == ns.size - 1) 127 else ns[ni] + (step + 1) / 2 - 1
            val vlo = if (layer == 0) 1 else 127 * layer / nl + 1
            val vhi = if (layer == nl - 1) 127 else 127 * (layer + 1) / nl
            val z = e.zones.add(tgt, smp, note)
            if (z >= 0) e.zones.update(tgt, z) { it.lo = lo; it.hi = hi; it.vlo = vlo; it.vhi = vhi }
        }
        org.openmk3.core.audio.Names.sound[tgt] = "${ui.soundName(source)} (sampled)".take(39)
        org.openmk3.core.audio.Names.save(ui.ctx)
        sp.save(ui.ctx)
        e.zones.save(ui.ctx)
        KitStore.save(ui.ctx, e.kit)
        ui.checkpoint()
        message = "${samples.size} takes → ${ui.padLabel(tgt % 16)} (zones)"
        AppLog.i("Auto sampler: $message")
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        for (i in 0 until 16) {
            val s = group * 16 + i
            ui.padLed(i, when {
                s == target -> org.openmk3.core.mk3.Mk3Colors.value(groupColour(group), 3)
                s == source -> org.openmk3.core.mk3.Mk3Colors.value(org.openmk3.core.mk3.Mk3Colors.WHITE, 1)
                else -> 0
            })
        }
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        r.drawText("AUDITION", 60f, 24f, dLabel)
        r.drawText("BACK", 300f, 24f, dLabel)
        r.drawText(if (running) "STOP" else "START", 420f, 24f, dLabel)
        l.drawText("AUTO SAMPLER", 16f, 52f, title)
        l.drawText("${ui.padLabel(source % 16)} ${ui.soundName(source)}  →  ${ui.padLabel(target % 16)}", 16f, 84f, big)
        l.drawText("${notes().size} notes × $layers = ${count()} takes · about ${(count() * (holdMs + tailMs) + 999) / 1000} s", 16f, 110f, if (count() > Zones.MAX) warn else label)
        if (running) {
            l.drawText("Recording ${Keys.noteName(noteOf(takeIdx))}" + (if (layers > 1) " v${takeIdx % layers + 1}" else "") + "  (${takeIdx + 1}/${count()})", 16f, 140f, ok)
            l.drawRect(RectF(16f, 150f, 464f, 158f), empty)
            l.drawRect(RectF(16f, 150f, 16f + 448f * takeIdx / count(), 158f), bar)
        }
        if (message.isNotEmpty()) l.drawText(message.take(60), 16f, 176f, if (message.startsWith("Can't") || message.startsWith("Too") || message.startsWith("Stop")) warn else ok)
        l.drawText("Transport stopped: everything heard is recorded", 16f, 198f, label)
        l.drawText("Pads or K8: target pad · D8 / push: start", 16f, 216f, label)
        val ks = listOf("LOW" to Keys.noteName(low), "HIGH" to Keys.noteName(high), "STEP" to "$step st", "LAYERS" to "$layers",
            "HOLD" to "%.1f s".format(holdMs / 1000f), "TAIL" to "%.1f s".format(tailMs / 1000f), "" to "", "TARGET" to ui.padLabel(target % 16))
        for ((i, kv) in ks.withIndex()) {
            val c = if (i < 4) l else r
            val x = 60f + (i % 4) * 120f
            c.drawText(kv.first, x, 238f, kName)
            c.drawText(kv.second, x, 260f, kValue)
        }
        // note map on the right screen
        val ns = notes()
        r.drawText("${Keys.noteName(low)} … ${Keys.noteName(high)}", 240f, 60f, cellText)
        for ((i, note) in ns.withIndex()) {
            val x = 16f + 448f * (note / 127f)
            val done = running && i * layers < takeIdx
            r.drawRect(RectF(x - 3f, 80f, x + 3f, 180f), if (done) bar else Theme.fill(android.graphics.Color.rgb(90, 90, 120)))
        }
    }

    private companion object {
        val STEPS = listOf(1, 2, 3, 4, 6, 12)
    }
}

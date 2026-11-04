package org.openmk3.core.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.openmk3.core.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** JNI bindings to engine.cpp (AAudio, real-time callback). */
object NativeEngine {
    init {
        System.loadLibrary("openmk3")
    }

    external fun nativeStart(deviceId: Int, inputDeviceId: Int): Boolean
    external fun nativeStop()
    external fun nativeTune(): Boolean
    external fun nativeLoadSample(slot: Int, data: FloatArray, channels: Int, choke: Int)
    external fun nativeSetPatternBuffer(buf: ByteBuffer)
    external fun nativeSetTrigBuffer(buf: ByteBuffer)
    external fun nativeTrigger(slot: Int, vel: Float)
    external fun nativeSetMasterGain(g: Float)
    external fun nativeTransport(cmd: Int)
    external fun nativeCurrentStep(): Int
    external fun nativePhase(): Double
    external fun nativeMeter(): Float
    external fun nativeXruns(): Int
    external fun nativeBufferFrames(): Int
    external fun nativeBurstFrames(): Int
    external fun nativeCallbackFrames(): Int
    external fun nativeExclusive(): Boolean
    external fun nativeTakeWorstTrigMs(): Float
    external fun nativeInputOpen(): Boolean
    external fun nativeTakeInputPeak(): Float
    external fun nativeUsbStart(fd: Int, outEp: Int, outMax: Int, inEp: Int, inMax: Int,
                                outCh: Int, inCh: Int, subslot: Int, urbs: Int, packets: Int,
                                hidEp: Int, hidMax: Int): Boolean
    external fun nativeHidRead(buf: ByteArray, timeoutMs: Int): Int
    external fun nativeUsbStop()
    /** Held (sounding, not released) sounds and notes of [watch], for the pad lights. */
    external fun nativeHeld(watch: Int, out: IntArray)
    /** Sounds cut before their end since [from]: [count, (slot, reason, by, fromSeq)…]. */
    external fun nativeTakeCuts(from: Int): IntArray
    /** Output on the phone (the direct USB driver then sends the MK3 silence). */
    external fun nativeOutPhone(on: Boolean)
    /** Phone playback captured for sampling is also heard on the output, at [gain]. */
    external fun nativeMonitor(on: Boolean, gain: Float)
    external fun nativeUsbRunning(): Boolean
    external fun nativeUsbError(): Int
    external fun nativeUsbLate(): Int
    external fun nativeUsbGuessed(): Int
    external fun nativeUsbClockPpm(): Double
    external fun nativeUsbStats(): String
    external fun nativeSetParamBuffer(buf: ByteBuffer)
    external fun nativeRelease(slot: Int)
    external fun nativeKill(slot: Int)
    external fun nativeNoteOn(slot: Int, note: Int, vel: Float, chord: Int)
    external fun nativeNoteOff(slot: Int, note: Int)
    external fun nativeRepeatHold(slot: Int, note: Int, vel: Float, chord: Int, type: Int)
    external fun nativeRepeatConfig(mode: Int, rate: Float, gate: Float, arpType: Int, octaves: Int)
    external fun nativeRepeatShape(dynamics: Int, rhythm: Int)
    external fun nativeSetRecording(on: Boolean)
    external fun nativeTakeRecorded(): Boolean
    external fun nativeInitSynths()
    external fun nativeFmLoadBank(index: Int, data: ByteArray): Boolean
    external fun nativeFmPatchName(bank: Int, program: Int): String
    external fun nativePolySet(slot: Int, index: Int, value: Float)
    external fun nativeCpuLoad(): FloatArray
    external fun nativeMeters(out: FloatArray)
    external fun nativeHintPoll(): Int
    external fun nativeSetZone(slot: Int, zone: Int, data: FloatArray?, channels: Int, root: Int, lo: Int, hi: Int, vlo: Int, vhi: Int)
    external fun nativeZoneMap(slot: Int, zone: Int, root: Int, lo: Int, hi: Int, vlo: Int, vhi: Int)
    /** start, end, tune, gain dB, pan, loop (-1 as the sound / 0 / 1), loop start, end, crossfade, attack, decay (-1 = as the sound) */
    external fun nativeZoneParams(slot: Int, zone: Int, v: FloatArray)
    external fun nativePolyParamCount(): Int
    external fun nativePolyParamName(i: Int): String
    external fun nativePolyParamGroup(i: Int): String
    external fun nativePolyParamDefault(i: Int): Float
    external fun nativePolyParamSteps(i: Int): Int
    external fun nativePolyDisplay(i: Int, v: Float): String
    external fun nativeSetFxBuffer(buf: ByteBuffer)
    external fun nativeSetFx(chain: Int, slot: Int, type: Int)
    external fun nativeFxMeter(chain: Int, slot: Int): Float
    external fun nativeSetScopeTap(kind: Int, index: Int)
    external fun nativeScope(out: FloatArray)
    external fun nativeActiveSlots(word: Int): Int
    external fun nativePlayPos(slot: Int): Float
    external fun nativeRecArm(source: Int, threshold: Boolean, thr: Float, seconds: Int)
    /** Records exactly [frames] frames, starting with the next bar the sequencer plays. */
    external fun nativeRecArmSync(source: Int, frames: Int)
    external fun nativeRecStop()
    external fun nativeRecState(): Int
    external fun nativeRecFrames(): Int
    external fun nativeRecTakePeak(source: Int): Float
    external fun nativeRecTake(): FloatArray
    external fun nativeRecFeedPhone(data: FloatArray, frames: Int)
}

/**
 * Kotlin face of the native engine. Same API the pages used with the old AudioTrack engine;
 * all real-time work (voices, mixing, sequencer) happens in C++ (engine.cpp).
 */
/** "A1".."H16": group letter and pad number of a sound index. */
fun soundLabel(sound: Int) = "${'A' + sound / Pattern.PADS}${sound % Pattern.PADS + 1}"

class AudioEngine(private val ctx: Context) {

    companion object {
        const val SR = 48000
        const val PREVIEW_SLOT = Pattern.SOUNDS
        /** An empty sound slot (new groups start empty). */
        fun empty() = Sample("", FloatArray(0), info = "empty")
    }

    private val n = NativeEngine

    /** Generated sounds, kept for "built-in" resets. Choke groups belong to the pad. */
    val builtIn: Array<Sample> = DrumKit.build(SR).let { drums ->
        Array(Pattern.SOUNDS) { if (it < drums.size) drums[it] else empty() }
    }
    /** What each sound plays now (built-in, a loaded WAV, or empty), 8 groups x 16. */
    val kit: Array<Sample> = builtIn.copyOf()

    /** Puts [s] on pad [slot] (safe while playing; the audio thread swaps it in). */
    fun setSample(slot: Int, s: Sample) {
        val withChoke = s.withChoke(builtIn[slot].chokeGroup)
        kit[slot] = withChoke
        n.nativeLoadSample(slot, withChoke.data, withChoke.channels, withChoke.chokeGroup)
    }

    fun resetSlot(slot: Int) = setSample(slot, builtIn[slot])

    /**
     * Plays [s] once on the separate preview slot (no pad changes). The audio thread installs
     * queued samples before it handles triggers, so the trigger always plays the new sound.
     */
    fun preview(s: Sample, velocity: Float = 0.8f) {
        n.nativeLoadSample(PREVIEW_SLOT, s.data, s.channels, 0)
        n.nativeTrigger(PREVIEW_SLOT, velocity)
    }

    /** uptimeMillis of the last trigger per sound, written by the audio callback. */
    class TrigTimes(buf: ByteBuffer) {
        private val l = buf.asLongBuffer()
        operator fun get(i: Int): Long = l.get(i)
    }
    private val trigBuf: ByteBuffer = ByteBuffer.allocateDirect(Pattern.SOUNDS * 8).order(ByteOrder.nativeOrder())
    val lastTrigMs = TrigTimes(trigBuf)

    private val held = IntArray(8)
    /** Reads which sounds (and which notes of [watch]) are held now: once per screen frame. */
    fun pollHeld(watch: Int) = n.nativeHeld(watch, held)
    fun heldSound(s: Int) = s in 0 until 128 && (held[s shr 5] ushr (s and 31)) and 1 != 0
    fun heldNote(note: Int) = note in 0 until 128 && (held[4 + (note shr 5)] ushr (note and 31)) and 1 != 0

    var pattern: Pattern = Pattern()
        set(v) {
            field = v
            n.nativeSetPatternBuffer(v.buffer)
        }

    /** Tune, volume, pan, range, envelope and filter of every sound (saved separately). */
    val params: SoundParams = SoundParams.load(ctx)
    /** Effect slots of sounds, groups and the master (saved separately). */
    val fx: FxParams = FxParams.load(ctx)
    /** Names of the user FM cartridges (USER 1..16), loaded from Download/<app folder>/DX7. */
    val fmUserBanks = mutableListOf<String>()
    /** POLY source patches (OB-Xf engine), per sound. */
    val poly: PolyParams by lazy { PolyParams(n) }
    /** Sample zones (multisamples) of every sound. */
    val zones = Zones(n)

    var masterGain: Float = 0.7f
        set(v) {
            field = v
            n.nativeSetMasterGain(v)
        }

    var bpm: Double
        get() = pattern.bpm
        set(v) { pattern.bpm = v }

    @Volatile var playing = false
        private set
    val currentStep: Int get() = n.nativeCurrentStep()
    val phase: Double get() = n.nativePhase()
    val meter: Float get() = n.nativeMeter()
    val bufferFrames: Int get() = n.nativeBufferFrames()
    /** VU meters: peak levels since the last call, sounds 0..127 then groups 0..7. */
    fun meters(out: FloatArray) = n.nativeMeters(out)

    private var hintState = -1
    /** Asks Android for a performance hint session for the audio thread (logged once per change). */
    fun pollHint() {
        val st = n.nativeHintPoll()
        if (st != hintState) {
            hintState = st
            if (st > 0) AppLog.i("Performance hint: " + listOf("", "not supported on this phone", "refused for the audio thread", "on for the audio thread")[st.coerceIn(0, 3)])
        }
    }

    /** Audio CPU load since the last read: average and peak, in % of real time. */
    fun cpuLoad(): String = pollHint().let { n.nativeCpuLoad() }.let { "cpu avg=${(it[0] * 100).toInt()}% peak=${(it[1] * 100).toInt()}%" + (if (it[2] > 0f) " shed=${it[2].toInt()}" else "") +
        " hint=" + listOf("none", "unsupported", "refused", "on").getOrElse(it[3].toInt()) { "?" } +
        " slowest(us) seq=${it[4].toInt()} voices=${it[5].toInt()} poly=${it[6].toInt()} fx=${it[7].toInt()}" }
    val underruns: Int get() = if (direct) n.nativeUsbLate() else n.nativeXruns()
    @Volatile var outputName = "none"
        private set

    private var worstTrig = 0f
    /** Worst pad-trigger -> render delay since last reset (assigning resets it). */
    var worstTriggerToRenderMs: Float
        get() { worstTrig = maxOf(worstTrig, n.nativeTakeWorstTrigMs()); return worstTrig }
        set(v) { n.nativeTakeWorstTrigMs(); worstTrig = v }

    private var monitor: ScheduledExecutorService? = null
    /** True when audio is streamed by our own USB driver instead of AAudio. */
    @Volatile var direct = false
        private set
    @Volatile private var running = false

    init {
        n.nativeSetTrigBuffer(trigBuf)
        n.nativeSetPatternBuffer(pattern.buffer)
        n.nativeSetParamBuffer(params.buffer)
        n.nativeSetFxBuffer(fx.buffer)
        n.nativeInitSynths()
        poly.load(ctx)
        Thread({ PolyPresets.list(ctx); PolyPresets.categories(ctx) }, "poly-presets").start()  // parsed off the UI thread
        params.fmPatchName = { b, p -> n.nativeFmPatchName(b, p) }
        loadFmBanks()
        // re-create the saved effects (each allocates its buffers here, not on the audio thread)
        for (c in 0 until FxParams.CHAINS) for (k in 0 until FxParams.SLOTS) {
            val t = fx.type(c, k)
            if (t != FxParams.NONE) n.nativeSetFx(c, k, t)
        }
        // the return chains start with a reverb (A) and a delay (B), fully wet
        val prefs = ctx.getSharedPreferences("openmk3", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("returnsInit", false)) {
            for ((a, t) in listOf(FxParams.REVERB, FxParams.DELAY).withIndex()) {
                val c = FxParams.RETURN0 + a
                if (fx.type(c, 0) != FxParams.NONE) continue
                setFx(c, 0, t)
                FxParams.DEFS[t]?.firstOrNull { it.name == "MIX" }?.let { fx[c, 0, it.index] = 1f }
            }
            fx.save(ctx)
            prefs.edit().putBoolean("returnsInit", true).apply()
        }
        n.nativeSetMasterGain(masterGain)
        n.nativeTransport(0) // the native engine outlives sessions: start stopped
        // Samples are loaded before the stream starts, so the callback never sees a half-copied one.
        kit.forEachIndexed { i, s -> n.nativeLoadSample(i, s.data, s.channels, s.chokeGroup) }
    }

    fun start() {
        if (running) return
        val am = ctx.getSystemService(AudioManager::class.java)!!
        val usbOut = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        outputName = usbOut?.productName?.toString()?.let { "$it (USB)" } ?: "phone (no USB output found)"
        // Run the MK3's input too: its clock drives the output (USB implicit feedback).
        val micOk = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val usbIn = if (!micOk) null else am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        mk3OutId = usbOut?.id ?: 0
        mk3InId = usbIn?.id ?: 0
        val ok = n.nativeStart(mk3OutId, mk3InId)
        running = ok
        AppLog.i(
            "Audio (native AAudio): ${if (ok) "started" else "FAILED"} out=$outputName " +
                "exclusive=${n.nativeExclusive()} burst=${n.nativeBurstFrames()}fr buffer=${bufferFrames}fr " +
                "(${"%.1f".format(bufferFrames * 1000.0 / SR)} ms) | input clock-sync: " +
                when {
                    !micOk -> "OFF (record-audio permission not granted)"
                    usbIn == null -> "OFF (no USB input found)"
                    n.nativeInputOpen() -> "on"
                    else -> "FAILED to open"
                }
        )
        var lastX = 0
        var reported = false
        monitor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "audio-monitor") }.apply {
            scheduleWithFixedDelay({
                try {
                    pollCuts()
                    pollHint()
                    if (n.nativeTune()) {
                        AppLog.i("Audio: stream error, reopened (buffer ${bufferFrames}fr)")
                        lastX = 0
                    }
                    if (!reported && n.nativeCallbackFrames() > 0) {
                        reported = true
                        AppLog.i("Audio: callback size ${n.nativeCallbackFrames()} frames")
                    }
                    val x = underruns
                    if (x != lastX) {
                        lastX = x
                        AppLog.i("Audio: glitches=$x buffer ${bufferFrames}fr (${"%.1f".format(bufferFrames * 1000.0 / SR)} ms)")
                    }
                } catch (e: Exception) {
                    AppLog.i("audio monitor: $e")
                }
            }, 250, 250, TimeUnit.MILLISECONDS)
        }
        restoreOutput()
    }

    /**
     * Audio is already streaming through the direct USB driver (started by Mk3Device):
     * the native engine renders in that thread; here we only monitor it.
     */
    fun startDirect() {
        if (running) return
        direct = true
        running = true
        outputName = "Maschine MK3 (direct USB)"
        AppLog.i("Audio (direct USB): buffer=${bufferFrames}fr (${"%.1f".format(bufferFrames * 1000.0 / SR)} ms)")
        var lastLate = 0
        var lastPpmLog = 0L
        var lastGapLog = 0L
        monitor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "audio-monitor") }.apply {
            scheduleWithFixedDelay({
                try {
                    // Summarised at most once a second (this check runs every 250 ms, so a
                    // per-gap line would carry a misleadingly precise timestamp).
                    pollCuts()
                    val late = n.nativeUsbLate()
                    val nowMs = System.currentTimeMillis()
                    if (late != lastLate && nowMs - lastGapLog >= 1000) {
                        AppLog.i("Audio: direct USB gaps +${late - lastLate} (total $late): the MK3 heard clicks")
                        lastLate = late
                        lastGapLog = nowMs
                    }
                    // output on the phone: keep its AAudio stream healthy (reopens after a
                    // device change such as Bluetooth headphones connecting)
                    if (outputPhone && n.nativeTune()) AppLog.i("Audio: phone output reopened (buffer ${bufferFrames}fr)")
                    if (!n.nativeUsbRunning()) {
                        AppLog.i("Audio: direct USB stream stopped (errno ${n.nativeUsbError()})")
                        monitor?.shutdown()
                    }
                    val now = System.currentTimeMillis()
                    if (lastPpmLog == 0L) lastPpmLog = now
                    if (now - lastPpmLog > 30_000) {
                        lastPpmLog = now
                        AppLog.i("Audio: direct USB ${n.nativeUsbStats()}")
                    }
                } catch (e: Exception) {
                    AppLog.i("audio monitor: $e")
                }
            }, 250, 250, TimeUnit.MILLISECONDS)
        }
        restoreOutput()
    }

    // ---- output: the MK3, or the phone (speaker, wired or Bluetooth headphones) ----

    /** True: everything plays on the phone's current output instead of the MK3. */
    @Volatile var outputPhone = false
        private set
    private var mk3OutId = 0
    private var mk3InId = 0

    /** The phone output to use without the MK3: Bluetooth, then wired headphones, then the speaker. */
    private fun phoneDevice(): AudioDeviceInfo? {
        val am = ctx.getSystemService(AudioManager::class.java)!!
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val order = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* BLE headset */, 27 /* BLE speaker */,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        for (t in order) outs.firstOrNull { it.type == t }?.let { return it }
        return null
    }

    /** Switches the output (saved; applied again at the next start). */
    fun setOutputPhone(phone: Boolean) {
        ctx.getSharedPreferences("openmk3", Context.MODE_PRIVATE).edit().putBoolean("outputPhone", phone).apply()
        if (phone == outputPhone) return
        applyOutput(phone)
    }

    private fun applyOutput(phone: Boolean) {
        outputPhone = phone
        if (direct) {
            if (phone) {
                n.nativeOutPhone(true)
                // the MK3's audio stays with our driver, so Android's default output is the
                // phone's own (and follows Bluetooth headphones connecting / disconnecting)
                val ok = n.nativeStart(0, 0)
                AppLog.i("Audio: output on the phone ${if (ok) "started" else "FAILED"} (buffer ${bufferFrames}fr, ${"%.1f".format(bufferFrames * 1000.0 / SR)} ms)")
                if (!ok) { n.nativeOutPhone(false); outputPhone = false }
            } else {
                n.nativeStop()
                n.nativeOutPhone(false)
                AppLog.i("Audio: output on the MK3")
            }
        } else {
            n.nativeStop()
            val dev = if (phone) phoneDevice() else null
            val ok = if (phone) n.nativeStart(dev?.id ?: 0, 0) else n.nativeStart(mk3OutId, mk3InId)
            outputName = if (phone) "phone (${dev?.productName ?: "default"})" else outputName
            AppLog.i("Audio: output on the ${if (phone) "phone: ${dev?.productName} type ${dev?.type}" else "MK3"} ${if (ok) "started" else "FAILED"}")
        }
        updateMonitor()
    }

    /** Re-applies the saved output (after the audio started). */
    private fun restoreOutput() {
        if (ctx.getSharedPreferences("openmk3", Context.MODE_PRIVATE).getBoolean("outputPhone", false)) applyOutput(true)
    }

    // ---- phone playback heard on the MK3 while it is captured for sampling ----

    @Volatile var phoneCaptureOn = false
        set(v) { field = v; updateMonitor() }
    /** Setting: phone audio plays through the MK3 while captured (the phone's speaker is muted). */
    var monitorPhone: Boolean
        get() = ctx.getSharedPreferences("openmk3", Context.MODE_PRIVATE).getBoolean("monitorPhone", true)
        set(v) {
            // turning it on again also retries muting the phone's speaker
            ctx.getSharedPreferences("openmk3", Context.MODE_PRIVATE).edit().putBoolean("monitorPhone", v).putBoolean("muteSilencesCapture", false).apply()
            updateMonitor()
        }
    /** True while the phone audio is routed to the MK3 (the capture then mutes the phone's speaker). */
    val monitoring get() = phoneCaptureOn && monitorPhone && !outputPhone

    private fun updateMonitor() = n.nativeMonitor(monitoring, 1f)

    // ---- why sounds end early (logged, at most every 2 s) ----
    private var cutsFrom = 0
    private var cutsLogged = 0L
    private val cutCounts = LinkedHashMap<String, Int>()

    private fun padName(s: Int) = if (s in 0 until Pattern.SOUNDS) "${'A' + s / 16}${s % 16 + 1}" else "preview"

    private fun pollCuts() {
        val a = n.nativeTakeCuts(cutsFrom)
        if (a.isEmpty()) return
        cutsFrom = a[0]
        var k = 1
        while (k + 3 < a.size) {
            val (slot, reason, by, seq) = listOf(a[k], a[k + 1], a[k + 2], a[k + 3])
            val src = if (seq == 1) "the sequencer" else "a pad"
            val why = when (reason) {
                1 -> "polyphony ${params[slot, SoundParams.POLY].toInt()}: a new note of ${padName(by)} from $src"
                2 -> "choke group ${params[by, SoundParams.CHOKE].toInt().takeIf { it > 0 } ?: kit[by].chokeGroup}: ${padName(by)} played by $src"
                else -> "all voices busy: its voice went to ${padName(by)}"
            }
            val key = "${padName(slot)} cut early by $why"
            cutCounts[key] = (cutCounts[key] ?: 0) + 1
            k += 4
        }
        val now = System.currentTimeMillis()
        if (cutCounts.isNotEmpty() && now - cutsLogged > 2000) {
            cutsLogged = now
            for ((msg, c) in cutCounts) AppLog.i("Cut: $msg" + if (c > 1) " (×$c)" else "")
            cutCounts.clear()
        }
    }

    fun stop() {
        monitor?.shutdownNow()
        monitor = null
        n.nativeStop()
        n.nativeOutPhone(false)
        n.nativeMonitor(false, 1f)
        running = false
    }

    val inputOpen: Boolean get() = n.nativeInputOpen()
    /** Peak level of the MK3 input since the last call. */
    fun takeInputPeak(): Float = n.nativeTakeInputPeak()

    /** Thread-safe (producers serialise on a tiny spinlock; the audio thread never waits). */
    fun trigger(slot: Int, velocity: Float) = n.nativeTrigger(slot, velocity)

    fun transportPlay() { if (!playing) n.nativeTransport(2); playing = true }
    fun transportStop() {
        n.nativeTransport(0)
        playing = false
        // Diagnostics for sounds heard after stopping: which pads are still sounding, and why.
        val now = sounding()
        if (now.isNotEmpty()) AppLog.i("Stop: still sounding (tails / release): $now")
        Thread {
            Thread.sleep(400)
            val later = sounding()
            if (later.isNotEmpty()) AppLog.i("Stop +400 ms: still sounding: $later")
        }.start()
    }

    /** Sounds with a voice playing right now, e.g. "A4 (Clap)". */
    private fun sounding(): String = (0..PREVIEW_SLOT).filter { isSounding(it) }
        .joinToString { if (it == PREVIEW_SLOT) "preview" else "${soundLabel(it)} (${kit[it].name})" }

    fun isSounding(slot: Int) = n.nativeActiveSlots(slot / 32) and (1 shl (slot % 32)) != 0

    /** Read position (frames) of the newest voice of [slot], or -1 when silent. */
    fun playPos(slot: Int): Float = n.nativePlayPos(slot)

    /** Plays [note] (60 = as sampled) on [slot]; [chord] = notes started together. */
    fun noteOn(slot: Int, note: Int, vel: Float, chord: Int = 1) = n.nativeNoteOn(slot, note, vel, chord)
    fun noteOff(slot: Int, note: Int) = n.nativeNoteOff(slot, note)

    /** NOTE REPEAT / ARP: a held note (type 3 down, 4 up, 5 pressure). */
    fun repeatHold(slot: Int, note: Int, vel: Float, chord: Int, type: Int) = n.nativeRepeatHold(slot, note, vel, chord, type)
    /** [mode] 0 off, 1 repeat, 2 arp; [rate] in 16th steps; [gate] fraction of the rate. */
    fun repeatShape(dynamics: Int, rhythm: Int) = n.nativeRepeatShape(dynamics, rhythm)
    fun repeatConfig(mode: Int, rate: Float, gate: Float, arpType: Int, octaves: Int) =
        n.nativeRepeatConfig(mode, rate, gate, arpType, octaves)
    /** While on (and playing), repeat / arp hits are recorded into the pattern by the engine. */
    fun setRecording(on: Boolean) = n.nativeSetRecording(on)
    /** True once after the engine wrote repeat / arp hits into the pattern. */
    fun takeRecorded() = n.nativeTakeRecorded()


    /** Loads up to 16 DX7 cartridges (.syx, 32 voices each) as the USER FM banks. */
    fun loadFmBanks() {
        try {
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                org.openmk3.core.Edition.storageFolder + "/DX7")
            dir.mkdirs()
            val files = dir.listFiles { f -> f.isFile && f.extension.equals("syx", true) }.orEmpty().sortedBy { it.name.lowercase() }.take(16)
            fmUserBanks.clear()
            for ((i, f) in files.withIndex()) {
                if (n.nativeFmLoadBank(i, f.readBytes())) fmUserBanks.add(f.nameWithoutExtension)
                else AppLog.i("FM: ${f.name} is not a 32-voice DX7 cartridge")
            }
            if (fmUserBanks.isNotEmpty()) AppLog.i("FM: ${fmUserBanks.size} cartridge(s) loaded")
        } catch (e: Exception) {
            AppLog.i("FM: cartridges not loaded: $e")
        }
    }

    /** Puts effect [type] in a chain slot, with its default settings. */
    fun setFx(chain: Int, slot: Int, type: Int) {
        fx.setType(chain, slot, type)
        n.nativeSetFx(chain, slot, type)
        fx.save(ctx)
    }

    /** Gain reduction (compressor) of a slot, for the screen. */
    fun fxMeter(chain: Int, slot: Int) = n.nativeFxMeter(chain, slot)

    /** Fades out everything that is sounding (or one sound). */
    fun killAll(slot: Int = -1) {
        n.nativeKill(slot)
        if (slot < 0) AppLog.i("All sounds off")
    }

    /** STOP: stops the transport; pressed again (already stopped) it silences every sound. */
    fun stopPressed() {
        if (playing) transportStop() else killAll()
    }

    /** Oscilloscope source: 0 master, 1 group [index], 2 sound [index]. */
    fun setScopeTap(kind: Int, index: Int) = n.nativeSetScopeTap(kind, index)
    fun scope(out: FloatArray) = n.nativeScope(out)

    /** Note-off for a pad (ends ADSR sustain). */
    fun release(slot: Int) = n.nativeRelease(slot)
    fun transportRestart() { pattern.songRestart(); n.nativeTransport(2); playing = true }

    /** Steps of latency between the playhead and what the player hears. */
    fun outputLatencySteps(): Double = bufferFrames / (SR * 60.0 / bpm / 4.0)
}

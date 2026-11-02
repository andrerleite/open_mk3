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
    external fun nativeUsbRunning(): Boolean
    external fun nativeUsbError(): Int
    external fun nativeUsbLate(): Int
    external fun nativeUsbGuessed(): Int
    external fun nativeUsbClockPpm(): Double
    external fun nativeUsbStats(): String
    external fun nativeSetParamBuffer(buf: ByteBuffer)
    external fun nativeRelease(slot: Int)
    external fun nativeActiveSlots(): Int
    external fun nativeRecArm(source: Int, threshold: Boolean, thr: Float, seconds: Int)
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
class AudioEngine(private val ctx: Context) {

    companion object {
        const val SR = 48000
        const val PREVIEW_SLOT = 16
    }

    private val n = NativeEngine

    /** Generated sounds, kept for "built-in" resets. Choke groups belong to the pad. */
    val builtIn: Array<Sample> = DrumKit.build(SR)
    /** What each pad plays now (built-in or a loaded WAV). */
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
    private val trigBuf: ByteBuffer = ByteBuffer.allocateDirect(16 * 8).order(ByteOrder.nativeOrder())
    val lastTrigMs = TrigTimes(trigBuf)

    var pattern: Pattern = Pattern()
        set(v) {
            field = v
            n.nativeSetPatternBuffer(v.buffer)
        }

    /** Tune, volume, pan, range, envelope and filter of every sound (saved separately). */
    val params: SoundParams = SoundParams.load(ctx)

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
        val ok = n.nativeStart(usbOut?.id ?: 0, usbIn?.id ?: 0)
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
                    val late = n.nativeUsbLate()
                    val nowMs = System.currentTimeMillis()
                    if (late != lastLate && nowMs - lastGapLog >= 1000) {
                        AppLog.i("Audio: direct USB gaps +${late - lastLate} (total $late): the MK3 heard clicks")
                        lastLate = late
                        lastGapLog = nowMs
                    }
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
    }

    fun stop() {
        monitor?.shutdownNow()
        monitor = null
        n.nativeStop()
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
        val now = n.nativeActiveSlots()
        if (now != 0) AppLog.i("Stop: still sounding (tails / release): ${slotsOf(now)}")
        Thread {
            Thread.sleep(400)
            val later = n.nativeActiveSlots()
            if (later != 0) AppLog.i("Stop +400 ms: still sounding: ${slotsOf(later)}")
        }.start()
    }

    private fun slotsOf(mask: Int) = (0 until 17).filter { mask and (1 shl it) != 0 }
        .joinToString { if (it == PREVIEW_SLOT) "preview" else "pad ${it + 1} (${kit[it].name})" }

    /** Note-off for a pad (ends ADSR sustain). */
    fun release(slot: Int) = n.nativeRelease(slot)
    fun transportRestart() { n.nativeTransport(2); playing = true }

    /** Steps of latency between the playhead and what the player hears. */
    fun outputLatencySteps(): Double = bufferFrames / (SR * 60.0 / bpm / 4.0)
}

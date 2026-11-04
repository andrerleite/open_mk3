package org.openmk3.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import org.openmk3.core.AppLog

/**
 * Captures what other apps play on the phone (Android playback capture) and feeds it to the
 * native recorder. Needs a MediaProjection the user granted for this session. Apps that opt
 * out of capture, and protected content, are delivered as silence by Android.
 */
class PhoneCapture(
    private val ctx: android.content.Context,
    private val projection: MediaProjection,
    /** True while the captured audio is heard on the MK3: the phone's own speaker is muted then. */
    private val monitoring: () -> Boolean = { false },
    /** Called (from the capture thread) if capturing ends on its own. */
    private val onEnded: (String) -> Unit = {},
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the app before this runs
    fun start(): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid()) // never our own output
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(AudioEngine.SR)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val r = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(AudioEngine.SR * 2 * 4 / 5) // 200 ms
                .build()
        } catch (e: Exception) {
            AppLog.i("Phone capture: AudioRecord failed: $e")
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            AppLog.i("Phone capture: not initialised")
            r.release()
            return false
        }
        record = r
        running = true
        r.startRecording()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = FloatArray(480 * 2)
            while (running) {
                val n = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    NativeEngine.nativeRecFeedPhone(buf, n / 2)
                    var pk = 0f
                    for (i in 0 until n) { val a = kotlin.math.abs(buf[i]); if (a > pk) pk = a }
                    muteControl(pk)
                } else if (n < 0) {
                    if (running) onEnded("read error $n")
                    break
                }
            }
            setMuted(false)
        }, "phone-capture").apply { start() }
        AppLog.i("Phone capture: started")
        return true
    }

    // ---- the phone's speaker while its audio plays through the MK3 ----

    private val am = ctx.getSystemService(android.media.AudioManager::class.java)
    private val prefs = ctx.getSharedPreferences("openmk3", android.content.Context.MODE_PRIVATE)
    private var muted = false
    private var lastSignal = 0L
    private var checkUntil = 0L
    private var heardWhileMuted = false

    private fun setMuted(m: Boolean) {
        if (m == muted) return
        muted = m
        try {
            am?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                if (m) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Exception) { AppLog.i("Phone capture: can't ${if (m) "mute" else "unmute"} the phone: $e") }
    }

    /**
     * Mutes the phone's own output while its audio is heard on the MK3. Some phones also silence
     * the capture when muted: if the audio stops dead the moment we mute, we unmute and don't
     * mute again (the phone's volume then has to be lowered by hand).
     */
    private fun muteControl(peak: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        if (peak > 0.003f) lastSignal = now
        val want = monitoring() && !prefs.getBoolean("muteSilencesCapture", false)
        if (!want) { setMuted(false); checkUntil = 0L; return }
        if (!muted && now - lastSignal < 200) {
            setMuted(true)
            checkUntil = now + 500
            heardWhileMuted = false
            return
        }
        if (muted && checkUntil != 0L) {
            if (peak > 1e-5f) heardWhileMuted = true
            if (now >= checkUntil) {
                checkUntil = 0L
                if (heardWhileMuted) AppLog.i("Phone capture: phone speaker muted, its audio plays on the MK3")
                else {
                    setMuted(false)
                    prefs.edit().putBoolean("muteSilencesCapture", true).apply()
                    AppLog.i("Phone capture: muting this phone also silences the capture: not muting (lower the phone volume by hand)")
                }
            }
        }
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        thread?.join(300)
        record?.release()
        record = null
        thread = null
        setMuted(false)
    }
}

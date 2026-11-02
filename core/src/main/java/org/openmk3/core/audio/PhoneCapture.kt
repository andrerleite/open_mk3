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
    private val projection: MediaProjection,
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
                if (n > 0) NativeEngine.nativeRecFeedPhone(buf, n / 2)
                else if (n < 0) {
                    if (running) onEnded("read error $n")
                    break
                }
            }
        }, "phone-capture").apply { start() }
        AppLog.i("Phone capture: started")
        return true
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        thread?.join(300)
        record?.release()
        record = null
        thread = null
    }
}

package org.openmk3.core.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes 48 kHz 24-bit PCM WAV files (mono or interleaved stereo float input). */
object WavWriter {
    fun write(file: File, data: FloatArray, channels: Int, rate: Int = AudioEngine.SR) {
        file.parentFile?.mkdirs()
        val frames = data.size / channels
        val dataBytes = frames * channels * 3
        val b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x46464952).putInt(36 + dataBytes).putInt(0x45564157)          // RIFF....WAVE
        b.putInt(0x20746D66).putInt(16).putShort(1).putShort(channels.toShort()) // fmt , PCM
            .putInt(rate).putInt(rate * channels * 3).putShort((channels * 3).toShort()).putShort(24)
        b.putInt(0x61746164).putInt(dataBytes)                                     // data
        for (x in data) {
            val v = (x.coerceIn(-1f, 1f) * 8388607f).toInt()
            b.put(v.toByte()).put((v shr 8).toByte()).put((v shr 16).toByte())
        }
        FileOutputStream(file).use { it.write(b.array()) }
    }
}

package org.openmk3.core.audio

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads WAV files into 48 kHz float samples: PCM 8/16/24/32-bit, IEEE float 32/64,
 * WAVE_FORMAT_EXTENSIBLE, mono or stereo (extra channels are dropped). Other sample rates
 * are resampled with 4-point Hermite interpolation.
 */
object WavDecoder {
    private const val MAX_BYTES = 64L * 1024 * 1024

    fun decode(file: File, maxSeconds: Int = 30): Sample {
        if (file.length() > MAX_BYTES) throw IOException("file too large (${file.length() / 1_048_576} MB)")
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < 12 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) throw IOException("not a WAV file")
        var fmt = -1
        var channels = 0
        var rate = 0
        var bits = 0
        var dataOff = -1
        var dataLen = 0
        var p = 12
        while (p + 8 <= b.limit()) {
            val id = b.getInt(p)
            val len = b.getInt(p + 4).toLong() and 0xFFFFFFFFL
            val body = p + 8
            when (id) {
                0x20746D66 -> { // "fmt "
                    fmt = b.getShort(body).toInt() and 0xFFFF
                    channels = b.getShort(body + 2).toInt() and 0xFFFF
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt() and 0xFFFF
                    if (fmt == 0xFFFE && len >= 26) fmt = b.getShort(body + 24).toInt() and 0xFFFF // sub-format
                }
                0x61746164 -> { // "data"
                    dataOff = body
                    dataLen = minOf(len, (b.limit() - body).toLong()).toInt()
                }
            }
            if (dataOff >= 0 && fmt >= 0) break
            p = body + len.toInt() + (len.toInt() and 1) // chunks are word-aligned
        }
        if (fmt < 0 || dataOff < 0) throw IOException("missing fmt/data chunk")
        if (fmt != 1 && fmt != 3) throw IOException("unsupported format $fmt (compressed?)")
        if (channels < 1 || rate < 4000 || bits !in intArrayOf(8, 16, 24, 32, 64)) throw IOException("unsupported: ${bits}-bit, $channels ch, $rate Hz")
        val bps = bits / 8
        val frameBytes = bps * channels
        var frames = dataLen / frameBytes
        frames = minOf(frames, rate * maxSeconds)
        val outCh = if (channels >= 2) 2 else 1

        val src = FloatArray(frames * outCh)
        for (f in 0 until frames) {
            val base = dataOff + f * frameBytes
            for (c in 0 until outCh) src[f * outCh + c] = read(b, base + c * bps, bits, fmt)
        }
        val data = if (rate == AudioEngine.SR) src else resample(src, outCh, rate, AudioEngine.SR)
        val name = shortName(file)
        val info = "${"%.1f".format(rate / 1000.0)} kHz · $bits-bit${if (fmt == 3) " float" else ""} · " +
            "${if (channels == 1) "mono" else "stereo"} · ${"%.2f".format(frames.toDouble() / rate)} s"
        return Sample(name, data, 0, outCh, file.absolutePath, info)
    }

    /**
     * Display name: the file name minus leading words that only repeat its folders' names
     * ("Acme Drums Vol 1 Kick 01" in ".../Acme Drums Vol 1/" -> "Kick 01").
     */
    fun shortName(file: File): String {
        val words = file.nameWithoutExtension.replace('_', ' ').replace('-', ' ').split(' ').filter { it.isNotBlank() }
        val folderWords = generateSequence(file.parentFile) { it.parentFile }.take(4)
            .flatMap { it.name.replace('_', ' ').replace('-', ' ').lowercase().split(' ') }.toSet()
        var i = 0
        while (i < words.size - 1 && words[i].lowercase() in folderWords) i++
        return words.drop(i).joinToString(" ").ifBlank { file.nameWithoutExtension }
    }

    private fun read(b: ByteBuffer, i: Int, bits: Int, fmt: Int): Float = when {
        fmt == 3 && bits == 32 -> b.getFloat(i)
        fmt == 3 && bits == 64 -> b.getDouble(i).toFloat()
        bits == 8 -> ((b.get(i).toInt() and 0xFF) - 128) / 128f
        bits == 16 -> b.getShort(i) / 32768f
        bits == 24 -> {
            val v = (b.get(i).toInt() and 0xFF) or ((b.get(i + 1).toInt() and 0xFF) shl 8) or (b.get(i + 2).toInt() shl 16)
            v / 8388608f
        }
        bits == 32 -> b.getInt(i) / 2147483648f
        else -> 0f
    }

    /** 4-point, 3rd-order Hermite resampling of interleaved [src]. */
    private fun resample(src: FloatArray, ch: Int, from: Int, to: Int): FloatArray {
        val inFrames = src.size / ch
        val outFrames = ((inFrames.toLong() * to) / from).toInt()
        val out = FloatArray(outFrames * ch)
        val step = from.toDouble() / to
        fun s(f: Int, c: Int) = src[f.coerceIn(0, inFrames - 1) * ch + c]
        for (o in 0 until outFrames) {
            val pos = o * step
            val i = pos.toInt()
            val t = (pos - i).toFloat()
            for (c in 0 until ch) {
                val y0 = s(i - 1, c); val y1 = s(i, c); val y2 = s(i + 1, c); val y3 = s(i + 2, c)
                val c0 = y1
                val c1 = 0.5f * (y2 - y0)
                val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
                val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
                out[o * ch + c] = ((c3 * t + c2) * t + c1) * t + c0
            }
        }
        return out
    }
}

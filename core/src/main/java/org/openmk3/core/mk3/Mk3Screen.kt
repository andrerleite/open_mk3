package org.openmk3.core.mk3

import android.graphics.Bitmap
import android.graphics.Canvas
import java.nio.ShortBuffer

/**
 * One 480x272 MK3 screen. Pages draw into [canvas] with the normal Android 2D API;
 * [flush] diffs the result against what the hardware already shows and transmits only the
 * changed rectangle (big-endian RGB565, see docs/hardware-protocol.md).
 */
class Mk3Screen(val index: Int) {
    companion object {
        const val W = 480
        const val H = 272
    }

    val bitmap: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565)
    val canvas = Canvas(bitmap)

    private val cur = ShortArray(W * H)
    private val sent = ShortArray(W * H)
    private var hardwareValid = false
    private val out = ByteArray(16 + 4 + W * H * 2 + 8)

    /** Forces the next flush to resend the whole screen (e.g. after reconnecting). */
    fun invalidateHardware() {
        hardwareValid = false
    }

    /** Returns the number of bytes sent (0 if nothing changed, -1 on error). */
    fun flush(dev: Mk3Device): Int {
        bitmap.copyPixelsToBuffer(ShortBuffer.wrap(cur))
        var x0: Int
        var y0: Int
        var x1: Int
        var y1: Int
        if (!hardwareValid) {
            x0 = 0; y0 = 0; x1 = W - 1; y1 = H - 1
        } else {
            y0 = -1; y1 = -1; x0 = W; x1 = -1
            for (y in 0 until H) {
                val row = y * W
                var first = -1
                var last = -1
                for (x in 0 until W) {
                    if (cur[row + x] != sent[row + x]) {
                        if (first < 0) first = x
                        last = x
                    }
                }
                if (first >= 0) {
                    if (y0 < 0) y0 = y
                    y1 = y
                    if (first < x0) x0 = first
                    if (last > x1) x1 = last
                }
            }
            if (y0 < 0) return 0
        }
        // Pixels are sent in pairs, so keep x and width even.
        x0 = x0 and 1.inv()
        if ((x1 - x0 + 1) % 2 != 0) x1++
        if (x1 >= W) { x1 = W - 1; x0 = (x0 - 1).coerceAtLeast(0) and 1.inv() }
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1

        var o = 0
        fun b(v: Int) { out[o++] = v.toByte() }
        // header
        b(0x84); b(0x00); b(index); b(0x60); b(0); b(0); b(0); b(0)
        b(x0 shr 8); b(x0); b(y0 shr 8); b(y0); b(w shr 8); b(w); b(h shr 8); b(h)
        // transmit w*h pixels (= w*h/2 pairs)
        val pairs = w * h / 2
        b(0x00); b(pairs shr 16); b(pairs shr 8); b(pairs)
        for (y in y0..y1) {
            val row = y * W
            for (x in x0..x1) {
                val v = cur[row + x].toInt()
                out[o++] = (v shr 8).toByte()
                out[o++] = v.toByte()
            }
        }
        b(0x03); b(0); b(0); b(0)   // blit
        b(0x40); b(0); b(0); b(0)   // end
        if (!dev.sendDisplay(out, o)) {
            hardwareValid = false
            return -1
        }
        System.arraycopy(cur, 0, sent, 0, cur.size)
        hardwareValid = true
        return o
    }
}

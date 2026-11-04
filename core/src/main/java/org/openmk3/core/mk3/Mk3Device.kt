package org.openmk3.core.mk3

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Process
import android.os.SystemClock
import org.openmk3.core.AppLog
import org.openmk3.core.HidDescriptor
import org.openmk3.core.hex
import org.openmk3.core.u16le
import org.openmk3.core.u8

/**
 * Low-level Maschine MK3 driver: turns HID input reports into [Mk3Listener] events,
 * keeps LED state buffers, and pushes screen rectangles. Layouts were confirmed with the
 * protocol investigation documented in docs/hardware-protocol.md.
 */
class Mk3Device(ctx: Context, val device: UsbDevice, private val listener: Mk3Listener) {

    companion object {
        const val NI_VID = 0x17CC
        const val MK3_PID = 0x1600

        const val BUTTON_BITS = 72          // report 01, bytes 1..9
        const val BUTTON_LED_COUNT = 62     // report 80 payload
        const val PAD_LED_COUNT = 41        // report 81 payload
        const val KNOB_RANGE = 1000         // knobs report 0..999 and wrap
    }

    private val usb = ctx.getSystemService(UsbManager::class.java)!!
    private var conn: UsbDeviceConnection? = null
    private var hidIface: UsbInterface? = null
    private var hidIn: UsbEndpoint? = null
    private var hidOut: UsbEndpoint? = null
    private var dispIface: UsbInterface? = null
    private var dispOut: UsbEndpoint? = null

    @Volatile private var running = false
    private var reader: Thread? = null
    private var ledThread: Thread? = null

    // ---- LED state (written by the UI thread, flushed by the LED thread) ----
    private val buttonLeds = ByteArray(BUTTON_LED_COUNT)
    private val padLeds = ByteArray(PAD_LED_COUNT)
    @Volatile private var buttonLedsDirty = true
    @Volatile private var padLedsDirty = true
    private val ledLock = Object()

    // ---- input state (reader thread only) ----
    private var prev01: ByteArray? = null
    @Volatile var reportCount = 0L
        private set
    @Volatile var displayBytes = 0L
        private set

    val isOpen get() = conn != null

    fun open(): Boolean {
        val c = usb.openDevice(device) ?: run {
            AppLog.i("MK3: openDevice failed (no permission?)")
            return false
        }
        conn = c
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            when (itf.interfaceClass) {
                UsbConstants.USB_CLASS_HID -> for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) hidIn = ep else hidOut = ep
                    hidIface = itf
                }
                UsbConstants.USB_CLASS_VENDOR_SPEC -> for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                        dispIface = itf; dispOut = ep
                    }
                }
            }
        }
        val hid = hidIface
        val disp = dispIface
        if (hid == null || hidIn == null || hidOut == null || disp == null) {
            AppLog.i("MK3: expected interfaces not found (hid=$hid disp=$disp)")
            close()
            return false
        }
        val okHid = c.claimInterface(hid, true)
        val okDisp = c.claimInterface(disp, true)
        AppLog.i("MK3: opened ${device.productName} fw=${device.version} claimHID=$okHid claimDisplay=$okDisp")
        if (!okHid || !okDisp) {
            close()
            return false
        }
        // Sanity-check the report sizes against the documented protocol.
        val buf = ByteArray(1024)
        val n = c.controlTransfer(0x81, 0x06, 0x2200, hid.id, buf, buf.size, 500)
        if (n > 0) {
            val d = HidDescriptor(buf.copyOf(n))
            val b80 = d.bytes(HidDescriptor.Kind.Output, 0x80)
            val b81 = d.bytes(HidDescriptor.Kind.Output, 0x81)
            if (b80 != BUTTON_LED_COUNT || b81 != PAD_LED_COUNT) {
                AppLog.i("MK3: WARNING unexpected LED report sizes 80=$b80 81=$b81")
            }
        }
        running = true
        buttonLedsDirty = true; padLedsDirty = true  // a (re)connected controller starts dark
        startReader(c)
        startLedThread()
        return true
    }

    fun close() {
        stopDirectAudio()
        running = false
        reader?.interrupt()
        ledThread?.interrupt()
        try {
            reader?.join(300)
            ledThread?.join(300)
        } catch (_: InterruptedException) {
        }
        reader = null
        ledThread = null
        conn?.let { c ->
            // Leave the hardware dark rather than frozen in the last state.
            try {
                sendReport(c, 0x80, ByteArray(BUTTON_LED_COUNT))
                sendReport(c, 0x81, ByteArray(PAD_LED_COUNT))
            } catch (_: Exception) {
            }
            hidIface?.let { c.releaseInterface(it) }
            dispIface?.let { c.releaseInterface(it) }
            c.close()
        }
        conn = null
    }

    // =====================================================================================
    // direct USB audio (bypasses Android's USB audio driver; see engine.cpp UsbAudio)
    // =====================================================================================

    private var audioIfaces: List<UsbInterface> = emptyList()
    private var audioAlt0: List<UsbInterface> = emptyList()
    @Volatile var directAudio = false
        private set

    /**
     * Takes the MK3's audio interfaces from Android, sets the clock to 48 kHz and starts
     * native isochronous streaming paced by the device's implicit feedback.
     * Returns a description on success, null on failure (interfaces handed back to Android).
     */
    fun startDirectAudio(urbs: Int = 8, packetsPerUrb: Int = 8): String? {
        val c = conn ?: return null
        val all = (0 until device.interfaceCount).map { device.getInterface(it) }
        val ac = all.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }
        fun streaming(dir: Int) = all.firstOrNull { itf ->
            itf.interfaceClass == UsbConstants.USB_CLASS_AUDIO && itf.interfaceSubclass == 2 && itf.alternateSetting == 1 &&
                (0 until itf.endpointCount).any {
                    val ep = itf.getEndpoint(it)
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.direction == dir
                }
        }
        val outAlt = streaming(UsbConstants.USB_DIR_OUT)
        val inAlt = streaming(UsbConstants.USB_DIR_IN)
        if (ac == null || outAlt == null || inAlt == null) {
            AppLog.i("Direct audio: audio interfaces not found")
            return null
        }
        fun isoEp(itf: UsbInterface) = (0 until itf.endpointCount).map { itf.getEndpoint(it) }
            .first { it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC }
        val outEp = isoEp(outAlt)
        val inEp = isoEp(inAlt)

        // Claim AC + both streaming interfaces (detaches Android's driver from them).
        val claims = listOf(ac, outAlt, inAlt)
        for (itf in claims) {
            if (!c.claimInterface(itf, true)) {
                AppLog.i("Direct audio: could not claim interface #${itf.id}")
                releaseAudio(c, claims)
                return null
            }
        }
        audioIfaces = claims
        audioAlt0 = all.filter { a -> a.alternateSetting == 0 && (a.id == outAlt.id || a.id == inAlt.id) }

        // Format from the class-specific descriptors (MK3: 4 out / 2 in, 4-byte subslots).
        val fmt = parseStreamingFormat(c.rawDescriptors ?: ByteArray(0))
        val outCh = fmt[outAlt.id]?.first ?: 4
        val inCh = fmt[inAlt.id]?.first ?: 2
        val subslot = fmt[outAlt.id]?.second ?: 4
        val clockId = parseClockId(c.rawDescriptors ?: ByteArray(0)) ?: 2

        // Sample rate: SET_CUR on the clock source's sampling-frequency control.
        val rate = byteArrayOf(0x80.toByte(), 0xBB.toByte(), 0, 0) // 48000 LE
        val setR = c.controlTransfer(0x21, 0x01, 0x0100, (clockId shl 8) or ac.id, rate, 4, 500)
        val cur = ByteArray(4)
        val getR = c.controlTransfer(0xA1, 0x01, 0x0100, (clockId shl 8) or ac.id, cur, 4, 500)
        val curRate = if (getR == 4) cur.u16le(0) or (cur.u16le(2) shl 16) else -1

        if (!c.setInterface(outAlt) || !c.setInterface(inAlt)) {
            AppLog.i("Direct audio: could not select streaming alt settings")
            releaseAudio(c, claims)
            return null
        }
        val ok = org.openmk3.core.audio.NativeEngine.nativeUsbStart(
            c.fileDescriptor, outEp.address, outEp.maxPacketSize, inEp.address, inEp.maxPacketSize,
            outCh, inCh, subslot, urbs, packetsPerUrb,
            hidIn?.address ?: 0, hidIn?.maxPacketSize ?: 0,
        )
        val info = "clock#$clockId set=$setR rate=$curRate out=0x${outEp.address.toString(16)}(${outCh}ch, max ${outEp.maxPacketSize}) " +
            "in=0x${inEp.address.toString(16)}(${inCh}ch, max ${inEp.maxPacketSize}) subslot=$subslot " +
            "queue=${urbs}x${packetsPerUrb} packets"
        if (!ok) {
            AppLog.i("Direct audio: streaming failed to start (errno ${org.openmk3.core.audio.NativeEngine.nativeUsbError()}) $info")
            releaseAudio(c, claims)
            return null
        }
        directAudio = true
        AppLog.i("Direct audio: streaming $info")
        return info
    }

    fun stopDirectAudio() {
        if (!directAudio && audioIfaces.isEmpty()) return
        org.openmk3.core.audio.NativeEngine.nativeUsbStop()
        conn?.let { c -> releaseAudio(c, audioIfaces) }
        directAudio = false
    }

    /** Back to zero-bandwidth alt settings, then release (Android re-attaches its driver). */
    private fun releaseAudio(c: UsbDeviceConnection, claims: List<UsbInterface>) {
        for (a in audioAlt0) try { c.setInterface(a) } catch (_: Exception) {}
        for (itf in claims) try { c.releaseInterface(itf) } catch (_: Exception) {}
        audioIfaces = emptyList()
        audioAlt0 = emptyList()
    }

    /** interface number -> (channels, subslot bytes) for UAC2 streaming alt settings. */
    private fun parseStreamingFormat(raw: ByteArray): Map<Int, Pair<Int, Int>> {
        val res = HashMap<Int, Pair<Int, Int>>()
        var i = 0
        var curIf = -1
        var curAlt = -1
        var ch = 0
        while (i + 1 < raw.size) {
            val len = raw.u8(i)
            if (len < 2 || i + len > raw.size) break
            when (raw.u8(i + 1)) {
                0x04 -> if (len >= 9) { curIf = raw.u8(i + 2); curAlt = raw.u8(i + 3) }
                0x24 -> if (curAlt == 1) when (raw.u8(i + 2)) {
                    0x01 -> if (len >= 11) ch = raw.u8(i + 10)                       // AS_GENERAL bNrChannels
                    0x02 -> if (len >= 6 && ch > 0) res[curIf] = ch to raw.u8(i + 4) // FORMAT_TYPE subslot
                }
            }
            i += len
        }
        return res
    }

    /** bClockID of the first UAC2 clock source. */
    private fun parseClockId(raw: ByteArray): Int? {
        var i = 0
        while (i + 1 < raw.size) {
            val len = raw.u8(i)
            if (len < 2 || i + len > raw.size) break
            if (raw.u8(i + 1) == 0x24 && len >= 8 && raw.u8(i + 2) == 0x0A) return raw.u8(i + 3)
            i += len
        }
        return null
    }

    // =====================================================================================
    // input
    // =====================================================================================

    private fun startReader(c: UsbDeviceConnection) {
        val ep = hidIn!!
        reader = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(ep.maxPacketSize)
            var fastFails = 0
            while (running) {
                val t = SystemClock.uptimeMillis()
                // While direct audio runs, the native USB thread reads the controls (a timed-out
                // bulkTransfer here gets cancelled in the kernel, which stalled the audio stream).
                var n = if (directAudio) org.openmk3.core.audio.NativeEngine.nativeHidRead(buf, 250) else -1
                if (n == 0) continue                 // native read timed out: normal when idle
                if (n < 0) n = c.bulkTransfer(ep, buf, buf.size, 250)
                if (n > 0) {
                    fastFails = 0
                    reportCount++
                    try {
                        parse(buf, n)
                    } catch (e: Exception) {
                        AppLog.i("MK3: parse error $e on ${buf.hex(n)}")
                    }
                } else if (SystemClock.uptimeMillis() - t < 5) {
                    if (++fastFails == 100) AppLog.i("MK3: input transfers failing (unplugged?)")
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "mk3-input").apply { start() }
    }

    private fun parse(r: ByteArray, n: Int) {
        when (r.u8(0)) {
            0x01 -> if (n >= 42) parseControls(r.copyOf(n))
            0x02 -> parsePads(r, n)
        }
    }

    /** Report 01: buttons, knob touch, encoder, knobs, touch strip. */
    private fun parseControls(cur: ByteArray) {
        val prev = prev01
        prev01 = cur
        if (prev == null) {
            // First report: publish absolute state without generating button events.
            listener.onInitialState(IntArray(8) { cur.u16le(12 + it * 2) }, cur.u8(11) and 0x0F)
            return
        }
        // Buttons: bytes 1..9
        for (byte in 1..9) {
            val x = prev.u8(byte) xor cur.u8(byte)
            if (x == 0) continue
            for (bit in 0..7) {
                if (x and (1 shl bit) != 0) {
                    listener.onButton((byte - 1) * 8 + bit, cur.u8(byte) and (1 shl bit) != 0)
                }
            }
        }
        // Knob touch: byte 10, bit 7 = knob 0 ... bit 0 = knob 7
        val tx = prev.u8(10) xor cur.u8(10)
        if (tx != 0) for (k in 0..7) {
            val mask = 1 shl (7 - k)
            if (tx and mask != 0) listener.onKnobTouch(k, cur.u8(10) and mask != 0)
        }
        // 4D encoder rotation: low nibble of byte 11, 0..15 wrapping
        val e0 = prev.u8(11) and 0x0F
        val e1 = cur.u8(11) and 0x0F
        if (e0 != e1) {
            val d = ((e1 - e0 + 8 + 16) % 16) - 8
            if (d != 0) listener.onEncoder(d)
        }
        // Knobs: bytes 12..27, 16-bit LE, 0..999 wrapping
        for (k in 0..7) {
            val a = prev.u16le(12 + k * 2)
            val b = cur.u16le(12 + k * 2)
            if (a != b) {
                val d = ((b - a + KNOB_RANGE / 2 + KNOB_RANGE) % KNOB_RANGE) - KNOB_RANGE / 2
                listener.onKnob(k, d, b)
            }
        }
        // Touch strip: bytes 30..31 look like the position (bytes 28..29 a counter)
        val s0 = prev.u16le(30)
        val s1 = cur.u16le(30)
        if (s0 != s1 || prev.u16le(28) != cur.u16le(28)) listener.onTouchStrip(s1, cur.u16le(28))
    }

    /**
     * Report 02: 3-byte entries [pad][state<<4 | p11..8][p7..0] until an all-zero entry.
     * States: 1 = hit, 4 = pressure update, 3 = release.
     */
    private fun parsePads(r: ByteArray, n: Int) {
        var i = 1
        while (i + 2 < n) {
            val pad = r.u8(i)
            val hi = r.u8(i + 1)
            val lo = r.u8(i + 2)
            if (pad == 0 && hi == 0 && lo == 0) break
            if (pad < 16) {
                val pressure = ((hi and 0x0F) shl 8) or lo
                when (hi shr 4) {
                    1 -> listener.onPadHit(pad, pressure)
                    3 -> listener.onPadRelease(pad)
                    4 -> if (pressure > 0) listener.onPadPressure(pad, pressure)
                }
            }
            i += 3
        }
    }

    // =====================================================================================
    // LEDs
    // =====================================================================================

    fun setButtonLed(index: Int, value: Int) {
        if (index !in 0 until BUTTON_LED_COUNT) return
        val v = value.coerceIn(0, 0x7F).toByte()
        if (buttonLeds[index] == v) return
        buttonLeds[index] = v
        buttonLedsDirty = true
        synchronized(ledLock) { ledLock.notify() }
    }

    fun setPadLed(index: Int, value: Int) {
        if (index !in 0 until PAD_LED_COUNT) return
        val v = value.coerceIn(0, 0x7F).toByte()
        if (padLeds[index] == v) return
        padLeds[index] = v
        padLedsDirty = true
        synchronized(ledLock) { ledLock.notify() }
    }

    fun clearLeds() {
        buttonLeds.fill(0); padLeds.fill(0)
        buttonLedsDirty = true; padLedsDirty = true
        synchronized(ledLock) { ledLock.notify() }
    }

    @Volatile var ledFailures = 0L
        private set

    /**
     * Coalesces LED changes: at most one report of each kind every 8 ms. A report the USB
     * write didn't deliver is sent again, and the full state is re-sent every second, so a
     * lost report never leaves a LED wrong for long.
     */
    private fun startLedThread() {
        ledThread = Thread({
            var lastFull = 0L
            while (running) {
                val c = conn ?: break
                val now = android.os.SystemClock.uptimeMillis()
                val full = now - lastFull >= 1000
                if (full) lastFull = now
                if (buttonLedsDirty || full) {
                    buttonLedsDirty = false
                    if (!sendReport(c, 0x80, buttonLeds.copyOf())) buttonLedsDirty = true
                }
                if (padLedsDirty || full) {
                    padLedsDirty = false
                    if (!sendReport(c, 0x81, padLeds.copyOf())) padLedsDirty = true
                }
                try {
                    synchronized(ledLock) {
                        if (!buttonLedsDirty && !padLedsDirty) ledLock.wait(250)
                    }
                    Thread.sleep(8)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "mk3-leds").apply { start() }
    }

    /** Returns false if the report wasn't delivered (it will be retried). */
    private fun sendReport(c: UsbDeviceConnection, id: Int, payload: ByteArray): Boolean {
        val pkt = ByteArray(payload.size + 1)
        pkt[0] = id.toByte()
        System.arraycopy(payload, 0, pkt, 1, payload.size)
        val r = c.bulkTransfer(hidOut, pkt, pkt.size, 100)
        if (r == pkt.size) return true
        ledFailures++
        if (ledFailures == 1L || ledFailures % 100 == 0L) AppLog.i("MK3: LED report 0x${id.toString(16)} not delivered (result $r, $ledFailures so far): retrying")
        return false
    }

    // =====================================================================================
    // screens
    // =====================================================================================

    /** Sends an already-encoded screen command buffer. Returns false on a USB error. */
    fun sendDisplay(data: ByteArray, length: Int): Boolean {
        val c = conn ?: return false
        val ep = dispOut ?: return false
        var off = 0
        while (off < length) {
            val n = minOf(65536, length - off)
            val r = c.bulkTransfer(ep, data, off, n, 1000)
            if (r != n) return false
            off += n
        }
        displayBytes += length
        return true
    }
}

interface Mk3Listener {
    fun onInitialState(knobs: IntArray, encoder: Int) {}
    fun onButton(bit: Int, down: Boolean) {}
    fun onKnobTouch(knob: Int, touched: Boolean) {}
    fun onKnob(knob: Int, delta: Int, value: Int) {}
    fun onEncoder(delta: Int) {}
    fun onTouchStrip(position: Int, counter: Int) {}
    fun onPadHit(pad: Int, pressure: Int) {}
    fun onPadPressure(pad: Int, pressure: Int) {}
    fun onPadRelease(pad: Int) {}
}

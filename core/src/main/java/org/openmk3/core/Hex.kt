package org.openmk3.core

private val HEX = "0123456789ABCDEF".toCharArray()

fun Int.hex2(): String = "${HEX[(this shr 4) and 0xF]}${HEX[this and 0xF]}"
fun Int.hex4(): String = (this shr 8 and 0xFF).hex2() + (this and 0xFF).hex2()

/** Unsigned value of the byte at [i]. */
fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

fun ByteArray.hex(len: Int = size, off: Int = 0, sep: String = " "): String {
    val sb = StringBuilder(len * 3)
    for (k in 0 until len) {
        if (k > 0) sb.append(sep)
        sb.append(u8(off + k).hex2())
    }
    return sb.toString()
}

/** Multi-line hex dump, 16 bytes per row with offsets. */
fun ByteArray.hexDump(len: Int = size, indent: String = "    "): String {
    val sb = StringBuilder()
    var o = 0
    while (o < len) {
        val n = minOf(16, len - o)
        sb.append(indent).append(o.hex4()).append(": ").append(hex(n, o)).append('\n')
        o += n
    }
    return sb.toString().trimEnd()
}

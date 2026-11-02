package org.openmk3.core

/**
 * Minimal HID report descriptor parser. It only needs to answer "which report IDs
 * exist and how many bytes is each one", plus produce a readable item dump for the log.
 */
class HidDescriptor(val raw: ByteArray) {

    enum class Kind { Input, Output, Feature }

    /** (kind, reportId) -> size in bits, excluding the report ID byte. */
    val reportBits = LinkedHashMap<Pair<Kind, Int>, Int>()
    var usesReportIds = false
        private set
    val itemDump = StringBuilder()

    init {
        parse()
    }

    fun bytes(kind: Kind, id: Int): Int? = reportBits[kind to id]?.let { (it + 7) / 8 }

    fun maxBytes(kind: Kind): Int =
        reportBits.filterKeys { it.first == kind }.values.maxOfOrNull { (it + 7) / 8 } ?: 0

    fun summary(): String {
        val sb = StringBuilder()
        sb.append("HID report descriptor: ${raw.size} bytes, report IDs used: $usesReportIds\n")
        for ((key, bits) in reportBits) {
            val (kind, id) = key
            sb.append("    $kind report id=0x${id.hex2()}: ${(bits + 7) / 8} bytes ($bits bits)")
            sb.append(" -> ${(bits + 7) / 8 + if (usesReportIds) 1 else 0} bytes on the wire\n")
        }
        return sb.toString().trimEnd()
    }

    private fun parse() {
        var i = 0
        var reportSize = 0
        var reportCount = 0
        var reportId = 0
        var depth = 0
        val stack = ArrayDeque<IntArray>()
        val d = raw
        while (i < d.size) {
            val b = d.u8(i)
            if (b == 0xFE) { // long item
                val sz = if (i + 1 < d.size) d.u8(i + 1) else 0
                i += 3 + sz
                continue
            }
            var size = b and 3
            if (size == 3) size = 4
            val type = (b shr 2) and 3
            val tag = (b shr 4) and 0xF
            if (i + size >= d.size) break // truncated item
            var v = 0L
            for (k in 0 until size) v = v or (d.u8(i + 1 + k).toLong() shl (8 * k))
            val value = v.toInt()
            i += 1 + size

            val name: String = when (type) {
                0 -> when (tag) {
                    0x8 -> { add(Kind.Input, reportId, reportSize * reportCount); "Input" }
                    0x9 -> { add(Kind.Output, reportId, reportSize * reportCount); "Output" }
                    0xB -> { add(Kind.Feature, reportId, reportSize * reportCount); "Feature" }
                    0xA -> "Collection"
                    0xC -> "End Collection"
                    else -> "Main(tag=$tag)"
                }
                1 -> when (tag) {
                    0x0 -> "Usage Page"
                    0x1 -> "Logical Min"
                    0x2 -> "Logical Max"
                    0x3 -> "Physical Min"
                    0x4 -> "Physical Max"
                    0x5 -> "Unit Exponent"
                    0x6 -> "Unit"
                    0x7 -> { reportSize = value; "Report Size" }
                    0x8 -> { reportId = value; usesReportIds = true; "Report ID" }
                    0x9 -> { reportCount = value; "Report Count" }
                    0xA -> { stack.addLast(intArrayOf(reportSize, reportCount, reportId)); "Push" }
                    0xB -> {
                        stack.removeLastOrNull()?.let { reportSize = it[0]; reportCount = it[1]; reportId = it[2] }
                        "Pop"
                    }
                    else -> "Global(tag=$tag)"
                }
                2 -> when (tag) {
                    0x0 -> "Usage"
                    0x1 -> "Usage Min"
                    0x2 -> "Usage Max"
                    else -> "Local(tag=$tag)"
                }
                else -> "Reserved"
            }
            if (type == 0 && tag == 0xC) depth = (depth - 1).coerceAtLeast(0)
            itemDump.append("    ").append("  ".repeat(depth)).append(name)
            if (size > 0) itemDump.append(" 0x").append(Integer.toHexString(value).uppercase())
            itemDump.append('\n')
            if (type == 0 && tag == 0xA) depth++
        }
    }

    private fun add(kind: Kind, id: Int, bits: Int) {
        val key = kind to id
        reportBits[key] = (reportBits[key] ?: 0) + bits
    }
}

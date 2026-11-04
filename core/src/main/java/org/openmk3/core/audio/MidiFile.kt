package org.openmk3.core.audio

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Standard MIDI files for patterns.
 *  Export: type 1, one track per sound with notes (named after the sound), each on its own
 *  channel (pad 1 = channel 1 … pad 16 = channel 16), notes at their pitch, 96 ticks per beat.
 *  Import: every note of every track, with its channel, pitch, velocity, start and length.
 */
object MidiFile {
    const val PPQ = 96
    const val TICKS_PER_STEP = PPQ / 4

    class Note(val channel: Int, val note: Int, val vel: Int, val start: Long, val length: Long)

    private fun ByteArrayOutputStream.u32(v: Int) { write(v ushr 24); write(v ushr 16 and 255); write(v ushr 8 and 255); write(v and 255) }
    private fun ByteArrayOutputStream.u16(v: Int) { write(v ushr 8 and 255); write(v and 255) }
    private fun ByteArrayOutputStream.vlq(v0: Long) {
        var v = v0; val b = ArrayList<Int>()
        b.add((v and 0x7F).toInt()); v = v ushr 7
        while (v > 0) { b.add(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        for (i in b.indices.reversed()) write(b[i])
    }

    private fun track(body: ByteArrayOutputStream.() -> Unit): ByteArray {
        val t = ByteArrayOutputStream().apply(body)
        t.write(0); t.write(0xFF); t.write(0x2F); t.write(0)   // end of track
        val o = ByteArrayOutputStream()
        o.write("MTrk".toByteArray()); o.u32(t.size()); o.write(t.toByteArray())
        return o.toByteArray()
    }

    /** Writes [d] (a pattern of group [g]) to [f]; [name] names each sound's track. */
    fun export(f: File, d: PatternData, bpm: Double, name: (Int) -> String): Int {
        val tracks = ArrayList<ByteArray>()
        tracks.add(track {
            vlq(0); write(0xFF); write(0x51); write(3)
            val us = (60_000_000.0 / bpm).toInt(); write(us ushr 16 and 255); write(us ushr 8 and 255); write(us and 255)
            vlq(0); write(0xFF); write(0x58); write(4); write(4); write(2); write(24); write(8)
        })
        var count = 0
        for (pad in 0 until Pattern.PADS) {
            data class Ev(val t: Long, val on: Boolean, val n: Int, val v: Int)
            val ev = ArrayList<Ev>()
            for (s in 0 until d.length) {
                if (!d.has(pad, s)) continue
                val t0 = ((s + d.off[pad][s]) * TICKS_PER_STEP).toLong()
                val t1 = t0 + maxOf(1L, (d.len[pad][s] * TICKS_PER_STEP).toLong())
                fun add(n: Int, v: Float) { if (v <= 0f) return; val vv = (v * 127).toInt().coerceIn(1, 127); ev.add(Ev(t0, true, n, vv)); ev.add(Ev(t1, false, n, 0)); count++ }
                add(d.note[pad][s].toInt().coerceIn(0, 127), d.vel[pad][s])
                for (x in 0 until Pattern.EXTRA) add(d.xnote[pad][s * Pattern.EXTRA + x].toInt().coerceIn(0, 127), d.xvel[pad][s * Pattern.EXTRA + x])
            }
            if (ev.isEmpty()) continue
            ev.sortWith(compareBy<Ev> { it.t }.thenBy { it.on })   // note-offs first at the same tick
            tracks.add(track {
                val nm = name(pad).toByteArray()
                vlq(0); write(0xFF); write(0x03); vlq(nm.size.toLong()); write(nm)
                var last = 0L
                for (e in ev) {
                    vlq(e.t - last); last = e.t
                    write((if (e.on) 0x90 else 0x80) or pad); write(e.n); write(if (e.on) e.v else 64)
                }
            })
        }
        val o = ByteArrayOutputStream()
        o.write("MThd".toByteArray()); o.u32(6); o.u16(1); o.u16(tracks.size); o.u16(PPQ)
        tracks.forEach { o.write(it) }
        f.parentFile?.mkdirs()
        f.writeBytes(o.toByteArray())
        return count
    }

    /** Reads every note (ticks scaled to [PPQ]). */
    fun read(f: File): List<Note> {
        val b = f.readBytes()
        var p = 0
        fun u8() = b[p++].toInt() and 255
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u16() shl 16) or u16()
        fun vlq(): Long { var v = 0L; while (true) { val c = u8(); v = (v shl 7) or (c and 0x7F).toLong(); if (c and 0x80 == 0) return v } }
        if (String(b, 0, 4) != "MThd") throw java.io.IOException("not a MIDI file")
        p = 4; val hl = u32(); u16(); val ntr = u16(); val div = u16(); p = 8 + hl
        if (div and 0x8000 != 0) throw java.io.IOException("SMPTE timing is not supported")
        val scale = PPQ.toDouble() / div
        val out = ArrayList<Note>()
        repeat(ntr) {
            if (p + 8 > b.size) return@repeat
            val id = String(b, p, 4); p += 4; val len = u32(); val end = p + len
            if (id != "MTrk") { p = end; return@repeat }
            var t = 0L; var status = 0
            val open = HashMap<Int, Pair<Long, Int>>()   // channel*128+note -> start, vel
            while (p < end) {
                t += vlq()
                var st = u8()
                if (st < 0x80) { p--; st = status } else if (st < 0xF0) status = st
                when {
                    st == 0xFF -> { u8(); val l = vlq().toInt(); p += l }
                    st == 0xF0 || st == 0xF7 -> { val l = vlq().toInt(); p += l }
                    else -> {
                        val ch = st and 15; val type = st and 0xF0
                        val d1 = u8(); val d2 = if (type == 0xC0 || type == 0xD0) 0 else u8()
                        if (type == 0x90 && d2 > 0) open[ch * 128 + d1] = t to d2
                        else if (type == 0x80 || (type == 0x90 && d2 == 0)) open.remove(ch * 128 + d1)?.let { (s, v) ->
                            out.add(Note(ch, d1, v, (s * scale).toLong(), maxOf(1L, ((t - s) * scale).toLong())))
                        }
                    }
                }
            }
            p = end
        }
        return out.sortedBy { it.start }
    }
}

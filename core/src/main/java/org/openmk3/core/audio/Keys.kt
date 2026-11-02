package org.openmk3.core.audio

import android.content.Context
import org.json.JSONObject
import org.openmk3.core.AppLog
import java.io.File

/**
 * KEYBOARD and CHORDS settings and the pad -> note mapping (pad 1 = bottom-left = lowest).
 * Notes are MIDI numbers; 60 (C3) plays a sample at its own pitch.
 */
class Keys {
    companion object {
        val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        fun noteName(n: Int) = NOTE_NAMES[((n % 12) + 12) % 12] + (n / 12 - 2)  // 60 = C3

        val SCALES: List<Pair<String, IntArray>> = listOf(
            "CHROMATIC" to IntArray(12) { it },
            "MAJOR" to intArrayOf(0, 2, 4, 5, 7, 9, 11),
            "MINOR" to intArrayOf(0, 2, 3, 5, 7, 8, 10),
            "HARMONIC MINOR" to intArrayOf(0, 2, 3, 5, 7, 8, 11),
            "MELODIC MINOR" to intArrayOf(0, 2, 3, 5, 7, 9, 11),
            "DORIAN" to intArrayOf(0, 2, 3, 5, 7, 9, 10),
            "PHRYGIAN" to intArrayOf(0, 1, 3, 5, 7, 8, 10),
            "LYDIAN" to intArrayOf(0, 2, 4, 6, 7, 9, 11),
            "MIXOLYDIAN" to intArrayOf(0, 2, 4, 5, 7, 9, 10),
            "LOCRIAN" to intArrayOf(0, 1, 3, 5, 6, 8, 10),
            "MAJOR PENTA" to intArrayOf(0, 2, 4, 7, 9),
            "MINOR PENTA" to intArrayOf(0, 3, 5, 7, 10),
            "BLUES" to intArrayOf(0, 3, 5, 6, 7, 10),
            "WHOLE TONE" to intArrayOf(0, 2, 4, 6, 8, 10),
        )

        /** Harmonizer chord shapes, as scale-degree steps above each pad's note. */
        val CHORD_TYPES: List<Pair<String, IntArray>> = listOf(
            "1-3" to intArrayOf(0, 2),
            "1-5" to intArrayOf(0, 4),
            "1-3-5" to intArrayOf(0, 2, 4),
            "1-3-5-7" to intArrayOf(0, 2, 4, 6),
            "1-3-7-9" to intArrayOf(0, 2, 6, 8),
            "1-2-5 SUS2" to intArrayOf(0, 1, 4),
            "1-4-5 SUS4" to intArrayOf(0, 3, 4),
            "1-5-8" to intArrayOf(0, 4, 7),
        )

        // chord qualities (semitones above the chord root, at most 4 notes)
        private val Q = mapOf(
            "" to intArrayOf(0, 4, 7), "m" to intArrayOf(0, 3, 7), "dim" to intArrayOf(0, 3, 6),
            "aug" to intArrayOf(0, 4, 8), "sus2" to intArrayOf(0, 2, 7), "sus4" to intArrayOf(0, 5, 7),
            "maj7" to intArrayOf(0, 4, 7, 11), "7" to intArrayOf(0, 4, 7, 10), "m7" to intArrayOf(0, 3, 7, 10),
            "m7b5" to intArrayOf(0, 3, 6, 10), "dim7" to intArrayOf(0, 3, 6, 9), "add9" to intArrayOf(0, 4, 7, 14),
            "maj9" to intArrayOf(0, 4, 11, 14), "9" to intArrayOf(0, 4, 10, 14), "m9" to intArrayOf(0, 3, 10, 14),
            "6/9" to intArrayOf(0, 4, 9, 14), "m11" to intArrayOf(0, 3, 10, 17), "5" to intArrayOf(0, 7, 12),
        )

        private fun set(vararg c: Pair<Int, String>) = c.toList()

        /** Chord sets: 16 chords each, relative to the root note. Our own selections. */
        val CHORD_SETS: List<Pair<String, List<Pair<Int, String>>>> = listOf(
            "POP" to set(0 to "", 7 to "", 9 to "m", 5 to "", 2 to "m", 4 to "m", 5 to "maj7", 7 to "7",
                0 to "maj7", 9 to "m7", 2 to "m7", 7 to "sus4", 5 to "add9", 0 to "add9", 10 to "", 8 to ""),
            "MINOR" to set(0 to "m", 8 to "", 3 to "", 10 to "", 5 to "m", 7 to "m", 7 to "", 2 to "dim",
                0 to "m7", 8 to "maj7", 3 to "maj7", 10 to "7", 5 to "m7", 7 to "7", 0 to "m9", 5 to "m9"),
            "JAZZ" to set(2 to "m7", 7 to "7", 0 to "maj7", 9 to "m7", 4 to "m7", 9 to "7", 2 to "m9", 7 to "9",
                0 to "maj9", 5 to "maj7", 10 to "7", 3 to "dim7", 11 to "m7b5", 4 to "7", 9 to "m9", 0 to "6/9"),
            "NEO SOUL" to set(0 to "maj9", 4 to "m7", 9 to "m9", 2 to "m11", 5 to "maj7", 7 to "9", 3 to "maj7", 10 to "9",
                0 to "m9", 5 to "m11", 8 to "maj9", 7 to "m7", 2 to "m9", 9 to "m11", 4 to "7", 11 to "m7b5"),
            "HOUSE" to set(0 to "m7", 0 to "m9", 3 to "maj7", 5 to "m7", 7 to "m7", 8 to "maj7", 10 to "7", 2 to "m7",
                0 to "m11", 5 to "m9", 7 to "m9", 10 to "maj7", 3 to "6/9", 8 to "maj9", 5 to "9", 0 to "sus2"),
            "CINEMATIC" to set(0 to "m", 8 to "", 3 to "", 10 to "", 0 to "sus2", 5 to "sus4", 7 to "", 1 to "",
                0 to "5", 8 to "maj7", 5 to "m", 3 to "sus2", 10 to "sus4", 7 to "m", 2 to "dim", 0 to "aug"),
        )

        private fun file(ctx: Context) = File(ctx.filesDir, "keys.json")

        fun load(ctx: Context): Keys {
            val k = Keys()
            try {
                val f = file(ctx)
                if (f.exists()) {
                    val j = JSONObject(f.readText())
                    k.root = j.optInt("root", 0).coerceIn(0, 11)
                    k.octave = j.optInt("octave", 3).coerceIn(-1, 7)
                    k.scale = j.optInt("scale", 0).coerceIn(0, SCALES.size - 1)
                    k.chordMode = j.optInt("chordMode", 0).coerceIn(0, 1)
                    k.chordType = j.optInt("chordType", 2).coerceIn(0, CHORD_TYPES.size - 1)
                    k.chordSet = j.optInt("chordSet", 0).coerceIn(0, CHORD_SETS.size - 1)
                }
            } catch (e: Exception) {
                AppLog.i("keys.json unreadable: $e")
            }
            return k
        }
    }

    var root = 0          // 0 = C
    var octave = 3        // the octave of pad 1 (3 -> C3 = 60)
    var scale = 0
    var chordMode = 0     // 0 harmonizer (chords from the scale), 1 chord set
    var chordType = 2
    var chordSet = 0

    /** MIDI note of pad 1. */
    val baseNote get() = (12 * (octave + 2) + root).coerceIn(0, 127)

    private fun scaleSteps() = SCALES[scale].second

    /** Note of scale degree [d] (0 = root) above the base note. */
    private fun degree(d: Int, steps: IntArray): Int {
        val n = steps.size
        val oct = Math.floorDiv(d, n)
        return baseNote + 12 * oct + steps[Math.floorMod(d, n)]
    }

    /** KEYBOARD: chromatic, or consecutive notes of the scale. */
    fun noteFor(slot: Int): Int = degree(slot, scaleSteps()).coerceIn(0, 127)

    /** CHORDS: the chord a pad plays (up to 4 notes). */
    fun chordFor(slot: Int): IntArray {
        if (chordMode == 1) {
            val (off, q) = CHORD_SETS[chordSet].second[slot.coerceIn(0, 15)]
            val r = baseNote + off
            return (Q[q] ?: Q[""]!!).map { (r + it).coerceIn(0, 127) }.toIntArray()
        }
        // harmonizer: stack degrees of the scale (chromatic falls back to major)
        val steps = if (scale == 0) SCALES[1].second else scaleSteps()
        return CHORD_TYPES[chordType].second.map { degree(slot + it, steps).coerceIn(0, 127) }.toIntArray()
    }

    fun chordName(slot: Int): String {
        if (chordMode == 1) {
            val (off, q) = CHORD_SETS[chordSet].second[slot.coerceIn(0, 15)]
            return NOTE_NAMES[(root + off) % 12] + q
        }
        val c = chordFor(slot)
        return NOTE_NAMES[c[0] % 12] + when {
            c.size >= 3 && c[1] - c[0] == 4 && c[2] - c[0] == 7 -> if (c.size > 3) (if (c[3] - c[0] == 11) "maj7" else "7") else ""
            c.size >= 3 && c[1] - c[0] == 3 && c[2] - c[0] == 7 -> if (c.size > 3) "m7" else "m"
            c.size >= 3 && c[1] - c[0] == 3 && c[2] - c[0] == 6 -> if (c.size > 3) "m7b5" else "dim"
            c.size >= 3 && c[2] - c[0] == 7 && (c[1] - c[0] == 2 || c[1] - c[0] == 5) -> if (c[1] - c[0] == 2) "sus2" else "sus4"
            else -> ""
        }
    }

    /** Pads that play the root note (lit brighter). */
    fun isRoot(note: Int) = Math.floorMod(note - root, 12) == 0

    fun transpose(semitones: Int) {
        val n = baseNote + semitones
        if (n < 0 || n > 120) return
        root = Math.floorMod(n, 12)
        octave = n / 12 - 2
    }

    fun save(ctx: Context) {
        try {
            file(ctx).writeText(JSONObject().apply {
                put("root", root); put("octave", octave); put("scale", scale)
                put("chordMode", chordMode); put("chordType", chordType); put("chordSet", chordSet)
            }.toString())
        } catch (e: Exception) {
            AppLog.i("keys.json save failed: $e")
        }
    }
}

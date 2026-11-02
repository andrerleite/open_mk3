package org.openmk3.core.mk3

/**
 * Indexed colours of the MK3's RGB LEDs (pads, touch strip, group buttons, SAMPLING),
 * confirmed with the LED palette page photos (2026-11-01):
 *
 *   value = colour * 4 + brightness      colour 0 = off, 1..16 = hues, 17+ = white
 *   brightness 0 = dim, 1, 2 = most saturated, 3 = brightest (washes towards white)
 *
 * Single-colour button LEDs ignore this and just use 0..127 as brightness.
 * Power note: all LEDs at full white raised phone battery drain to 3–4 A, so keep the
 * default looks dim and save bright values for feedback.
 */
object Mk3Colors {
    const val OFF = 0
    const val RED = 1
    const val ORANGE = 2
    const val LIGHT_ORANGE = 3
    const val WARM_YELLOW = 4
    const val YELLOW = 5
    const val LIME = 6
    const val GREEN = 7
    const val MINT = 8
    const val CYAN = 9
    const val TURQUOISE = 10
    const val BLUE = 11
    const val PLUM = 12
    const val VIOLET = 13
    const val PURPLE = 14
    const val MAGENTA = 15
    const val FUCHSIA = 16
    const val WHITE = 17

    val NAMES = arrayOf(
        "off", "red", "orange", "light orange", "warm yellow", "yellow", "lime", "green", "mint",
        "cyan", "turquoise", "blue", "plum", "violet", "purple", "magenta", "fuchsia", "white",
    )

    /** LED value for [colour] at [brightness] 0..3. */
    fun value(colour: Int, brightness: Int): Int =
        if (colour <= 0) 0 else (colour.coerceAtMost(WHITE) shl 2) or brightness.coerceIn(0, 3)

    fun describe(value: Int): String {
        val c = value shr 2
        return if (c == 0) "off" else "${NAMES[c.coerceAtMost(WHITE)]} b${value and 3}"
    }

    /** Approximate screen colour for drawing an LED colour on the MK3 displays. */
    fun rgb(colour: Int): Int = when (colour) {
        RED -> 0xFFE53935.toInt()
        ORANGE -> 0xFFFF6D00.toInt()
        LIGHT_ORANGE -> 0xFFFF9800.toInt()
        WARM_YELLOW -> 0xFFFFB74D.toInt()
        YELLOW -> 0xFFFFEB3B.toInt()
        LIME -> 0xFFC0CA33.toInt()
        GREEN -> 0xFF43A047.toInt()
        MINT -> 0xFF69F0AE.toInt()
        CYAN -> 0xFF00BCD4.toInt()
        TURQUOISE -> 0xFF26C6DA.toInt()
        BLUE -> 0xFF1E88E5.toInt()
        PLUM -> 0xFF7E57C2.toInt()
        VIOLET -> 0xFF9C27B0.toInt()
        PURPLE -> 0xFFAB47BC.toInt()
        MAGENTA -> 0xFFE040FB.toInt()
        FUCHSIA -> 0xFFF06292.toInt()
        WHITE -> 0xFFEEEEEE.toInt()
        else -> 0xFF282832.toInt()
    }
}

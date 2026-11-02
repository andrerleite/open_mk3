package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A zoomable waveform: shows [viewStart, viewEnd) of a sample in [COLS] columns, with a
 * selected region, markers and a playhead. Zoom keeps a focus frame (the marker being
 * edited) in place; scrolling moves a quarter of the view.
 */
class WaveView {
    companion object {
        const val COLS = 448
        private const val MIN_VIEW = 448  // frames: at most zoomed, about one frame per column

        /**
         * Nearest zero crossing of [data] (interleaved, [ch] channels) to frame [f], within
         * [radius] frames; [f] itself if there is none.
         */
        fun zeroCross(data: FloatArray, ch: Int, f: Int, radius: Int = 2400): Int {
            val n = data.size / ch
            if (n < 2) return f
            fun mono(i: Int): Float {
                var s = 0f
                for (c in 0 until ch) s += data[i * ch + c]
                return s
            }
            val c = f.coerceIn(0, n - 1)
            for (d in 0..radius) {
                for (i in intArrayOf(c - d, c + d)) {
                    if (i <= 0 || i >= n) continue
                    val a = mono(i - 1)
                    val b = mono(i)
                    if ((a <= 0f && b > 0f) || (a >= 0f && b < 0f) || b == 0f) return i
                }
            }
            return c
        }
    }

    private var data: FloatArray? = null
    private var ch = 1
    var frames = 0
        private set
    var viewStart = 0
        private set
    var viewEnd = 0
        private set
    val viewLen get() = viewEnd - viewStart

    private val mn = FloatArray(COLS)
    private val mx = FloatArray(COLS)
    private var dirty = true

    private val waveIn = Theme.fill(Color.rgb(90, 200, 120))
    private val waveOut = Theme.fill(Color.rgb(50, 70, 60))

    /** Draws the waveform in [rgb] (the pad's colour); outside the selection, dimmed. */
    fun setColour(rgb: Int) {
        waveIn.color = rgb
        waveOut.color = Color.rgb(Color.red(rgb) * 3 / 10 + 10, Color.green(rgb) * 3 / 10 + 10, Color.blue(rgb) * 3 / 10 + 14)
    }
    private val marker = Theme.fill(Theme.ACCENT)
    private val loopFill = Theme.fill(Color.rgb(80, 160, 255))
    private val head = Theme.fill(Color.WHITE)
    private val zoomText = Theme.text(12f, Theme.DIM, align = Paint.Align.RIGHT)
    val sliceFill = Theme.fill(Color.rgb(80, 160, 255))

    /** Shows [d] (keeps the zoom if it's the same sample). */
    fun set(d: FloatArray?, channels: Int) {
        if (d === data) return
        data = d
        ch = max(1, channels)
        frames = (d?.size ?: 0) / ch
        viewStart = 0
        viewEnd = frames
        dirty = true
    }

    val zoomed get() = frames > 0 && viewLen < frames

    /** [steps] > 0 zooms in, < 0 out, around [focus] (a frame). */
    fun zoom(steps: Int, focus: Int) {
        if (frames <= 0) return
        val len = viewLen.toDouble()
        val newLen = (len * 0.8.pow(steps.toDouble())).toInt().coerceIn(min(MIN_VIEW, frames), frames)
        val f = focus.coerceIn(viewStart, max(viewStart, viewEnd - 1))
        val rel = if (len > 0) (f - viewStart) / len else 0.5
        var s = (f - rel * newLen).toInt()
        s = s.coerceIn(0, frames - newLen)
        viewStart = s
        viewEnd = s + newLen
        dirty = true
    }

    /** Moves the view by [steps] quarters of its length. */
    fun scroll(steps: Int) {
        if (frames <= 0) return
        val len = viewLen
        val s = (viewStart + steps * len / 4).coerceIn(0, frames - len)
        viewStart = s
        viewEnd = s + len
        dirty = true
    }

    /** Scrolls just enough to keep [f] visible. */
    fun follow(f: Int) {
        if (!zoomed) return
        val len = viewLen
        if (f < viewStart) { viewStart = max(0, f - len / 10); viewEnd = viewStart + len; dirty = true }
        else if (f >= viewEnd) { viewEnd = min(frames, f + len / 10); viewStart = viewEnd - len; dirty = true }
    }

    /** Knob step in frames: a full turn crosses the visible part (SHIFT: a tenth of that). */
    fun knobStep(fine: Boolean) = max(1, viewLen / (if (fine) 10000 else 1000))

    private fun rebuild() {
        val d = data ?: return
        val len = max(1, viewLen)
        for (c in 0 until COLS) {
            val a = viewStart + (c.toLong() * len / COLS).toInt()
            val b = max(a + 1, viewStart + ((c + 1).toLong() * len / COLS).toInt())
            var lo = 0f
            var hi = 0f
            val stride = max(1, (b - a) / 256)
            var f = a
            while (f < min(b, frames)) {
                var v = 0f
                for (k in 0 until ch) v += d[f * ch + k]
                v /= ch
                if (v < lo) lo = v
                if (v > hi) hi = v
                f += stride
            }
            mn[c] = lo
            mx[c] = hi
        }
        dirty = false
    }

    private fun xOf(left: Float, f: Int): Float = left + (f - viewStart).toFloat() * COLS / max(1, viewLen)

    /**
     * Draws at [left], between [top] and [bot]. Frames in [selA, selB) are highlighted (all
     * if selA < 0); [markers] get a tall line, [loop] (two frames) a bar above, [slices]
     * thin lines, [playhead] (frame, < 0 = none) a white line.
     */
    fun draw(
        c: Canvas, left: Float, top: Float, bot: Float,
        selA: Int = -1, selB: Int = -1, markers: IntArray = IntArray(0),
        loop: IntArray? = null, slices: IntArray = IntArray(0), playhead: Int = -1,
    ) {
        if (data == null || frames == 0) return
        if (dirty) rebuild()
        val mid = (top + bot) / 2
        val half = (bot - top) / 2
        for (col in 0 until COLS) {
            val f = viewStart + (col.toLong() * viewLen / COLS).toInt()
            val inSel = selA < 0 || f in selA until selB
            val y0 = mid - mx[col].coerceIn(-1f, 1f) * half
            val y1 = mid - mn[col].coerceIn(-1f, 1f) * half
            c.drawRect(left + col, y0, left + col + 1f, max(y1, y0 + 1f), if (inSel) waveIn else waveOut)
        }
        fun vis(f: Int) = f in viewStart..viewEnd
        if (loop != null && loop.size == 2) {
            val a = xOf(left, loop[0].coerceIn(viewStart, viewEnd))
            val b = xOf(left, loop[1].coerceIn(viewStart, viewEnd))
            if (b > a) c.drawRect(a, top - 8f, b, top - 4f, loopFill)
        }
        for (s in slices) if (vis(s)) { val x = xOf(left, s); c.drawRect(x - 1f, top - 6f, x + 1f, bot + 6f, sliceFill) }
        for (m in markers) if (vis(m)) { val x = xOf(left, m); c.drawRect(x - 1f, top - 6f, x + 1f, bot + 6f, marker) }
        if (playhead >= 0 && vis(playhead)) { val x = xOf(left, playhead); c.drawRect(x - 1f, top, x + 1f, bot, head) }
        if (zoomed) c.drawText("zoom ×${"%.1f".format(frames.toFloat() / viewLen)}", left + COLS, top - 10f, zoomText)
    }
}

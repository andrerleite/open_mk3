package org.openmk3.core.audio

/**
 * LOCK snapshots: every sound setting, every effect setting, group volume / pan and the master
 * volume. One quick snapshot (LOCK) plus 16 stored ones (SHIFT + LOCK), with optional morphing
 * from the current state to a recalled snapshot.
 */
class Lock(private val e: AudioEngine) {

    class Snapshot(val sound: FloatArray, val fx: FloatArray, val group: FloatArray, val master: Float)

    val slots = arrayOfNulls<Snapshot>(16)
    var quick: Snapshot? = null
    var selected = -1
    var morph = false
    /** Morph time in bars. */
    var morphBars = 1

    // a running morph
    private var from: Snapshot? = null
    private var to: Snapshot? = null
    private var morphStart = 0L
    private var morphMs = 0L

    fun take(): Snapshot {
        val n = Pattern.SOUNDS * SoundParams.COUNT
        val sound = FloatArray(n) { e.params[it / SoundParams.COUNT, it % SoundParams.COUNT] }
        val fxN = FxParams.CHAINS * FxParams.SLOTS * FxParams.PER_SLOT
        val fx = FloatArray(fxN) { i ->
            val c = i / (FxParams.SLOTS * FxParams.PER_SLOT)
            val k = (i / FxParams.PER_SLOT) % FxParams.SLOTS
            e.fx[c, k, i % FxParams.PER_SLOT]
        }
        val group = FloatArray(Pattern.GROUPS * 2) { if (it < 8) e.pattern.groupVolume(it) else e.pattern.groupPan(it - 8) }
        return Snapshot(sound, fx, group, e.masterGain)
    }

    /** Sets the state to [a] -> [b] at [t] (0..1); discrete values (types, choices) jump at the end. */
    private fun apply(a: Snapshot, b: Snapshot, t: Float) {
        for (i in b.sound.indices) {
            val s = i / SoundParams.COUNT
            val p = i % SoundParams.COUNT
            if (p == SoundParams.BEND || p == SoundParams.MODW) continue  // live controls stay live
            val d = SoundParams.DEFS[p]
            val discrete = d == null || d.choices != null || p == SoundParams.SRC
            e.params[s, p] = if (discrete) (if (t >= 1f) b.sound[i] else a.sound[i]) else a.sound[i] + (b.sound[i] - a.sound[i]) * t
        }
        for (i in b.fx.indices) {
            val c = i / (FxParams.SLOTS * FxParams.PER_SLOT)
            val k = (i / FxParams.PER_SLOT) % FxParams.SLOTS
            val p = i % FxParams.PER_SLOT
            if (p <= 1) continue  // effect type / bypass are not part of a recall (they rebuild effects)
            e.fx[c, k, p] = a.fx[i] + (b.fx[i] - a.fx[i]) * t
        }
        for (g in 0 until Pattern.GROUPS) {
            e.pattern.setGroupVolume(g, a.group[g] + (b.group[g] - a.group[g]) * t)
            e.pattern.setGroupPan(g, a.group[g + 8] + (b.group[g + 8] - a.group[g + 8]) * t)
        }
        e.masterGain = a.master + (b.master - a.master) * t
    }

    /** Recalls [s]: at once, or morphing over [morphBars] bars. */
    fun recall(s: Snapshot) {
        if (!morph) { apply(s, s, 1f); return }
        from = take()
        to = s
        morphStart = android.os.SystemClock.uptimeMillis()
        morphMs = (morphBars * 4 * 60000.0 / e.bpm).toLong().coerceAtLeast(50)
    }

    /** Advances a running morph; returns true while one runs. Call from the UI thread. */
    fun tick(now: Long): Boolean {
        val a = from ?: return false
        val b = to ?: return false
        val t = ((now - morphStart).toFloat() / morphMs).coerceIn(0f, 1f)
        apply(a, b, t)
        if (t >= 1f) { from = null; to = null }
        return true
    }
}

package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import org.openmk3.core.AppLog
import org.openmk3.core.audio.Pattern
import org.openmk3.core.mk3.Mk3Colors

/**
 * PATTERN and SCENE modes. Pads are the 16 pattern slots of the focused group (PATTERN) or
 * the 16 scenes (SCENE):
 *  - pad with content: select (while playing it starts when the current pattern ends)
 *  - empty pad: new empty pattern / new scene from the patterns playing now
 *  - ERASE + pad: delete · DUPLICATE + source pad + target pad: duplicate
 *  - GROUP buttons (PATTERN): another group
 * D7 / D8: previous / next bank of 16 (4 banks).
 * PATTERN: K1 length (16 steps per turn notch; SHIFT: single steps), D5 duplicate to the next
 * free slot, D6 delete, D7 double, D8 clear notes.
 * SCENE: D5 duplicate, D6 delete, D8 update the scene with the patterns playing now.
 * Hold the mode button for a quick look (release returns); a short press keeps the mode on.
 */
class SlotPage(ui: Ui, private val back: Page, val kind: Kind) : Page(ui) {

    override val handlesTransport = true

    enum class Kind { PATTERN, SCENE }

    override val customPads = true
    private val p = ui.engine.pattern
    private var erase = false
    private var dup = false
    private var dupSource = -1
    private var shift = false
    private var message = ""
    private var lenAcc = 0

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val big = Theme.text(22f, Theme.FG, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val ok = Theme.text(15f, Theme.OK)
    private val dLabel = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val cellName = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val cellDim = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val ring = Theme.stroke(Theme.FG, 3f)
    private val empty = Theme.fill(android.graphics.Color.rgb(32, 32, 40))

    private companion object {
        val patBank = IntArray(8)
        var sceneBank = 0
    }

    private var bank: Int
        get() = if (kind == Kind.PATTERN) patBank[g] else sceneBank
        set(v) { if (kind == Kind.PATTERN) patBank[g] = v else sceneBank = v }
    private val banks = Pattern.SLOTS / Pattern.BANK_SLOTS
    /** Slot of pad [i] in the shown bank. */
    private fun s(i: Int) = bank * Pattern.BANK_SLOTS + i

    init {
        // open on the bank of the playing pattern / scene
        val cur = if (kind == Kind.PATTERN) p.current[ui.group] else p.currentScene
        if (cur >= 0) bank = cur / Pattern.BANK_SLOTS
    }

    private val playing get() = ui.engine.playing
    private val g get() = ui.group

    private fun has(i: Int) = if (kind == Kind.PATTERN) p.patterns[g][i] != null else p.scenes[i] != null
    private fun isCurrent(i: Int) = if (kind == Kind.PATTERN) p.current[g] == i else p.currentScene == i
    private fun isQueued(i: Int) = kind == Kind.PATTERN && p.queued[g] == i

    fun back() = ui.show(back)

    override fun tick(now: Long): Boolean {
        p.poll()
        return true
    }

    override fun padLight(slot: Int, now: Long): Int? = null

    private fun updatePads(now: Long) {
        val c = if (kind == Kind.PATTERN) groupColour(g) else Mk3Colors.WHITE
        val blink = (now / 200) % 2 == 0L
        for (i in 0 until 16) {
            val i = s(i)
            val v = when {
                isQueued(i) -> if (blink) Mk3Colors.value(c, 3) else Mk3Colors.value(c, 0)
                isCurrent(i) -> Mk3Colors.value(c, 3)
                has(i) -> Mk3Colors.value(c, 0)
                else -> 0
            }
            ui.padLed(i, v)
        }
    }

    override fun lights(l: Lights) {
        l.set(if (kind == Kind.PATTERN) "PATTERN" else "SCENE", true)
        l.set("ERASE", erase)
        l.set("DUPLICATE", dup)
        l.set("SHIFT", shift)
        l.set("PLAY", playing)
        l.set("STOP")
        l.set("D5", "D6")
        l.set("D7", bank > 0); l.set("D8", bank < banks - 1)
        if (kind == Kind.SCENE) l.set("D2")
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        val i = s(slot)
        when {
            erase -> {
                if (!has(i)) return
                if (kind == Kind.PATTERN) p.deletePattern(g, i, playing) else p.deleteScene(i)
                message = "${name(i)} deleted"
            }
            dup -> {
                if (dupSource < 0) {
                    if (has(i)) { dupSource = i; message = "Duplicate ${name(i)} to… (hit the target pad)" }
                } else {
                    if (kind == Kind.PATTERN) p.duplicatePattern(g, dupSource, i)
                    else p.scenes[dupSource]?.let { p.scenes[i] = it.copyOf() }
                    message = "${name(dupSource)} → ${name(i)}"
                    dupSource = -1
                }
            }
            kind == Kind.PATTERN -> {
                val created = !has(i)
                p.selectPattern(g, i, playing)
                message = if (created) "New pattern ${name(i)}" else if (playing && p.queued[g] == i) "${name(i)} starts at the end of the pattern" else "${name(i)}"
            }
            else -> {
                if (!has(i)) { p.captureScene(i); message = "New scene ${name(i)} (patterns playing now)" }
                else if (p.songMode && i in p.song) {
                    // song mode: jump to that scene's section (at the end of the playing one)
                    val sec = p.song.indexOf(i)
                    if (playing) p.songJump = sec else p.songPos = sec
                    message = "Section ${sec + 1} (scene ${i + 1})"
                }
                else { p.launchScene(i, playing); message = "Scene ${i + 1}" }
            }
        }
        changed()
    }

    private fun name(i: Int) = if (kind == Kind.PATTERN) "${ui.groupLetter}${i + 1}" else "Scene ${i + 1}"

    private fun changed() {
        p.save(ui.ctx)
        ui.checkpoint()
        AppLog.i(message)
        ui.invalidate()
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        when (name) {
            "ERASE" -> { erase = down; return }
            "DUPLICATE" -> { dup = down; if (!down) dupSource = -1; return }
            "SHIFT" -> { shift = down; return }
        }
        if (!down) return
        val e = ui.engine
        when (name) {
            "PLAY" -> if (e.playing) e.transportStop() else e.transportPlay()
            "STOP" -> e.stopPressed()
            "D5" -> createNext()
            "D6" -> deleteCurrent()
            "D7" -> if (bank > 0) bank--
            "D8" -> if (bank < banks - 1) bank++
            "D2" -> if (kind == Kind.SCENE && p.currentScene >= 0) { p.captureScene(p.currentScene); message = "Scene ${p.currentScene + 1} updated"; changed() }
            "BROWSER", "SAMPLING", "PLUG-IN", "SETTINGS", "MIXER", "FILE" -> back()
        }
    }

    /** D5 CREATE: a new empty pattern / scene in the next free slot after the current one. */
    private fun createNext() {
        val cur = if (kind == Kind.PATTERN) p.current[g] else p.currentScene
        val free = ((cur + 1 until Pattern.SLOTS) + (0..cur.coerceAtLeast(0))).firstOrNull { it in 0 until Pattern.SLOTS && !has(it) }
            ?: run { message = "No free slot"; return }
        if (kind == Kind.PATTERN) p.selectPattern(g, free, playing) else p.captureScene(free)
        message = "New ${name(free)}"
        changed()
    }

    private fun duplicateCurrent() {
        val cur = if (kind == Kind.PATTERN) p.current[g] else p.currentScene
        if (cur < 0) return
        val free = (0 until Pattern.SLOTS).firstOrNull { !has(it) } ?: run { message = "No free slot"; return }
        if (kind == Kind.PATTERN) { p.duplicatePattern(g, cur, free); p.selectPattern(g, free, playing) }
        else { p.scenes[free] = p.scenes[cur]!!.copyOf(); p.currentScene = free }
        message = "${name(cur)} → ${name(free)}"
        changed()
    }

    private fun deleteCurrent() {
        val cur = if (kind == Kind.PATTERN) p.current[g] else p.currentScene
        if (cur < 0) return
        if (kind == Kind.PATTERN) p.deletePattern(g, cur, playing) else p.deleteScene(cur)
        message = "${name(cur)} deleted"
        changed()
    }

    override fun onKnob(knob: Int, delta: Int) {
        if (kind != Kind.PATTERN || knob != 3) return  // K4 LENGTH
        lenAcc += delta
        if (kotlin.math.abs(lenAcc) < 25) return
        val d = if (lenAcc > 0) 1 else -1
        lenAcc = 0
        val cur = p.length(g)
        val a = ui.arrangeGrid
        val next = if (shift) cur + d else (if (d > 0) (cur / a + 1) * a else ((cur - 1) / a) * a)
        p.setLength(g, next.coerceIn(1, Pattern.STEPS))
        message = "Length ${lengthText(p.length(g))}"
        p.save(ui.ctx)
        ui.checkpoint()
    }

    override fun onEncoder(delta: Int) = onKnob(3, delta * 25)

    private fun lengthText(steps: Int) = if (steps % 16 == 0) "${steps / 16} bar${if (steps > 16) "s" else ""}" else "$steps steps"

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        updatePads(now)
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        val pinName = if (kind == Kind.PATTERN) "PATTERN" else "SCENE"
        l.drawText("PIN", 60f, 24f, if (pinName in ui.pinned) cellName else dLabel)
        if (kind == Kind.SCENE) l.drawText("UPDATE", 180f, 24f, dLabel)
        r.drawText("CREATE", 60f, 24f, dLabel)
        r.drawText("DELETE", 180f, 24f, dLabel)
        r.drawText("◀ BANK", 300f, 24f, dLabel)
        r.drawText("BANK ▶", 420f, 24f, dLabel)
        if (kind == Kind.PATTERN) {
            l.drawText("PATTERN · GROUP ${ui.groupLetter}", 16f, 52f, title)
            val cur = p.current[g]
            l.drawText(if (cur >= 0) "${ui.groupLetter}${cur + 1}" else "No pattern", 16f, 82f, big)
            l.drawText("Length ${lengthText(p.length(g))}", 16f, 100f, label)
            if (p.queued[g] >= 0) l.drawText("Next: ${ui.groupLetter}${p.queued[g] + 1} (at the end of this one)", 16f, 124f, ok)
            l.drawText("K4 length (SHIFT: steps) · ERASE + pad delete", 16f, 196f, label)
            l.drawText("DUPLICATE + pad + pad · GROUP: other group", 16f, 216f, label)
        } else {
            l.drawText("SCENE", 16f, 52f, title)
            val cur = p.currentScene
            l.drawText(if (cur >= 0) "Scene ${cur + 1}" else "No scene", 16f, 82f, big)
            p.scenes.getOrNull(cur)?.let { sc ->
                val txt = (0 until 8).joinToString("  ") { gi -> "${'A' + gi}:${if (sc[gi] >= 0) "${sc[gi] + 1}" else "–"}" }
                l.drawText(txt, 16f, 100f, label)
            }
            l.drawText("Empty pad: new scene from the patterns playing", 16f, 196f, label)
            l.drawText("ERASE + pad delete · DUPLICATE + pad + pad", 16f, 216f, label)
        }
        if (message.isNotEmpty()) l.drawText(message.take(60), 16f, 160f, ok)
        // slot grid in pad layout (pad 13–16 on top)
        val w = 112f
        val h = 52f
        val col = Mk3Colors.rgb(if (kind == Kind.PATTERN) groupColour(g) else Mk3Colors.WHITE)
        val fill = Theme.fill(col)
        val dim = Theme.fill(android.graphics.Color.rgb(android.graphics.Color.red(col) / 3 + 12, android.graphics.Color.green(col) / 3 + 12, android.graphics.Color.blue(col) / 3 + 16))
        r.drawText("BANK ${bank + 1}/$banks", 380f, 270f, cellDim)
        for (pad in 0 until 16) {
            val i = s(pad)
            val c = pad % 4
            val row = 3 - pad / 4
            val x = 8f + c * (w + 4f)
            val y = 38f + row * (h + 4f)
            val rect = RectF(x, y, x + w, y + h)
            r.drawRoundRect(rect, 5f, 5f, when { isCurrent(i) -> fill; has(i) -> dim; else -> empty })
            if (isQueued(i) && (now / 200) % 2 == 0L) r.drawRoundRect(rect, 5f, 5f, ring)
            r.drawText(name(i), x + w / 2, y + 24f, if (has(i)) cellName else cellDim)
            if (kind == Kind.PATTERN && has(i)) r.drawText(lengthText(p.patterns[g][i]!!.length), x + w / 2, y + 42f, cellDim)
        }
    }
}

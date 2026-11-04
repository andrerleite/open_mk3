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
 * SCENE: D2 UNIQUE (the current scene gets its own copies of its patterns), SHIFT + D2 clear
 * (no patterns), D3 APPEND (pads add their scene to the end of the song), D4 update the scene
 * with the patterns playing now, D5 create, D6 delete.
 * Both: SHIFT + D5 names the current pattern / scene; SHIFT + D6 picks its colour (the pads show
 * the 16 colours, hit one; SHIFT + D6 again clears it back to the group's colour).
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
    /** Picking the current pattern's / scene's colour on the pads. */
    private var picking = false
    /** SCENE, D3 APPEND: pads add scenes to the end of the song. */
    private var appending = false

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

    /** Colour of slot [i] (its own colour, else the group's / white). */
    private fun colourOf(i: Int): Int {
        val own = if (kind == Kind.PATTERN) p.patterns[g][i]?.colour ?: 0 else p.sceneColours[i]
        return if (own > 0) own else if (kind == Kind.PATTERN) groupColour(g) else Mk3Colors.WHITE
    }

    private fun currentIdx() = if (kind == Kind.PATTERN) p.current[g] else p.currentScene
    private fun ownColour(): Int = currentIdx().let { cur -> if (cur < 0) 0 else if (kind == Kind.PATTERN) p.patterns[g][cur]?.colour ?: 0 else p.sceneColours[cur] }
    private fun setOwnColour(c: Int) {
        val cur = currentIdx(); if (cur < 0) return
        if (kind == Kind.PATTERN) p.patterns[g][cur]?.colour = c else p.sceneColours[cur] = c
    }

    private fun updatePads(now: Long) {
        if (picking) {
            for (i in 0 until 16) ui.padLed(i, Mk3Colors.value(i + 1, if (i + 1 == ownColour()) 3 else 1))
            return
        }
        val blink = (now / 200) % 2 == 0L
        for (i in 0 until 16) {
            val i = s(i)
            val c = colourOf(i)
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
        l.set("SETTINGS", "MIXER", "FILE")
        l.set("D7", bank > 0); l.set("D8", bank < banks - 1)
        if (kind == Kind.SCENE) { l.set("D2"); l.set("D3", appending); l.set("D4") }
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (picking) {
            setOwnColour(slot + 1)
            picking = false
            message = "${name(currentIdx())} colour ${slot + 1}"
            changed()
            return
        }
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
                    else p.scenes[dupSource]?.let { p.scenes[i] = it.copyOf(); p.sceneNames[i] = p.sceneNames[dupSource]; p.sceneColours[i] = p.sceneColours[dupSource] }
                    message = "${name(dupSource)} → ${name(i)}"
                    dupSource = -1
                }
            }
            kind == Kind.SCENE && appending -> {
                if (!has(i)) p.captureScene(i)
                p.song.add(i)
                message = "${slotTitle(i)} appended as section ${p.song.size}"
            }
            kind == Kind.PATTERN -> {
                val created = !has(i)
                p.selectPattern(g, i, playing)
                message = if (created) "New pattern ${name(i)}" else if (playing && p.queued[g] == i) "${name(i)} starts at the end of the pattern" else "${name(i)}"
            }
            else -> {
                if (!has(i)) { p.captureScene(i); message = "New scene ${name(i)} (patterns playing now)" }
                else if (p.songMode && p.song.indices.any { p.sceneOf(it) == i }) {
                    // song mode: jump to that scene's section (at the end of the playing one)
                    val sec = p.song.indices.first { p.sceneOf(it) == i }
                    if (playing) p.songJump = sec else p.songPos = sec
                    message = "Section ${sec + 1} (scene ${i + 1})"
                }
                else { p.launchScene(i, playing); message = "Scene ${i + 1}" }
            }
        }
        changed()
    }

    private fun name(i: Int) = if (kind == Kind.PATTERN) "${ui.groupLetter}${i + 1}" else "Scene ${i + 1}"
    /** The name given to slot [i], or its number. */
    private fun slotTitle(i: Int): String = (if (kind == Kind.PATTERN) p.patterns[g].getOrNull(i)?.name else p.sceneNames.getOrNull(i))?.ifEmpty { null } ?: name(i)

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
            "D5" -> if (shift) { if (currentIdx() >= 0) ui.show(FilePage(ui, if (kind == Kind.PATTERN) 3 else 4, this)) else message = "No ${if (kind == Kind.PATTERN) "pattern" else "scene"} to name" } else createNext()
            "D6" -> if (shift) {
                if (currentIdx() < 0) message = "No ${if (kind == Kind.PATTERN) "pattern" else "scene"} selected"
                else if (picking) { picking = false; setOwnColour(0); message = "${name(currentIdx())}: ${if (kind == Kind.PATTERN) "group colour" else "no colour"}"; changed() }
                else { picking = true; message = "Hit a pad for the colour (SHIFT + D6 again: ${if (kind == Kind.PATTERN) "group colour" else "none"})" }
            } else deleteCurrent()
            "D7" -> if (bank > 0) bank--
            "D8" -> if (bank < banks - 1) bank++
            "D2" -> if (kind == Kind.SCENE) { if (shift) clearScene() else makeUnique() }
            "D3" -> if (kind == Kind.SCENE) { appending = !appending; message = if (appending) "APPEND: hit scene pads to add them to the end of the song" else "" }
            "D4" -> if (kind == Kind.SCENE && p.currentScene >= 0) { p.captureScene(p.currentScene); message = "${slotTitle(p.currentScene)} updated"; changed() }
            "BROWSER", "SAMPLING", "PLUG-IN", "SETTINGS", "MIXER", "FILE" -> back()
        }
    }

    /** SCENE D2 UNIQUE: the current scene's patterns are copied to free slots so editing them leaves other scenes alone. */
    private fun makeUnique() {
        val cur = p.currentScene
        val sc = p.scenes.getOrNull(cur) ?: run { message = "No scene selected"; return }
        var copies = 0
        for (gi in 0 until Pattern.GROUPS) {
            val src = sc[gi]
            if (src < 0 || p.patterns[gi][src] == null) continue
            val free = (0 until Pattern.SLOTS).firstOrNull { p.patterns[gi][it] == null } ?: run { message = "Group ${'A' + gi} has no free slot"; return }
            p.duplicatePattern(gi, src, free)
            sc[gi] = free
            if (p.current[gi] == src) p.selectPattern(gi, free, playing)
            copies++
        }
        message = if (copies == 0) "${slotTitle(cur)} has no patterns" else "${slotTitle(cur)} is unique ($copies new pattern${if (copies > 1) "s" else ""})"
        changed()
    }

    /** SCENE SHIFT + D2: the current scene keeps its slot but plays no patterns. */
    private fun clearScene() {
        val cur = p.currentScene
        val sc = p.scenes.getOrNull(cur) ?: run { message = "No scene selected"; return }
        sc.fill(-1)
        message = "${slotTitle(cur)} cleared"
        changed()
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
        if (kind == Kind.SCENE && knob == 0) {  // K1 RETRIGGER
            lenAcc += delta
            if (kotlin.math.abs(lenAcc) < 25) return
            p.retrigger = lenAcc > 0
            lenAcc = 0
            message = "Retrigger ${if (p.retrigger) "on: scenes start from the top" else "off: scenes carry on in time"}"
            p.save(ui.ctx)
            return
        }
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
        if (picking) {
            val cur = currentIdx()
            l.drawText("COLOUR · ${slotTitle(cur)}", 16f, 52f, title)
            l.drawText("Hit a pad to pick its colour", 16f, 92f, label)
            l.drawText("SHIFT + D6: ${if (kind == Kind.PATTERN) "back to the group colour" else "no colour"}", 16f, 114f, label)
            for (pad in 0 until 16) {
                val x = 8f + (pad % 4) * 116f; val y = 38f + (3 - pad / 4) * 56f
                val rect = RectF(x, y, x + 112f, y + 52f)
                r.drawRoundRect(rect, 5f, 5f, Theme.fill(Mk3Colors.rgb(pad + 1)))
                if (pad + 1 == ownColour()) r.drawRoundRect(rect, 5f, 5f, ring)
            }
            return
        }
        val pinName = if (kind == Kind.PATTERN) "PATTERN" else "SCENE"
        l.drawText("PIN", 60f, 24f, if (pinName in ui.pinned) cellName else dLabel)
        if (kind == Kind.SCENE) {
            l.drawText(if (shift) "CLEAR" else "UNIQUE", 180f, 24f, dLabel)
            l.drawText("APPEND", 300f, 24f, if (appending) cellName else dLabel)
            l.drawText("UPDATE", 420f, 24f, dLabel)
        }
        r.drawText(if (shift) "NAME" else "CREATE", 60f, 24f, dLabel)
        r.drawText(if (shift) "COLOUR" else "DELETE", 180f, 24f, dLabel)
        r.drawText("◀ BANK", 300f, 24f, dLabel)
        r.drawText("BANK ▶", 420f, 24f, dLabel)
        if (kind == Kind.PATTERN) {
            l.drawText("PATTERN · GROUP ${ui.groupLetter}", 16f, 52f, title)
            val cur = p.current[g]
            l.drawText(if (cur >= 0) slotTitle(cur) + (if (slotTitle(cur) != name(cur)) "  (${name(cur)})" else "") else "No pattern", 16f, 82f, big)
            l.drawText("Length ${lengthText(p.length(g))}", 16f, 100f, label)
            if (p.queued[g] >= 0) l.drawText("Next: ${ui.groupLetter}${p.queued[g] + 1} (at the end of this one)", 16f, 124f, ok)
            l.drawText("K4 length (SHIFT: steps) · ERASE + pad delete", 16f, 196f, label)
            l.drawText("DUPLICATE + pad + pad · GROUP: other group", 16f, 216f, label)
        } else {
            l.drawText("SCENE", 16f, 52f, title)
            val cur = p.currentScene
            l.drawText(if (cur >= 0) slotTitle(cur) + (if (slotTitle(cur) != name(cur)) "  (${name(cur)})" else "") else "No scene", 16f, 82f, big)
            p.scenes.getOrNull(cur)?.let { sc ->
                val txt = (0 until 8).joinToString("  ") { gi -> "${'A' + gi}:${if (sc[gi] >= 0) "${sc[gi] + 1}" else "–"}" }
                l.drawText(txt, 16f, 100f, label)
            }
            val grid = when (val pg = p.performGrid) { 0 -> "at the scene's end"; -1 -> "at once"; 16 -> "on the next bar"; else -> "on the next 1/${16 / pg}" }
            l.drawText("K1 RETRIGGER ${if (p.retrigger) "ON" else "OFF"} · switches $grid", 16f, 124f, label)
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
            val ci = Mk3Colors.rgb(colourOf(i))
            val fillI = if (ci == col) fill else Theme.fill(ci)
            val dimI = if (ci == col) dim else Theme.fill(android.graphics.Color.rgb(android.graphics.Color.red(ci) / 3 + 12, android.graphics.Color.green(ci) / 3 + 12, android.graphics.Color.blue(ci) / 3 + 16))
            r.drawRoundRect(rect, 5f, 5f, when { isCurrent(i) -> fillI; has(i) -> dimI; else -> empty })
            if (isQueued(i) && (now / 200) % 2 == 0L) r.drawRoundRect(rect, 5f, 5f, ring)
            r.drawText(if (has(i)) slotTitle(i).take(11) else name(i), x + w / 2, y + 24f, if (has(i)) cellName else cellDim)
            if (kind == Kind.PATTERN && has(i)) r.drawText(lengthText(p.patterns[g][i]!!.length), x + w / 2, y + 42f, cellDim)
        }
    }
}

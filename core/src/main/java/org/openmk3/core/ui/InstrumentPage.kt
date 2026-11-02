package org.openmk3.core.ui

import android.graphics.Canvas
import org.openmk3.core.AppLog
import org.openmk3.core.audio.PolyPresets
import org.openmk3.core.audio.SoundParams

/**
 * SHIFT + BROWSER: instruments and presets for the focused sound.
 *  POLY (OB-Xf factory patches by category) · FM (DX7-format banks) · SYNTH (Plaits models)
 *  Encoder turn = move · push / ▶ = open or load (the sound switches to that source and plays a
 *  note) · ◀ = back a level · PADS = choose the sound · BROWSER = done
 */
class InstrumentPage(ui: Ui) : Page(ui) {

    override val playsPads = true

    private sealed class Item(val label: String) {
        class Level(label: String, val open: () -> List<Item>) : Item(label)
        class Load(label: String, val load: () -> Unit) : Item(label)
    }

    private companion object { val path = ArrayList<Int>() }

    private val params = ui.engine.params
    private val snd get() = ui.selectedIndex
    private var stack = mutableListOf<Pair<String, List<Item>>>()
    private var sel = 0
    private var message = ""

    private val title = Theme.text(20f, Theme.ACCENT, bold = true)
    private val pathText = Theme.text(14f, Theme.DIM)
    private val row = Theme.text(15f, Theme.FG)
    private val rowDir = Theme.text(15f, Theme.ACCENT)
    private val label = Theme.text(14f, Theme.DIM)
    private val ok = Theme.text(15f, Theme.OK)
    private val big = Theme.text(20f, Theme.FG, bold = true)
    private val selFill = Theme.fill(android.graphics.Color.rgb(60, 60, 80))

    private fun root(): List<Item> = listOf(
        Item.Level("POLY (OB-Xf)") {
            PolyPresets.categories(ui.ctx).map { cat ->
                Item.Level(cat) {
                    PolyPresets.list(ui.ctx).filter { it.category == cat }.map { p ->
                        Item.Load(p.name) {
                            params[snd, SoundParams.SRC] = SoundParams.SRC_POLY.toFloat()
                            ui.engine.poly.apply(snd, p)
                            ui.engine.poly.save(ui.ctx)
                            done("POLY · ${p.name}" + if (p.author.isNotEmpty()) " (${p.author})" else "")
                        }
                    }
                }
            }
        },
        Item.Level("FM (6-operator)") {
            SoundParams.FM_BANKS.indices.mapNotNull { b ->
                val names = (0 until 32).map { params.fmPatchName?.invoke(b, it)?.trim().orEmpty() }
                if (names.all { it.isEmpty() }) null
                else Item.Level(SoundParams.FM_BANKS[b]) {
                    names.mapIndexed { i, n ->
                        Item.Load("${i + 1} ${n.ifEmpty { "—" }}") {
                            params[snd, SoundParams.SRC] = SoundParams.SRC_FM.toFloat()
                            params[snd, SoundParams.FMBANK] = b.toFloat()
                            params[snd, SoundParams.FMPROG] = i.toFloat()
                            done("FM · ${SoundParams.FM_BANKS[b]} ${i + 1} $n")
                        }
                    }
                }
            }
        },
        Item.Level("SYNTH (Plaits models)") {
            SoundParams.MODELS.mapIndexed { i, m ->
                Item.Load(m) {
                    params[snd, SoundParams.SRC] = SoundParams.SRC_SYNTH.toFloat()
                    params[snd, SoundParams.SMODEL] = i.toFloat()
                    done("SYNTH · $m")
                }
            }
        },
    )

    init {
        stack.add("INSTRUMENTS" to root())
    }

    private val items get() = stack.last().second

    private fun done(msg: String) {
        params.save(ui.ctx)
        message = "${ui.padLabel()}: $msg"
        AppLog.i("Instrument: $message")
        ui.engine.noteOn(snd, 60, 0.8f)
        noteOffAt = android.os.SystemClock.uptimeMillis() + 600
    }

    private var noteOffAt = 0L
    override fun tick(now: Long): Boolean {
        if (noteOffAt != 0L && now >= noteOffAt) { ui.engine.noteOff(snd, 60); noteOffAt = 0L }
        return false
    }

    private fun open() {
        when (val it = items.getOrNull(sel)) {
            is Item.Level -> { stack.add(it.label to it.open()); sel = 0 }
            is Item.Load -> it.load()
            null -> {}
        }
    }

    private fun back() {
        if (stack.size > 1) {
            val left = stack.removeAt(stack.size - 1).first
            sel = items.indexOfFirst { it.label == left }.coerceAtLeast(0)
        }
    }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (!down) return
        when (name) {
            "ENCODER PUSH", "ENCODER RIGHT", "PAGE RIGHT" -> open()
            "ENCODER LEFT", "PAGE LEFT" -> back()
            "ENCODER UP" -> sel = (sel - 1).coerceAtLeast(0)
            "ENCODER DOWN" -> sel = (sel + 1).coerceAtMost(maxOf(0, items.size - 1))
            "BROWSER" -> ui.showHome()
        }
    }

    override fun onEncoder(delta: Int) {
        sel = (sel + delta).coerceIn(0, maxOf(0, items.size - 1))
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (ui.padMode == Ui.PadMode.PAD) ui.selectedSound = slot
    }

    override fun lights(l: Lights) {
        l.set("BROWSER", true)
        l.set("PAGE LEFT", stack.size > 1)
        l.set("PAGE RIGHT")
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        l.drawText("INSTRUMENTS", 12f, 24f, title)
        l.drawText(stack.drop(1).joinToString(" / ") { it.first }.takeLast(40), 170f, 23f, pathText)
        val rows = 10
        val list = items
        val top = (sel - rows / 2).coerceIn(0, maxOf(0, list.size - rows))
        for (i in 0 until rows) {
            val idx = top + i
            val it = list.getOrNull(idx) ?: break
            val y = 36f + i * 23f
            if (idx == sel) l.drawRect(0f, y, 480f, y + 23f, selFill)
            l.drawText((if (it is Item.Level) it.label + "/" else it.label).take(46), 12f, y + 17f, if (it is Item.Level) rowDir else row)
        }
        r.drawText("SOUND ${ui.padLabel()}", 16f, 40f, label)
        val src = SoundParams.DEFS[SoundParams.SRC]?.choices?.getOrNull(params[snd, SoundParams.SRC].toInt()) ?: ""
        r.drawText(src + if (params[snd, SoundParams.SRC].toInt() == SoundParams.SRC_POLY) " · ${ui.engine.poly.patchName[snd]}" else "", 16f, 68f, big)
        if (message.isNotEmpty()) r.drawText(message.take(48), 16f, 110f, ok)
        r.drawText("ENC: move · push: open / load · ◀: back", 16f, 226f, label)
        r.drawText("PADS: sound · BROWSER: done", 16f, 246f, label)
    }
}

package org.openmk3.core.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import org.openmk3.core.audio.PolyPresets
import org.openmk3.core.audio.Sample
import org.openmk3.core.audio.SoundParams
import org.openmk3.core.mk3.Mk3Colors
import org.openmk3.core.audio.SoundParams.Companion.TUNE
import org.openmk3.core.audio.SoundParams.Companion.VOL
import org.openmk3.core.audio.SoundParams.Companion.PAN
import org.openmk3.core.audio.SoundParams.Companion.START
import org.openmk3.core.audio.SoundParams.Companion.END
import org.openmk3.core.audio.SoundParams.Companion.REV
import org.openmk3.core.audio.SoundParams.Companion.ENV
import org.openmk3.core.audio.SoundParams.Companion.ATT
import org.openmk3.core.audio.SoundParams.Companion.HOLD
import org.openmk3.core.audio.SoundParams.Companion.DEC
import org.openmk3.core.audio.SoundParams.Companion.FTYPE
import org.openmk3.core.audio.SoundParams.Companion.CUT
import org.openmk3.core.audio.SoundParams.Companion.RES
import org.openmk3.core.audio.SoundParams.Companion.SUS
import org.openmk3.core.audio.SoundParams.Companion.REL
import org.openmk3.core.audio.SoundParams.Companion.POLY
import org.openmk3.core.audio.SoundParams.Companion.ENGINE
import org.openmk3.core.audio.SoundParams.Companion.LOOP
import org.openmk3.core.audio.SoundParams.Companion.LSTART
import org.openmk3.core.audio.SoundParams.Companion.LEND
import org.openmk3.core.audio.SoundParams.Companion.XFADE
import org.openmk3.core.audio.SoundParams.Companion.DRIVE
import org.openmk3.core.audio.SoundParams.Companion.MATT
import org.openmk3.core.audio.SoundParams.Companion.MDEC
import org.openmk3.core.audio.SoundParams.Companion.MPITCH
import org.openmk3.core.audio.SoundParams.Companion.MCUT
import org.openmk3.core.audio.SoundParams.Companion.LWAVE
import org.openmk3.core.audio.SoundParams.Companion.LRATE
import org.openmk3.core.audio.SoundParams.Companion.LPITCH
import org.openmk3.core.audio.SoundParams.Companion.LCUT
import org.openmk3.core.audio.SoundParams.Companion.LPAN
import org.openmk3.core.audio.SoundParams.Companion.LVOL
import org.openmk3.core.audio.SoundParams.Companion.VVOL
import org.openmk3.core.audio.SoundParams.Companion.VCUT
import org.openmk3.core.audio.SoundParams.Companion.GATE
import org.openmk3.core.audio.SoundParams.Companion.SRC
import org.openmk3.core.audio.SoundParams.Companion.SMODEL
import org.openmk3.core.audio.SoundParams.Companion.SNOTE
import org.openmk3.core.audio.SoundParams.Companion.SHARM
import org.openmk3.core.audio.SoundParams.Companion.STIMB
import org.openmk3.core.audio.SoundParams.Companion.SMORPH
import org.openmk3.core.audio.SoundParams.Companion.SDEC
import org.openmk3.core.audio.SoundParams.Companion.SCOL
import org.openmk3.core.audio.SoundParams.Companion.SLPG
import org.openmk3.core.audio.SoundParams.Companion.SAUX
import org.openmk3.core.audio.SoundParams.Companion.SFM
import org.openmk3.core.audio.SoundParams.Companion.STMOD
import org.openmk3.core.audio.SoundParams.Companion.SMMOD
import org.openmk3.core.audio.FxParams
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * PLUG-IN: the plug-in chains of the master, the focused group and the focused sound,
 * following the hardware workflow.
 *
 *  D1 MASTER · D2 GROUP · D3 SOUND (level) · D4 next slot · D5 bypass the effect
 *  D6 oscilloscope · D8 zero-crossing snap (sampler SAMPLE page)
 *  ◀ ▶ = parameter pages · knobs = parameters (SHIFT = fine, SHIFT + touch = default)
 *  Sound slot 1 is the source (SAMPLER or SYNTH); sound slots 2–4 and every group / master
 *  slot hold an effect (K1 on the first page chooses it).
 *  Encoder: zoom the waveform (or the scope's time) · tilt: scroll · PADS: focus and play
 */
/** Opens the PLUG-IN page on return [a] (0 = A, 1 = B) next time it is shown. */
object SoundPageNav {
    @Volatile var openReturn = -1
}

class SoundPage(ui: Ui) : Page(ui) {

    override val playsPads = true

    private enum class View { WAVE, AMP, FILTER, MOD, LFO, VEL, SCOPE }

    /** A knob: a sound parameter, an effect parameter, or the effect type. */
    private sealed class Knob {
        class Snd(val p: Int) : Knob()
        class Fx(val d: FxParams.Def) : Knob()
        object Type : Knob()
        /** POLY source parameter (OB-Xf index). */
        class Poly(val i: Int) : Knob()
        object PolyCategory : Knob()
        object PolyPreset : Knob()
    }

    private class PageDef(val name: String, val knobs: List<Knob?>, val view: View)

    private companion object {
        fun snd(vararg p: Int) = p.map { if (it < 0) null else Knob.Snd(it) }
        val SAMPLER = listOf(
            PageDef("VOICE", snd(VOL, PAN, TUNE, POLY, SRC, GATE, REV, ENGINE), View.WAVE),
            PageDef("SAMPLE", snd(START, END, -1, -1, LOOP, LSTART, LEND, XFADE), View.WAVE),
            PageDef("AMP ENV", snd(ENV, ATT, HOLD, DEC, SUS, REL, -1, -1), View.AMP),
            PageDef("FILTER", snd(FTYPE, CUT, RES, DRIVE, -1, -1, -1, -1), View.FILTER),
            PageDef("MOD ENV", snd(MATT, MDEC, MPITCH, MCUT, -1, -1, -1, -1), View.MOD),
            PageDef("LFO", snd(LWAVE, LRATE, LPITCH, LCUT, LPAN, LVOL, -1, -1), View.LFO),
            PageDef("VELOCITY", snd(VVOL, VCUT, -1, -1, -1, -1, -1, -1), View.VEL),
        )
        val SYNTH = listOf(
            PageDef("VOICE", snd(VOL, PAN, TUNE, POLY, SRC, GATE, SNOTE, -1), View.SCOPE),
            PageDef("SYNTH", snd(SMODEL, SHARM, STIMB, SMORPH, SLPG, SDEC, SCOL, SAUX), View.SCOPE),
            PageDef("SYNTH ENV", snd(SFM, STMOD, SMMOD, -1, -1, -1, -1, -1), View.SCOPE),
            PageDef("AMP ENV", snd(ENV, ATT, HOLD, DEC, SUS, REL, -1, -1), View.AMP),
            PageDef("FILTER", snd(FTYPE, CUT, RES, DRIVE, -1, -1, -1, -1), View.FILTER),
            PageDef("MOD ENV", snd(MATT, MDEC, MPITCH, MCUT, -1, -1, -1, -1), View.MOD),
            PageDef("LFO", snd(LWAVE, LRATE, LPITCH, LCUT, LPAN, LVOL, -1, -1), View.LFO),
            PageDef("VELOCITY", snd(VVOL, VCUT, -1, -1, -1, -1, -1, -1), View.VEL),
        )
        val FM = listOf(
            PageDef("VOICE", snd(VOL, PAN, TUNE, POLY, SRC, -1, -1, -1), View.SCOPE),
            PageDef("FM", snd(SoundParams.FMBANK, SoundParams.FMPROG, SoundParams.FMBRIGHT, SoundParams.FMATT, SoundParams.FMREL, SoundParams.FMFB, -1, -1), View.SCOPE),
            PageDef("AMP ENV", snd(ENV, ATT, HOLD, DEC, SUS, REL, -1, -1), View.AMP),
            PageDef("FILTER", snd(FTYPE, CUT, RES, DRIVE, -1, -1, -1, -1), View.FILTER),
            PageDef("MOD ENV", snd(MATT, MDEC, MPITCH, MCUT, -1, -1, -1, -1), View.MOD),
            PageDef("LFO", snd(LWAVE, LRATE, LPITCH, LCUT, LPAN, LVOL, -1, -1), View.LFO),
            PageDef("VELOCITY", snd(VVOL, VCUT, -1, -1, -1, -1, -1, -1), View.VEL),
        )
        fun ob(vararg i: Int) = i.map { if (it < 0) null else Knob.Poly(it) }
        /** POLY (OB-Xf): OB-Xf's parameter indices, grouped like its panel. */
        val POLY_PAGES = listOf(
            PageDef("VOICE", listOf(Knob.PolyCategory, Knob.PolyPreset, Knob.Snd(VOL), Knob.Snd(PAN), Knob.Snd(SRC), Knob.Snd(TUNE), null, null), View.SCOPE),
            PageDef("OSC", ob(12, 14, 13, 20, 16, 17, 18, 19), View.SCOPE),
            PageDef("OSC MOD", ob(29, 28, 30, 21, 22, 25, 15, 23), View.SCOPE),
            PageDef("MIXER", ob(31, 32, 33, 34, 35, 0, 1, 2), View.SCOPE),
            PageDef("FILTER", ob(41, 42, 43, 44, 45, 48, 49, 50), View.SCOPE),
            PageDef("FILTER ENV", ob(79, 80, 81, 82, 83, 84, 85, 46), View.SCOPE),
            PageDef("AMP ENV", ob(86, 87, 88, 89, 90, 91, 6, 47), View.SCOPE),
            PageDef("LFO 1", ob(52, 55, 56, 57, 53, 54, 59, 61), View.SCOPE),
            PageDef("LFO 1 MORE", ob(51, 58, 60, 62, 63, 64, -1, -1), View.SCOPE),
            PageDef("LFO 2", ob(66, 69, 70, 71, 67, 68, 73, 75), View.SCOPE),
            PageDef("LFO 2 MORE", ob(65, 72, 74, 76, 77, 78, -1, -1), View.SCOPE),
            PageDef("VOICING", ob(3, 7, 5, 8, 9, 10, 11, 4), View.SCOPE),
            PageDef("CONTROL", ob(36, 37, 38, 39, 40, 24, 26, 27), View.SCOPE),
            PageDef("SLOP", ob(92, 93, 94, 95, -1, -1, -1, -1), View.SCOPE),
            PageDef("VOICE PAN", ob(96, 97, 98, 99, 100, 101, 102, 103), View.SCOPE),
        )
        var polyCat = 0
        val MARKERS = setOf(START, END, LSTART, LEND)
        var lastLevel = 2
        var lastSlot = 0
        var lastPage = 0
        var scopeOn = false
        var scopeWindow = 1024
    }

    private var level = SoundPageNav.openReturn.let { if (it >= 0) 3 + it else lastLevel }  // 0 master, 1 group, 2 sound, 3 / 4 returns
    private var slotIdx = if (SoundPageNav.openReturn >= 0) 0 else lastSlot       // sound: 0 = source, 1..3 = effects; group / master: 0..3
    private var pageIdx = if (SoundPageNav.openReturn >= 0) 0.also { SoundPageNav.openReturn = -1 } else lastPage
    private var shift = false
    private var dirtySince = 0L
    private var fxDirtySince = 0L
    private val params = ui.engine.params
    private val fx = ui.engine.fx
    /** Sound index (0..127) of the focused pad. */
    private val slot get() = ui.selectedIndex
    private val waveView = WaveView()
    private val rawMarker = HashMap<Int, Float>()
    private var focusFrame = 0
    private val scopeBuf = FloatArray(8192)

    private val slotCount get() = if (level == 2) 1 + FxParams.SOUND_FX_SLOTS else FxParams.SLOTS
    private val isSource get() = level == 2 && slotIdx == 0
    private val source get() = params[slot, SRC].toInt()
    private val isSynth get() = params[slot, SRC] > 0.5f
    /** Effect chain index and slot for the current selection (not the sound source). */
    private val chain get() = when (level) { 0 -> FxParams.MASTER; 1 -> FxParams.GROUP0 + ui.group; 3, 4 -> FxParams.RETURN0 + level - 3; else -> slot }
    private val fxSlot get() = if (level == 2) slotIdx - 1 else slotIdx

    private fun pages(): List<PageDef> {
        if (isSource) return when (source) { SoundParams.SRC_POLY -> POLY_PAGES; SoundParams.SRC_FM -> FM; SoundParams.SRC_SYNTH -> SYNTH; else -> SAMPLER }
        val type = fx.type(chain, fxSlot)
        val defs = FxParams.DEFS[type] ?: emptyList()
        val name = FxParams.TYPES[type]
        val first = listOf<Knob?>(Knob.Type) + defs.take(7).map { Knob.Fx(it) }
        val out = mutableListOf(PageDef(name, first + List(8 - first.size) { null }, View.SCOPE))
        var rest = defs.drop(7)
        var n = 2
        while (rest.isNotEmpty()) {
            val k = rest.take(8).map { Knob.Fx(it) as Knob? }
            out.add(PageDef("$name $n", k + List(8 - k.size) { null }, View.SCOPE))
            rest = rest.drop(8)
            n++
        }
        return out
    }

    private val page get() = pages().let { it[pageIdx.coerceIn(0, it.size - 1)] }

    private val tab = Theme.text(15f, Theme.DIM, bold = true, align = Paint.Align.CENTER)
    private val tabOn = Theme.text(15f, Theme.ACCENT, bold = true, align = Paint.Align.CENTER)
    private val strip = Theme.text(13f, Theme.DIM, align = Paint.Align.LEFT)
    private val stripOn = Theme.text(13f, Theme.ACCENT, bold = true, align = Paint.Align.LEFT)
    private val kName = Theme.text(13f, Theme.DIM, align = Paint.Align.CENTER)
    private val kValue = Theme.text(16f, Theme.FG, bold = true, align = Paint.Align.CENTER)
    private val kOff = Theme.text(16f, Theme.DIM, bold = false, align = Paint.Align.CENTER)
    private val title = Theme.text(18f, Theme.ACCENT, bold = true)
    private val big = Theme.text(20f, Theme.FG, bold = true)
    private val label = Theme.text(14f, Theme.DIM)
    private val slotText = Theme.text(15f, Theme.FG)
    private val slotDim = Theme.text(15f, Theme.DIM)
    private val selFill = Theme.fill(Color.rgb(60, 60, 80))
    private val sep = Theme.fill(Color.rgb(50, 50, 60))
    private val barBg = Theme.fill(Theme.CELL)
    private val barFg = Theme.fill(Theme.ACCENT)
    private val grFill = Theme.fill(Theme.WARN)
    private val curve = Theme.stroke(Theme.ACCENT, 2f)
    private val grid = Theme.stroke(Color.rgb(50, 50, 60), 1f)

    override fun onEnter() {
        updateScopeTap()
    }

    override fun onExit() {
        lastLevel = level; lastSlot = slotIdx; lastPage = pageIdx
        if (dirtySince != 0L) save()
        if (fxDirtySince != 0L) { fx.save(ui.ctx); fxDirtySince = 0L }
        if (presetAt != 0L) applyPreset()
        if (polyDirtySince != 0L) savePoly()
        ui.engine.setScopeTap(0, 0)
    }

    private fun changed() { if (dirtySince == 0L) dirtySince = SystemClock.uptimeMillis() }
    private fun fxChanged() { if (fxDirtySince == 0L) fxDirtySince = SystemClock.uptimeMillis() }

    private fun save() {
        params.save(ui.ctx)
        dirtySince = 0L
    }

    private fun savePoly() {
        poly.save(ui.ctx)
        polyDirtySince = 0L
    }

    private fun updateScopeTap() = when (level) {
        0, 3, 4 -> ui.engine.setScopeTap(0, 0)
        1 -> ui.engine.setScopeTap(1, ui.group)
        else -> ui.engine.setScopeTap(2, slot)
    }

    private fun showScope() = scopeOn || page.view == View.SCOPE

    private var lastHead = -2f

    override fun tick(now: Long): Boolean {
        if (dirtySince != 0L && now - dirtySince > 1500) save()
        if (fxDirtySince != 0L && now - fxDirtySince > 1500) { fx.save(ui.ctx); fxDirtySince = 0L }
        if (presetAt != 0L && now - presetAt > 250) applyPreset()
        if (polyDirtySince != 0L && now - polyDirtySince > 1500) savePoly()
        updateScopeTap()
        if (showScope()) return true  // live view
        val h = if (page.view == View.WAVE) ui.engine.playPos(slot) else -1f
        if (h != lastHead) { lastHead = h; return true }
        return false
    }

    private fun resetPage() { pageIdx = 0; rawMarker.clear() }

    override fun onButton(name: String?, bit: Int, down: Boolean) {
        if (name == "SHIFT") { shift = down; return }
        if (!down) return
        val e = ui.engine
        val n = pages().size
        when (name) {
            // D1: MASTER, pressed again RETURN A, then RETURN B
            "D1" -> { level = when (level) { 0 -> 3; 3 -> 4; else -> 0 }; slotIdx = 0; resetPage() }
            "D2" -> { level = 1; slotIdx = 0; resetPage() }
            "D3" -> { level = 2; slotIdx = 0; resetPage() }
            "D4" -> { slotIdx = (slotIdx + 1) % slotCount; resetPage() }
            "D5" -> if (!isSource && fx.type(chain, fxSlot) != FxParams.NONE) {
                fx[chain, fxSlot, 1] = if (fx.bypassed(chain, fxSlot)) 0f else 1f
                fxChanged()
            }
            "D6", "ENCODER PUSH" -> scopeOn = !scopeOn
            "D8" -> if (isSource && !isSynth && pageIdx == 1) { ui.zeroSnap = !ui.zeroSnap; rawMarker.clear() }
            "ENCODER LEFT" -> if (page.view == View.WAVE && !scopeOn) waveView.scroll(-1)
            "ENCODER RIGHT" -> if (page.view == View.WAVE && !scopeOn) waveView.scroll(1)
            "PAGE LEFT" -> pageIdx = (pageIdx + n - 1) % n
            "PAGE RIGHT" -> pageIdx = (pageIdx + 1) % n
            "PLAY" -> if (e.playing) e.transportStop() else e.transportPlay()
            "STOP" -> e.stopPressed()
            "RESTART" -> e.transportRestart()
            "PLUG-IN" -> ui.showHome()
            "BROWSER" -> ui.show(BrowserPage(ui))
            "SAMPLING" -> ui.show(SamplingPage(ui))
        }
    }

    override fun lights(l: Lights) {
        l.set("PLUG-IN", true)
        l.set("PLAY", ui.engine.playing)
        l.set("SHIFT", shift)
        l.set("STOP", "RESTART", "PAGE LEFT", "PAGE RIGHT", "BROWSER", "SAMPLING")
        l.set("D1", level == 0 || level >= 3)
        l.set("D2", level == 1)
        l.set("D3", level == 2)
        l.set("D4")
        if (!isSource && fx.type(chain, fxSlot) != FxParams.NONE) l.set("D5", fx.bypassed(chain, fxSlot))
        l.set("D6", scopeOn)
        if (isSource && !isSynth && pageIdx == 1) l.set("D8", ui.zeroSnap)
    }

    override fun onPadHit(slot: Int, hid: Int, velocity: Float, pressure: Int) {
        if (ui.padMode != Ui.PadMode.PAD) return  // KEYBOARD / CHORDS / 16 VEL: pads only play notes
        val wasSource = source
        ui.selectedSound = slot
        rawMarker.clear()
        if (isSource && wasSource != source) pageIdx = 0
    }

    override fun onKnob(knob: Int, delta: Int) {
        when (val k = page.knobs.getOrNull(knob)) {
            is Knob.Snd -> soundKnob(k.p, delta)
            is Knob.Fx -> { fx.nudge(chain, fxSlot, k.d, delta, shift); fxChanged() }
            Knob.Type -> typeKnob(delta)
            is Knob.Poly -> { poly.nudge(slot, k.i, delta, shift); polyChanged() }
            Knob.PolyCategory -> presetStep(delta) { d ->
                val cats = PolyPresets.categories(ui.ctx)
                if (cats.isNotEmpty()) { polyCat = (polyCat + d).coerceIn(0, cats.size - 1); presetIdx = 0; loadPreset() }
            }
            Knob.PolyPreset -> presetStep(delta) { d ->
                presetIdx = (presetIdx + d).coerceIn(0, maxOf(0, catPresets().size - 1)); loadPreset()
            }
            null -> {}
        }
    }

    // ---- POLY presets ----
    private val poly get() = ui.engine.poly
    private var presetIdx = 0
    private var presetAcc = 0
    private var polyDirtySince = 0L
    private fun polyChanged() { if (polyDirtySince == 0L) polyDirtySince = SystemClock.uptimeMillis() }
    private var catCache: Pair<Int, List<PolyPresets.Preset>>? = null
    private fun catPresets(): List<PolyPresets.Preset> {
        catCache?.let { if (it.first == polyCat) return it.second }
        val cats = PolyPresets.categories(ui.ctx)
        val c = cats.getOrNull(polyCat) ?: return emptyList()
        val l = PolyPresets.list(ui.ctx).filter { it.category == c }
        catCache = polyCat to l
        return l
    }
    private inline fun presetStep(delta: Int, f: (Int) -> Unit) {
        presetAcc += delta
        if (kotlin.math.abs(presetAcc) < 25) return
        val d = if (presetAcc > 0) 1 else -1
        presetAcc = 0
        f(d)
    }
    /** OB-Xf parameter names, shortened to fit a knob cell. */
    private fun polyShort(n: String): String {
        var t = n.replace("Filter Env", "F.Env").replace("Amp Env", "A.Env").replace("Filter ", "Flt ")
            .replace("Osc 1 ", "O1 ").replace("Osc 2 ", "O2 ").replace("Osc ", "").replace("LFO 1 ", "").replace("LFO 2 ", "")
            .replace("Pulsewidth", "PW").replace("Volume", "Vol").replace("Amount", "Amt").replace("Velocity", "Vel")
            .replace("Modulation", "Mod").replace("Invert", "Inv").replace("Resonance", "Reso")
        if (t.length > 14) t = t.take(14)
        return t
    }

    /** Turning through presets shows the names; the patch loads once the knob rests. */
    private var presetAt = 0L
    private fun loadPreset() { presetAt = SystemClock.uptimeMillis() }
    private fun applyPreset() {
        presetAt = 0L
        val p = catPresets().getOrNull(presetIdx) ?: return
        poly.apply(slot, p)
        polyChanged()
        org.openmk3.core.AppLog.i("POLY ${ui.padLabel()}: ${p.category} / ${p.name}")
    }

    private var typeAcc = 0

    private fun typeKnob(delta: Int) {
        typeAcc += delta
        if (kotlin.math.abs(typeAcc) < 25) return
        val dir = if (typeAcc > 0) 1 else -1
        typeAcc = 0
        val t = (fx.type(chain, fxSlot) + dir).coerceIn(0, FxParams.TYPES.size - 1)
        if (t == fx.type(chain, fxSlot)) return
        ui.engine.setFx(chain, fxSlot, t)
        org.openmk3.core.AppLog.i("FX ${levelName()} slot ${slotIdx + 1}: ${FxParams.TYPES[t]}")
    }

    private fun soundKnob(p: Int, delta: Int) {
        val frames = ui.engine.kit[slot].frames
        if (p in MARKERS && frames > 0) {
            // Markers move relative to the zoomed view, and can snap to zero crossings.
            val raw = rawMarker[p] ?: params[slot, p]
            val next = (raw + delta * waveView.knobStep(shift).toFloat() / frames).coerceIn(0f, 1f)
            rawMarker[p] = next
            val s = ui.engine.kit[slot]
            var f = (next * frames).toInt()
            if (ui.zeroSnap && f in 1 until frames) f = WaveView.zeroCross(s.data, s.channels, f)
            params[slot, p] = f.toFloat() / frames
            params.nudge(slot, p, 0, false)  // keeps start < end and loop start < loop end
            focusFrame = (params[slot, p] * frames).toInt()
            waveView.follow(focusFrame)
        } else {
            val before = source
            params.nudge(slot, p, delta, shift)
            ui.autoWrite(slot, p)
            if (p == SRC && before != source) pageIdx = 0
        }
        changed()
    }

    override fun onKnobTouch(knob: Int, touched: Boolean) {
        if (!touched || !shift) return
        when (val k = page.knobs.getOrNull(knob)) {
            is Knob.Snd -> { SoundParams.DEFS[k.p]?.let { params[slot, k.p] = it.default }; changed() }
            is Knob.Fx -> { fx[chain, fxSlot, k.d.index] = k.d.default; fxChanged() }
            is Knob.Poly -> { poly[slot, k.i] = poly.defaults[k.i]; polyChanged() }
            else -> {}
        }
    }

    override fun onEncoder(delta: Int) {
        if (showScope()) {
            scopeWindow = (if (delta > 0) scopeWindow * 2 else scopeWindow / 2).coerceIn(128, 8192)
            return
        }
        if (page.view == View.WAVE) waveView.zoom(delta, focusFrame)
    }

    // =====================================================================================

    private fun levelName() = when (level) {
        0 -> "MASTER"
        1 -> "GROUP ${ui.groupLetter}"
        3 -> "RETURN A"
        4 -> "RETURN B"
        else -> "SOUND ${ui.padLabel()}"
    }

    private fun slotName(i: Int): String {
        if (level == 2 && i == 0) return when (source) {
            SoundParams.SRC_POLY -> "POLY · ${poly.patchName[slot]}"
            SoundParams.SRC_FM -> "FM · ${params.display(slot, SoundParams.FMPROG)} (${SoundParams.FM_BANKS[params[slot, SoundParams.FMBANK].toInt().coerceIn(0, 18)]})"
            SoundParams.SRC_SYNTH -> "SYNTH · ${SoundParams.MODELS[params[slot, SMODEL].toInt().coerceIn(0, 23)]}"
            else -> "SAMPLER"
        }
        val fs = if (level == 2) i - 1 else i
        val t = fx.type(chain, fs)
        return if (t == FxParams.NONE) "—" else FxParams.TYPES[t] + if (fx.bypassed(chain, fs)) " (bypassed)" else ""
    }

    private fun knobCell(c: Canvas, i: Int) {
        val k = page.knobs.getOrNull(i) ?: return
        val x = 60f + (i % 4) * 120f
        val name: String
        val value: String
        val frac: Float
        var on = true
        when (k) {
            is Knob.Snd -> {
                val d = SoundParams.DEFS[k.p] ?: return
                name = d.name
                value = params.display(slot, k.p)
                val v = params[slot, k.p]
                frac = if (d.log) ln(v / d.min) / ln(d.max / d.min) else (v - d.min) / (d.max - d.min)
                on = applies(k.p)
            }
            is Knob.Fx -> {
                val d = k.d
                name = d.name
                value = fx.display(chain, fxSlot, d)
                val v = fx[chain, fxSlot, d.index]
                frac = if (d.log) ln(v / d.min) / ln(d.max / d.min) else (v - d.min) / (d.max - d.min)
            }
            is Knob.Poly -> {
                name = polyShort(poly.names[k.i]).uppercase()
                value = poly.display(slot, k.i)
                frac = poly[slot, k.i]
            }
            Knob.PolyCategory -> {
                name = "CATEGORY"
                val cats = PolyPresets.categories(ui.ctx)
                value = cats.getOrNull(polyCat) ?: "—"
                frac = if (cats.size > 1) polyCat / (cats.size - 1f) else 0f
            }
            Knob.PolyPreset -> {
                name = "PRESET"
                val ps = catPresets()
                value = ps.getOrNull(presetIdx)?.name ?: "—"
                frac = if (ps.size > 1) presetIdx / (ps.size - 1f) else 0f
            }
            Knob.Type -> {
                name = "TYPE"
                val t = fx.type(chain, fxSlot)
                value = FxParams.TYPES[t]
                frac = t / (FxParams.TYPES.size - 1f)
            }
        }
        c.drawText(name, x, 232f, kName)
        c.drawText(value, x, 252f, if (on) kValue else kOff)
        c.drawRect(x - 44f, 260f, x + 44f, 265f, barBg)
        c.drawRect(x - 44f, 260f, x - 44f + 88f * frac.coerceIn(0f, 1f), 265f, barFg)
    }

    override fun draw(l: Canvas, r: Canvas, now: Long) {
        l.drawColor(Theme.BG)
        r.drawColor(Theme.BG)
        // D-button labels
        l.drawText(when (level) { 3 -> "RETURN A"; 4 -> "RETURN B"; else -> "MASTER" }, 60f, 24f, if (level == 0 || level >= 3) tabOn else tab)
        l.drawText("GROUP", 180f, 24f, if (level == 1) tabOn else tab)
        l.drawText("SOUND", 300f, 24f, if (level == 2) tabOn else tab)
        l.drawText("SLOT ${slotIdx + 1} ▸", 420f, 24f, tab)
        if (!isSource && fx.type(chain, fxSlot) != FxParams.NONE)
            r.drawText("BYPASS", 60f, 24f, if (fx.bypassed(chain, fxSlot)) tabOn else tab)
        r.drawText("SCOPE", 180f, 24f, if (scopeOn) tabOn else tab)
        if (isSource && !isSynth && pageIdx == 1)
            r.drawText(if (ui.zeroSnap) "ZERO-X ON" else "ZERO-X", 420f, 24f, if (ui.zeroSnap) tabOn else tab)
        l.drawRect(0f, 34f, 480f, 35f, sep)
        r.drawRect(0f, 34f, 480f, 35f, sep)
        // page strip (◀ ▶)
        val ps = pages()
        var x = 8f
        for ((i, pg) in ps.withIndex()) {
            val p = if (i == pageIdx.coerceIn(0, ps.size - 1)) stripOn else strip
            if (x + p.measureText(pg.name) > 472f) break
            l.drawText(pg.name, x, 52f, p)
            x += p.measureText(pg.name) + 14f
        }
        // chain list
        l.drawText(levelName(), 16f, 80f, title)
        if (level == 2) l.drawText(ellipsize(ui.engine.kit[slot].name.ifEmpty { if (isSynth) "" else "(empty)" }, 24), 200f, 80f, slotDim)
        for (i in 0 until slotCount) {
            val y = 92f + i * 26f
            if (i == slotIdx) l.drawRect(8f, y, 472f, y + 24f, selFill)
            l.drawText("${i + 1}", 18f, y + 18f, slotDim)
            l.drawText(ellipsize(slotName(i), 40), 40f, y + 18f, if (slotName(i) == "—") slotDim else slotText)
        }
        l.drawRect(0f, 214f, 480f, 215f, sep)
        r.drawRect(0f, 214f, 480f, 215f, sep)
        for (i in 0 until 4) knobCell(l, i)
        for (i in 4 until 8) knobCell(r, i)
        // right screen view
        if (showScope()) { drawScope(r); return }
        when (page.view) {
            View.WAVE -> drawWave(r)
            View.AMP -> drawEnvelope(r)
            View.FILTER -> drawFilter(r)
            View.MOD -> drawModEnv(r)
            View.LFO -> drawLfo(r)
            View.VEL -> drawVelocity(r)
            View.SCOPE -> drawScope(r)
        }
    }

    /** Oscilloscope of the selected level, triggered on a rising zero crossing. */
    private fun drawScope(c: Canvas) {
        val top = 50f; val bot = 206f; val mid = (top + bot) / 2; val half = (bot - top) / 2 - 4f
        val x0 = 16f; val w = 448f
        val n = scopeWindow
        ui.engine.scope(scopeBuf)
        val len = scopeBuf.size
        // find a rising zero crossing so periodic sounds stand still
        var start = len - n - 1
        var k = len - n - 1
        val limit = max(1, len - n - 4096)
        while (k > limit) {
            if (scopeBuf[k - 1] <= 0f && scopeBuf[k] > 0f) { start = k; break }
            k--
        }
        var peak = 0.05f
        for (i in start until start + n) peak = max(peak, abs(scopeBuf[i]))
        val gain = if (peak < 0.25f) 0.25f / peak else 1f  // small signals are scaled up
        c.drawLine(x0, mid, x0 + w, mid, grid)
        val path = Path()
        for (col in 0..447) {
            val idx = start + (col.toLong() * n / 448).toInt()
            val y = mid - (scopeBuf[idx] * gain).coerceIn(-1f, 1f) * half
            if (col == 0) path.moveTo(x0 + col, y) else path.lineTo(x0 + col, y)
        }
        val col = Mk3Colors.rgb(if (level == 2) ui.soundColour(slot) else if (level == 1) groupColour(ui.group) else Mk3Colors.WHITE)
        c.drawPath(path, Theme.stroke(col, 2f))
        val ms = n * 1000f / 48000f
        c.drawText("SCOPE · ${levelName()} · ${"%.1f".format(ms)} ms${if (gain > 1.01f) " · ×${"%.1f".format(gain)}" else ""}", x0, 210f, label)
        // compressor: gain reduction bar
        if (!isSource && fx.type(chain, fxSlot) == FxParams.COMPRESSOR) {
            val gr = ui.engine.fxMeter(chain, fxSlot).coerceIn(0f, 24f)
            c.drawRect(470f, top, 476f, bot, barBg)
            c.drawRect(470f, top, 476f, top + (bot - top) * gr / 24f, grFill)
            c.drawText("GR ${"%.1f".format(gr)} dB", 360f, 210f, label)
        }
    }

    private fun drawWave(c: Canvas) {
        val s = ui.engine.kit[slot]
        if (s.frames == 0) { c.drawText("Empty pad: load a sample (BROWSER) or record one (SAMPLING)", 16f, 130f, label); return }
        waveView.set(s.data, s.channels)
        waveView.setColour(Mk3Colors.rgb(ui.soundColour(slot)))
        val n = s.frames
        fun fr(p: Int) = (params[slot, p] * n).toInt()
        val loop = if (params[slot, LOOP] > 0.5f && params[slot, ENV] > 0.5f) intArrayOf(fr(LSTART), fr(LEND)) else null
        val head = ui.engine.playPos(slot)
        waveView.draw(c, 16f, 60f, 196f, selA = fr(START), selB = fr(END),
            markers = intArrayOf(fr(START), fr(END)), loop = loop, playhead = if (head >= 0f) head.toInt() else -1)
        val notes = buildList {
            if (params[slot, REV] > 0.5f) add("REVERSE")
            if (pageIdx == 1) add("encoder: zoom · tilt: scroll")
        }
        c.drawText(notes.joinToString("  ·  "), 16f, 210f, label)
    }

    private fun drawEnvelope(c: Canvas) {
        val top = 60f; val bot = 196f; val x0 = 24f; val w = 432f
        c.drawLine(x0, bot, x0 + w, bot, grid)
        val path = Path()
        if (params[slot, ENV] > 1.5f) {
            val a = params[slot, ATT]; val d = params[slot, DEC]; val su = params[slot, SUS]; val rl = params[slot, REL]
            val sustainShow = max(0.25f * (a + d + rl), 0.05f)
            val total = a + d + sustainShow + rl
            fun x(t: Float) = x0 + w * (t / total)
            fun y(l: Float) = bot - (bot - top) * l
            path.moveTo(x0, bot)
            path.lineTo(x(a), top)
            for (k in 1..30) { val t = d * k / 30f; path.lineTo(x(a + t), y(su + (1 - su) * Math.pow(1e-4, (t / d).toDouble()).toFloat())) }
            path.lineTo(x(a + d + sustainShow), y(su))
            for (k in 1..30) { val t = rl * k / 30f; path.lineTo(x(a + d + sustainShow + t), y(su * Math.pow(1e-4, (t / rl).toDouble()).toFloat())) }
            c.drawPath(path, curve)
            c.drawText("ADSR: sustains while the pad is held", x0, 210f, label)
            return
        }
        if (params[slot, ENV] < 0.5f) {
            path.moveTo(x0, bot); path.lineTo(x0, top); path.lineTo(x0 + w, top); path.lineTo(x0 + w, bot)
            c.drawPath(path, curve)
            c.drawText("ONE-SHOT: plays the whole sample (start to end)", x0, 210f, label)
            return
        }
        val a = params[slot, ATT]; val h = params[slot, HOLD]; val d = params[slot, DEC]
        val total = a + h + d
        fun x(t: Float) = x0 + w * (t / total)
        path.moveTo(x0, bot)
        path.lineTo(x(a), top)
        path.lineTo(x(a + h), top)
        for (k in 1..40) {
            val t = d * k / 40f
            val lvl = Math.pow(1e-4, (t / d).toDouble()).toFloat() // to -80 dB
            path.lineTo(x(a + h + t), bot - (bot - top) * lvl)
        }
        c.drawPath(path, curve)
        c.drawText("AHD · total %.2f s".format(total), x0, 210f, label)
    }

    private fun drawFilter(c: Canvas) {
        val top = 56f; val bot = 196f; val x0 = 24f; val w = 432f
        val type = params[slot, FTYPE].toInt()
        val fc = params[slot, CUT]
        val k = 2f - 1.96f * params[slot, RES]
        for (f in intArrayOf(100, 1000, 10000)) {
            val gx = x0 + w * (ln(f / 20f) / ln(1000f))
            c.drawLine(gx, top, gx, bot, grid)
        }
        val path = Path()
        for (i in 0..216) {
            val f = 20f * Math.pow(1000.0, i / 216.0).toFloat()
            val wr = f / fc
            val den = sqrt((1 - wr * wr) * (1 - wr * wr) + (k * wr) * (k * wr))
            val mag = when (type) {
                1 -> 1f / den
                2 -> k * wr / den
                3 -> wr * wr / den
                4 -> (1f / den) / sqrt((1 - wr * wr) * (1 - wr * wr) + 2f * wr * wr)
                else -> 1f
            }
            val db = (20f * kotlin.math.log10(max(mag, 1e-4f))).coerceIn(-48f, 24f)
            val y = top + (bot - top) * (24f - db) / 72f
            val x = x0 + w * i / 216f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, curve)
    }

    /** Whether parameter [p] does anything with the current settings (else shown dimmed). */
    private fun applies(p: Int): Boolean {
        val env = params[slot, ENV].toInt()
        return when (p) {
            HOLD -> env == 1
            ATT, DEC -> env != 0
            SUS, REL -> env == 2
            LOOP -> env != 0
            LSTART, LEND, XFADE -> env != 0 && params[slot, LOOP] > 0.5f
            CUT, RES, DRIVE -> p == DRIVE || params[slot, FTYPE] >= 0.5f
            VCUT -> params[slot, FTYPE] >= 0.5f
            else -> true
        }
    }

    private fun pageHint(): String = when (pageIdx) {
        1 -> if (params[slot, ENV] < 0.5f) "Loop needs an AHD or ADSR envelope (AMP ENV)" else "Loop repeats between loop start and end"
        3 -> "Drive saturates before the filter"
        4 -> "Shapes pitch and cutoff at the start of each note"
        5 -> "Restarts with each note"
        6 -> "How hard you hit changes volume and cutoff"
        else -> ""
    }

    private fun drawModEnv(c: Canvas) {
        val top = 60f; val bot = 196f; val x0 = 24f; val w = 432f
        c.drawLine(x0, bot, x0 + w, bot, grid)
        val a = params[slot, MATT]; val d = params[slot, MDEC]
        val total = a + d
        fun x(t: Float) = x0 + w * (t / total)
        val path = Path()
        path.moveTo(x0, bot)
        path.lineTo(x(a), top)
        for (k in 1..40) { val t = d * k / 40f; path.lineTo(x(a + t), bot - (bot - top) * Math.pow(1e-3, (t / d).toDouble()).toFloat()) }
        c.drawPath(path, curve)
    }

    private fun drawLfo(c: Canvas) {
        val top = 60f; val bot = 196f; val mid = (top + bot) / 2; val half = (bot - top) / 2
        val x0 = 24f; val w = 432f
        c.drawLine(x0, mid, x0 + w, mid, grid)
        val wave = params[slot, LWAVE].toInt()
        val path = Path()
        var held = 0.3f
        val rnd = java.util.Random(7)
        for (i in 0..288) {
            val ph = (i / 144f) % 1f
            if (wave == 4 && i % 144 == 0) held = rnd.nextFloat() * 2 - 1
            val y = when (wave) {
                1 -> 1f - 4f * abs(ph - 0.5f)
                2 -> 2f * ph - 1f
                3 -> if (ph < 0.5f) 1f else -1f
                4 -> held
                else -> kotlin.math.sin(6.2831853f * ph)
            }
            val x = x0 + w * i / 288f
            if (i == 0) path.moveTo(x, mid - y * half) else path.lineTo(x, mid - y * half)
        }
        c.drawPath(path, curve)
        c.drawText("2 cycles shown", x0, 210f, label)
    }

    private fun drawVelocity(c: Canvas) {
        val top = 60f; val bot = 196f; val x0 = 24f; val w = 432f
        c.drawLine(x0, bot, x0 + w, bot, grid)
        val amt = params[slot, VVOL]
        val path = Path()
        for (i in 0..100) {
            val v = i / 100f
            val g = 1f - amt + amt * v * v
            val x = x0 + w * v
            val y = bot - (bot - top) * g
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, curve)
        c.drawText("volume by velocity (soft → hard)", x0, 210f, label)
    }

    private fun ellipsize(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}

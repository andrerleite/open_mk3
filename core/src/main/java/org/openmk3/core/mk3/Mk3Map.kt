package org.openmk3.core.mk3

import android.content.Context
import org.openmk3.core.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Names for the MK3 controls the mapping wizard asks for, in a sensible physical order. */
object Mk3Buttons {
    val ALL = listOf(
        // top-left block
        "CHANNEL", "PLUG-IN", "ARRANGER", "MIXER", "BROWSER", "SAMPLING",
        "FILE", "SETTINGS", "AUTO", "MACRO",
        // above / beside the screens
        "PAGE LEFT", "PAGE RIGHT",
        "D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8",
        // right of the screens
        "VOLUME", "SWING", "TEMPO", "NOTE REPEAT", "LOCK",
        "PITCH", "MOD", "PERFORM", "NOTES",
        // 4D encoder
        // ENCODER TOUCH is not a button: it teaches the wizard which bit is the touch sensor.
        "ENCODER TOUCH", "ENCODER PUSH", "ENCODER UP", "ENCODER DOWN", "ENCODER LEFT", "ENCODER RIGHT",
        // left of the pads
        "RESTART", "ERASE", "TAP", "FOLLOW",
        "PLAY", "REC", "STOP", "SHIFT",
        // group buttons, left of the pads
        "GROUP A", "GROUP B", "GROUP C", "GROUP D", "GROUP E", "GROUP F", "GROUP G", "GROUP H",
        // above the pads
        "FIXED VEL", "PAD MODE", "KEYBOARD", "CHORDS", "STEP",
        "SCENE", "PATTERN", "EVENTS", "VARIATION", "DUPLICATE", "SELECT", "SOLO", "MUTE",
    )
}

/**
 * Learned mapping between the MK3's raw indices and meaning. Produced by the on-device
 * wizard and stored as JSON in the app's files dir.
 */
class Mk3Map {
    /** Button name -> HID bit (0..71). */
    val buttonBits = LinkedHashMap<String, Int>()
    /** Button name -> index into report 0x80. */
    val buttonLeds = LinkedHashMap<String, Int>()
    /** Physical pad slot (0 = bottom-left, row-major upward) -> HID pad index. */
    val padSlotToHid = IntArray(16) { it }
    var padsMapped = false
    /** HID pad index -> index into report 0x81 (-1 = unknown). */
    val padLeds = IntArray(16) { -1 }
    /** Report 0x81 indices that lit something other than a pad (touch strip etc.). */
    val otherPadReportLeds = ArrayList<Int>()

    /** Bits that are touch sensors (e.g. on the 4D encoder), not buttons: ignored. */
    val touchBits = LinkedHashSet<Int>()

    private val bitToName = HashMap<Int, String>()

    fun nameForBit(bit: Int): String? = bitToName[bit]
    fun hidPadToSlot(hid: Int): Int = padSlotToHid.indexOf(hid).let { if (it < 0) hid else it }

    fun rebuildIndex() {
        bitToName.clear()
        for ((k, v) in buttonBits) bitToName[v] = k
    }

    val buttonsMapped get() = buttonBits.size >= 10
    val ledsMapped get() = buttonLeds.isNotEmpty()
    val padLedsMapped get() = padLeds.any { it >= 0 }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("buttonBits", JSONObject(buttonBits as Map<*, *>))
        put("buttonLeds", JSONObject(buttonLeds as Map<*, *>))
        put("padsMapped", padsMapped)
        put("padSlotToHid", JSONArray(padSlotToHid.toList()))
        put("padLeds", JSONArray(padLeds.toList()))
        put("otherPadReportLeds", JSONArray(otherPadReportLeds))
        put("touchBits", JSONArray(touchBits.toList()))
    }

    fun save(ctx: Context) {
        rebuildIndex()
        file(ctx).writeText(toJson().toString(2))
        AppLog.i("Mapping saved: ${buttonBits.size} buttons, ${buttonLeds.size} button LEDs, " +
            "pads=${padsMapped}, pad LEDs=${padLeds.count { it >= 0 }}")
        AppLog.i("Mapping JSON: ${toJson()}")
    }

    private fun parse(j: JSONObject) {
        j.optJSONObject("buttonBits")?.let { o -> o.keys().forEach { this.buttonBits[it] = o.getInt(it) } }
        j.optJSONObject("buttonLeds")?.let { o -> o.keys().forEach { this.buttonLeds[it] = o.getInt(it) } }
        this.padsMapped = j.optBoolean("padsMapped")
        j.optJSONArray("padSlotToHid")?.let { a -> for (i in 0 until minOf(16, a.length())) this.padSlotToHid[i] = a.getInt(i) }
        j.optJSONArray("padLeds")?.let { a -> for (i in 0 until minOf(16, a.length())) this.padLeds[i] = a.getInt(i) }
        j.optJSONArray("otherPadReportLeds")?.let { a -> for (i in 0 until a.length()) this.otherPadReportLeds.add(a.getInt(i)) }
        j.optJSONArray("touchBits")?.let { a -> for (i in 0 until a.length()) this.touchBits.add(a.getInt(i)) }
    }

    companion object {
        private fun file(ctx: Context) = File(ctx.filesDir, "mk3map.json")

        /**
         * The standard MK3 layout (identical on every unit), so a fresh install works without
         * running the setup wizard. The wizard can still re-learn anything.
         */
        private const val DEFAULT_JSON = """{"version":1,
 "buttonBits":{"CHANNEL":56,"PLUG-IN":53,"ARRANGER":57,"MIXER":52,"BROWSER":58,"SAMPLING":51,"FILE":60,"SETTINGS":49,
  "AUTO":61,"MACRO":48,"PAGE LEFT":59,"PAGE RIGHT":50,"D1":64,"D2":65,"D3":66,"D4":67,"D5":68,"D6":69,"D7":70,"D8":7,
  "VOLUME":17,"SWING":18,"TEMPO":19,"NOTE REPEAT":20,"LOCK":21,"PITCH":38,"MOD":39,"PERFORM":40,"NOTES":16,
  "ENCODER PUSH":71,"ENCODER UP":2,"ENCODER DOWN":4,"ENCODER LEFT":5,"ENCODER RIGHT":3,
  "RESTART":41,"ERASE":42,"TAP":43,"FOLLOW":44,"PLAY":45,"REC":46,"STOP":47,"SHIFT":6,
  "GROUP A":8,"GROUP B":9,"GROUP C":10,"GROUP D":11,"GROUP E":12,"GROUP F":13,"GROUP G":14,"GROUP H":15,
  "FIXED VEL":28,"PAD MODE":24,"KEYBOARD":25,"CHORDS":26,"STEP":27,"SCENE":29,"PATTERN":30,"EVENTS":31,
  "VARIATION":33,"DUPLICATE":34,"SELECT":35,"SOLO":36,"MUTE":37},
 "buttonLeds":{"CHANNEL":0,"PLUG-IN":1,"ARRANGER":2,"MIXER":3,"BROWSER":4,"SAMPLING":5,"PAGE LEFT":6,"PAGE RIGHT":7,
  "FILE":8,"SETTINGS":9,"AUTO":10,"MACRO":11,"D1":12,"D2":13,"D3":14,"D4":15,"D5":16,"D6":17,"D7":18,"D8":19,
  "VOLUME":20,"SWING":21,"NOTE REPEAT":22,"TEMPO":23,"LOCK":24,"PITCH":25,"MOD":26,"PERFORM":27,"NOTES":28,
  "GROUP A":29,"GROUP B":30,"GROUP C":31,"GROUP D":32,"GROUP E":33,"GROUP F":34,"GROUP G":35,"GROUP H":36,
  "RESTART":37,"ERASE":38,"TAP":39,"FOLLOW":40,"PLAY":41,"REC":42,"STOP":43,"SHIFT":44,"FIXED VEL":45,"PAD MODE":46,
  "KEYBOARD":47,"CHORDS":48,"STEP":49,"SCENE":50,"PATTERN":51,"EVENTS":52,"VARIATION":53,"DUPLICATE":54,"SELECT":55,
  "SOLO":56,"MUTE":57},
 "padsMapped":true,"padSlotToHid":[12,13,14,15,8,9,10,11,4,5,6,7,0,1,2,3],
 "padLeds":[25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40],
 "otherPadReportLeds":[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24]}"""

        fun load(ctx: Context): Mk3Map {
            val m = Mk3Map()
            val f = file(ctx)
            if (!f.exists()) {
                m.parse(JSONObject(DEFAULT_JSON))
                m.rebuildIndex()
                return m
            }
            try {
                m.parse(JSONObject(f.readText()))
            } catch (e: Exception) {
                AppLog.i("Mapping file unreadable, starting fresh: $e")
            }
            m.rebuildIndex()
            return m
        }
    }
}

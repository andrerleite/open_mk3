package org.openmk3.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/** Battery readings, used to estimate how long the phone can power the MK3. */
object PowerMonitor {

    data class Sample(
        val percent: Int,
        /** Positive = draining, in mA. Null if the phone doesn't report it. */
        val drainMa: Int?,
        val rawCurrentNow: Int,
        val chargeMah: Int?,
        val voltageMv: Int,
        val tempC: Float,
        val plugged: Int,
        val status: Int,
    ) {
        val charging get() = status == BatteryManager.BATTERY_STATUS_CHARGING || plugged != 0

        fun hoursLeft(): Float? {
            val d = drainMa ?: return null
            val c = chargeMah ?: return null
            if (charging || d <= 0) return null
            return c.toFloat() / d
        }

        fun line(): String {
            val drain = drainMa?.let { "${it}mA" } ?: "n/a"
            val est = hoursLeft()?.let { String.format("%.1fh", it) } ?: "-"
            return "batt $percent% drain=$drain (raw $rawCurrentNow) charge=${chargeMah ?: "?"}mAh " +
                "${voltageMv}mV ${"%.1f".format(tempC)}C plugged=$plugged est=$est"
        }
    }

    fun sample(ctx: Context): Sample {
        val bm = ctx.getSystemService(BatteryManager::class.java)!!
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val sticky: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val mv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val temp = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0

        // CURRENT_NOW is specified in microamps, but some Samsung builds report mA.
        // Phones draw hundreds of mA, so a magnitude under 20000 means it's already mA.
        val drain: Int? = when {
            cur == Int.MIN_VALUE || cur == 0 -> null
            abs(cur) < 20_000 -> -cur          // mA, negative = discharging
            else -> -cur / 1000                 // µA -> mA
        }
        // Sign conventions differ between vendors too: when unplugged, any current is drain.
        val drainFixed = if (drain != null && plugged == 0) abs(drain) else drain
        val chargeMah = if (cc == Int.MIN_VALUE || cc <= 0) null else if (cc > 100_000) cc / 1000 else cc
        return Sample(pct, drainFixed, cur, chargeMah, mv, temp, plugged, status)
    }
}

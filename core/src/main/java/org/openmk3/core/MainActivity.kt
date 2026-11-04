package org.openmk3.core

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.openmk3.core.mk3.Mk3Device

/**
 * Phone-side screen. Only needed to start things, run setup and fetch logs: in use, the
 * phone screen stays off and the MK3 is the whole interface.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 7
        private const val ACTION_USB_PERMISSION = "org.openmk3.core.USB_PERMISSION"
    }

    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val logListener: () -> Unit = { refreshLog() }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            AppLog.i("USB permission ${if (granted) "granted" else "DENIED"}")
            if (granted && d != null) Mk3Service.start(this@MainActivity, d)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        usb = getSystemService(UsbManager::class.java)!!
        buildUi()
        registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)
        val missing = arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_MEDIA_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        handleIntent(intent)
    }

    @Deprecated("Simple one-off result; no AndroidX in this project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            Mk3Service.instance?.onCaptureGranted(resultCode, data) ?: AppLog.i("Phone capture: no MK3 session")
        } else {
            AppLog.i("Phone capture: declined")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val i = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (i >= 0) {
            val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            AppLog.i("Record-audio permission ${if (granted) "granted" else "DENIED (output may pop every ~36 s)"}")
            if (granted) Mk3Service.instance?.restartAudio()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Mk3Service.ACTION_REQUEST_CAPTURE) {
            val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)!!
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
            return
        }
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
        AppLog.i("MK3 plugged in: ${d.productName}")
        if (usb.hasPermission(d)) Mk3Service.start(this, d) else requestPermission(d)
    }

    override fun onResume() {
        super.onResume()
        AppLog.addListener(logListener)
        refreshLog()
        handler.post(statusTick)
    }

    override fun onPause() {
        AppLog.removeListener(logListener)
        handler.removeCallbacks(statusTick)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    private fun connect() {
        val all = usb.deviceList.values
        AppLog.i("USB devices visible to Android: ${all.size}")
        for (x in all) {
            AppLog.i("  ${x.deviceName} VID=0x${x.vendorId.hex4()} PID=0x${x.productId.hex4()} '${x.productName}' permission=${usb.hasPermission(x)}")
        }
        val d = all.firstOrNull { it.vendorId == Mk3Device.NI_VID && it.productId == Mk3Device.MK3_PID }
            ?: all.firstOrNull { it.vendorId == Mk3Device.NI_VID }
        if (d == null) {
            AppLog.i(
                if (all.isEmpty()) "No USB device at all: the phone isn't seeing the MK3. Unplug the adapter, wait 10 s, " +
                    "plug it back in and check the MK3 lights up. If it stays dark, restart the phone."
                else "No Native Instruments device among them."
            )
            return
        }
        if (d.productId != Mk3Device.MK3_PID) AppLog.i("Native Instruments device with unexpected PID 0x${d.productId.hex4()}: trying anyway")
        if (usb.hasPermission(d)) Mk3Service.start(this, d) else requestPermission(d)
    }

    private fun requestPermission(d: UsbDevice) {
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(d, pi)
    }

    private fun exportLog(share: Boolean) {
        val uri = try { AppLog.exportToDownloads(this) } catch (e: Exception) { AppLog.i("Export failed: $e"); null } ?: return
        AppLog.i("Log saved to Downloads/${Edition.storageFolder}/${AppLog.file?.name}")
        if (share) {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share log"
            ))
        } else {
            Toast.makeText(this, "Saved to Downloads/${Edition.storageFolder}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- the screen: status at a glance, the few actions still needed, logs on request ----

    private val bg = android.graphics.Color.rgb(14, 14, 18)
    private val card = android.graphics.Color.rgb(28, 28, 36)
    private val accent = android.graphics.Color.rgb(255, 140, 40)
    private val fg = android.graphics.Color.rgb(236, 236, 240)
    private val dim = android.graphics.Color.rgb(140, 140, 152)
    private val ok = android.graphics.Color.rgb(90, 200, 120)
    private val off = android.graphics.Color.rgb(90, 90, 100)

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var infoRows: LinearLayout
    private lateinit var connectBtn: TextView
    private lateinit var captureBtn: TextView
    private lateinit var logBox: View
    private lateinit var advanced: View
    private var dp = 1f

    private fun rounded(color: Int, radius: Float, stroke: Int = 0) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = radius * dp
        if (stroke != 0) setStroke((1 * dp).toInt(), stroke)
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** A pill-shaped button: filled (main action) or outlined. */
    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) = text(15f, if (filled) bg else fg, bold = true).apply {
        text = label
        gravity = android.view.Gravity.CENTER
        setPadding((18 * dp).toInt(), (14 * dp).toInt(), (18 * dp).toInt(), (14 * dp).toInt())
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(60, 255, 255, 255)),
            rounded(if (filled) accent else card, 28f, if (filled) 0 else android.graphics.Color.rgb(60, 60, 72)), null)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        dp = resources.displayMetrics.density
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val pad = (20 * dp).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val lp = { top: Int -> LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (top * dp).toInt() } }

        // header
        col.addView(text(28f, fg, bold = true).apply { text = Edition.title })
        col.addView(text(13f, dim).apply { text = "v${Edition.version} · the controller is the instrument" })

        // status card
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 18f)
            setPadding(pad, pad, pad, pad)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        statusDot = View(this).apply { background = rounded(off, 6f) }
        head.addView(statusDot, LinearLayout.LayoutParams((12 * dp).toInt(), (12 * dp).toInt()).apply { rightMargin = (12 * dp).toInt() })
        statusText = text(20f, fg, bold = true)
        head.addView(statusText)
        status.addView(head)
        statusSub = text(13f, dim)
        status.addView(statusSub, lp(6))
        infoRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status.addView(infoRows, lp(12))
        col.addView(status, lp(24))

        // actions
        connectBtn = pill("Connect MK3", true) {
            if (Mk3Service.instance?.ui != null) Mk3Service.command(this, Mk3Service.ACTION_STOP) else connect()
            handler.postDelayed({ refreshStatus() }, 600)
        }
        col.addView(connectBtn, lp(20))
        captureBtn = pill("Allow phone audio for sampling", false) {
            if (Mk3Service.instance?.ui != null && Mk3Service.instance?.phoneCaptureActive != true)
                handleIntent(Intent(Mk3Service.ACTION_REQUEST_CAPTURE))
        }
        col.addView(captureBtn, lp(12))

        // logs and advanced, folded away
        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun link(label: String, onClick: () -> Unit) = text(14f, accent, bold = true).apply {
            text = label; setPadding(0, (8 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt()); setOnClickListener { onClick() }
        }
        links.addView(link("Save log") { exportLog(false) })
        links.addView(link("Advanced") { advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        links.addView(link("Show log") {
            logBox.visibility = if (logBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (logBox.visibility == View.VISIBLE) refreshLog()
        })
        col.addView(links, lp(28))

        val adv = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 18f)
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        adv.addView(text(13f, dim).apply { text = "The controls are built in. Re-learn them only if a button or pad acts as another one." })
        adv.addView(pill("Re-learn controls (on the MK3)", false) { Mk3Service.command(this, Mk3Service.ACTION_WIZARD, "ALL") }, lp(12))
        val driver = pill("", false) {}
        fun dLabel() = "Audio driver: " + if (Mk3Service.directAudioEnabled(this)) "direct USB (recommended)" else "Android"
        driver.text = dLabel()
        driver.setOnClickListener {
            Mk3Service.setDirectAudio(this, !Mk3Service.directAudioEnabled(this))
            driver.text = dLabel()
            AppLog.i("${dLabel()}: takes effect on the next connect")
        }
        adv.addView(driver, lp(10))
        advanced = adv
        col.addView(adv, lp(8))

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 10f; setTextColor(dim); setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply {
            addView(logView)
            background = rounded(android.graphics.Color.rgb(8, 8, 10), 14f)
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            visibility = View.GONE
        }
        logBox = logScroll
        col.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, (360 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })

        val outer = ScrollView(this).apply {
            setBackgroundColor(bg)
            fitsSystemWindows = true
            isFillViewport = true
            addView(col)
        }
        setContentView(outer)
        refreshStatus()
    }

    private val statusTick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }

    /** Fills the status card from the running session (or the hint to plug in). */
    private fun refreshStatus() {
        val svc = Mk3Service.instance
        val ui = svc?.ui
        val on = ui != null
        statusDot.background = rounded(if (on) ok else off, 6f)
        statusText.text = if (on) "MK3 connected" else "MK3 not connected"
        statusSub.text = if (on) "Turn the phone screen off: everything happens on the controller."
            else "Plug in the MK3 (with power). It connects by itself; if not, tap Connect."
        infoRows.removeAllViews()
        if (ui != null) {
            val e = ui.engine
            fun row(k: String, v: String, vColor: Int = fg) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (5 * dp).toInt(), 0, (5 * dp).toInt()) }
                r.addView(text(14f, dim).apply { text = k }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                r.addView(text(14f, vColor, bold = true).apply { text = v })
                infoRows.addView(r)
            }
            row("Output", if (e.outputPhone) "Phone" else "MK3")
            row("Tempo", "%.1f BPM".format(e.bpm))
            row("Transport", if (e.playing) "Playing" else "Stopped", if (e.playing) ok else fg)
            row("Audio", if (e.direct) "Direct USB" else "Android")
            row("Phone audio sampling", if (svc.phoneCaptureActive) "Allowed" else "Not allowed", if (svc.phoneCaptureActive) ok else dim)
        }
        connectBtn.text = if (on) "Disconnect" else "Connect MK3"
        captureBtn.visibility = if (on && svc?.phoneCaptureActive != true) View.VISIBLE else View.GONE
    }

    private fun refreshLog() {
        if (logBox.visibility != View.VISIBLE) return
        logView.text = AppLog.tail(400)
        handler.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}

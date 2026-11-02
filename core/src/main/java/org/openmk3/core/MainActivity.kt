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
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
        AppLog.i("MK3 plugged in: ${d.productName}")
        if (usb.hasPermission(d)) Mk3Service.start(this, d) else requestPermission(d)
    }

    override fun onResume() {
        super.onResume()
        AppLog.addListener(logListener)
        refreshLog()
    }

    override fun onPause() {
        AppLog.removeListener(logListener)
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

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "Plug in the MK3: everything else happens on the MK3. First run starts the " +
                "setup wizard on the MK3 screens. Then: pads play drums, PLAY = demo beat, " +
                "K1 volume, K2 pad sensitivity, K3/encoder tempo, SETTINGS = LED palette."
        })
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 12f
            setOnClickListener { onClick() }
        }
        fun row(vararg views: View) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (v in views) r.addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            root.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        fun wizard(mode: String) = Mk3Service.command(this, Mk3Service.ACTION_WIZARD, mode)

        row(btn("Connect") { connect() }, btn("Stop") { Mk3Service.command(this, Mk3Service.ACTION_STOP) },
            btn("Home") { Mk3Service.command(this, Mk3Service.ACTION_HOME) })
        row(btn("Setup: all") { wizard("ALL") }, btn("Setup: pads") { wizard("PADS") },
            btn("Setup: buttons") { wizard("BUTTONS") }, btn("Setup: encoder") { wizard("ENCODER") })
        row(btn("Setup: button lights") { wizard("BUTTON_LEDS") }, btn("Setup: pad lights") { wizard("PAD_LEDS") },
            btn("LED palette") { Mk3Service.command(this, Mk3Service.ACTION_PALETTE) })
        row(btn("Power") { AppLog.i(PowerMonitor.sample(this).line()) }, btn("Save log") { exportLog(false) },
            btn("Share log") { exportLog(true) })
        row(btn("Copy log") {
            val t = AppLog.tail(3000)
            getSystemService(ClipboardManager::class.java)!!.setPrimaryClip(ClipData.newPlainText("log", t))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }, btn("Clear view") { AppLog.clearView() })
        val audioBtn = btn("") {}
        fun label() = "Audio path: " + if (Mk3Service.directAudioEnabled(this)) "direct USB" else "Android (AAudio)"
        audioBtn.text = label()
        audioBtn.setOnClickListener {
            Mk3Service.setDirectAudio(this, !Mk3Service.directAudioEnabled(this))
            audioBtn.text = label()
            AppLog.i("${label()} (takes effect on the next connect: tap Stop, then Connect)")
        }
        val queueBtn = btn("") {}
        fun qLabel() = "USB buffer: ${Mk3Service.usbQueueMs(this)} ms"
        queueBtn.text = qLabel()
        queueBtn.setOnClickListener {
            val c = Mk3Service.QUEUE_CHOICES
            val next = c[(c.indexOf(Mk3Service.usbQueueMs(this)) + 1).mod(c.size)]
            Mk3Service.setUsbQueueMs(this, next)
            queueBtn.text = qLabel()
            AppLog.i("${qLabel()} (takes effect on the next connect: tap Stop, then Connect)")
        }
        row(audioBtn, queueBtn)

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 10f; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun refreshLog() {
        logView.text = AppLog.tail(400)
        handler.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}

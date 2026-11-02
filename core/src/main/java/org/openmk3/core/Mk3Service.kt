package org.openmk3.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.openmk3.core.audio.AudioEngine
import org.openmk3.core.mk3.Mk3Device
import org.openmk3.core.mk3.Mk3Listener
import org.openmk3.core.ui.PalettePage
import org.openmk3.core.ui.Ui
import org.openmk3.core.ui.WizardPage

/**
 * Foreground service that owns the MK3, the audio engine and the MK3 UI, so everything
 * keeps running with the phone screen off.
 */
class Mk3Service : Service() {

    companion object {
        private const val CHANNEL = "groove"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "org.openmk3.core.STOP"
        const val ACTION_WIZARD = "org.openmk3.core.WIZARD"
        const val ACTION_PALETTE = "org.openmk3.core.PALETTE"
        const val ACTION_HOME = "org.openmk3.core.HOME"
        const val ACTION_REQUEST_CAPTURE = "org.openmk3.core.REQUEST_CAPTURE"
        private const val CAPTURE_NOTIF_ID = 2
        const val EXTRA_MODE = "mode"
        private const val HEARTBEAT_MS = 30_000L

        @Volatile var instance: Mk3Service? = null
            private set

        fun start(ctx: Context, device: UsbDevice) {
            ctx.startForegroundService(Intent(ctx, Mk3Service::class.java).putExtra(UsbManager.EXTRA_DEVICE, device))
        }

        private const val PREFS = "openmk3"
        private const val PREF_DIRECT = "directUsbAudio"

        fun directAudioEnabled(ctx: Context) =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_DIRECT, true)

        fun setDirectAudio(ctx: Context, on: Boolean) =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_DIRECT, on).apply()

        private const val PREF_QUEUE = "usbQueueMs"
        val QUEUE_CHOICES = intArrayOf(4, 6, 8, 12)

        /** Direct-USB queue length in ms (= URBs of 8 packets x 125 us). */
        fun usbQueueMs(ctx: Context) = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_QUEUE, 8)

        fun setUsbQueueMs(ctx: Context, ms: Int) =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_QUEUE, ms).apply()

        fun command(ctx: Context, action: String, mode: String? = null) {
            ctx.startService(Intent(ctx, Mk3Service::class.java).setAction(action).putExtra(EXTRA_MODE, mode))
        }
    }

    /** Lets the device exist before the UI that receives its events. */
    private class ListenerProxy : Mk3Listener {
        @Volatile var target: Mk3Listener? = null
        override fun onInitialState(knobs: IntArray, encoder: Int) { target?.onInitialState(knobs, encoder) }
        override fun onButton(bit: Int, down: Boolean) { target?.onButton(bit, down) }
        override fun onKnobTouch(knob: Int, touched: Boolean) { target?.onKnobTouch(knob, touched) }
        override fun onKnob(knob: Int, delta: Int, value: Int) { target?.onKnob(knob, delta, value) }
        override fun onEncoder(delta: Int) { target?.onEncoder(delta) }
        override fun onTouchStrip(position: Int, counter: Int) { target?.onTouchStrip(position, counter) }
        override fun onPadHit(pad: Int, pressure: Int) { target?.onPadHit(pad, pressure) }
        override fun onPadPressure(pad: Int, pressure: Int) { target?.onPadPressure(pad, pressure) }
        override fun onPadRelease(pad: Int) { target?.onPadRelease(pad) }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var dev: Mk3Device? = null
    private var engine: AudioEngine? = null
    var ui: Ui? = null
        private set
    private var started = 0L

    private val heartbeat = object : Runnable {
        override fun run() {
            beat()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> AppLog.i("*** SCREEN OFF ***")
                Intent.ACTION_SCREEN_ON -> AppLog.i("*** SCREEN ON ***")
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (d != null && d.deviceName == dev?.device?.deviceName) {
                        AppLog.i("MK3 detached: stopping")
                        stopSelf()
                    }
                }
            }
        }
    }

    /** Re-route audio when the MK3's USB audio output appears after we started. */
    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            val usb = added.any { it.isSink && (it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET) }
            val e = engine
            if (usb && e != null && !e.direct && !e.outputName.contains("USB")) {
                AppLog.i("USB audio output appeared: restarting audio")
                e.stop()
                e.start()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.init(this)
        getSystemService(NotificationManager::class.java)!!
            .createNotificationChannel(NotificationChannel(CHANNEL, "MK3 session", NotificationManager.IMPORTANCE_LOW))
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, RECEIVER_NOT_EXPORTED)
        getSystemService(AudioManager::class.java)!!.registerAudioDeviceCallback(audioCallback, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_WIZARD -> {
                val mode = intent.getStringExtra(EXTRA_MODE)?.let { WizardPage.Mode.valueOf(it) } ?: WizardPage.Mode.ALL
                ui?.let { it.show(WizardPage(it, mode)) } ?: AppLog.i("Not connected")
                return START_NOT_STICKY
            }
            ACTION_PALETTE -> { ui?.let { it.show(PalettePage(it)) }; return START_NOT_STICKY }
            ACTION_HOME -> { ui?.showHome(); return START_NOT_STICKY }
        }

        val device = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        try {
            startForeground(NOTIF_ID, notification("Starting…"), fgsTypes())
        } catch (e: Exception) {
            AppLog.i("startForeground failed: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        if (device == null) {
            if (dev == null) stopSelf()
            return START_NOT_STICKY
        }
        if (dev?.device?.deviceName == device.deviceName && dev?.isOpen == true) {
            AppLog.i("Already running")
            return START_NOT_STICKY
        }
        shutdownSession()

        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)!!
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openmk3:session").apply { acquire() }
        }

        val e = AudioEngine(this).also { engine = it }
        e.pattern = org.openmk3.core.audio.Pattern().load(this)
        val proxy = ListenerProxy()
        val d = Mk3Device(this, device, proxy)
        if (!d.open()) {
            AppLog.i("Could not open the MK3")
            engine = null
            stopSelf()
            return START_NOT_STICKY
        }
        dev = d
        // Preferred: our own USB audio driver, locked to the MK3's clock. Fallback: AAudio.
        if (directAudioEnabled(this) && d.startDirectAudio(urbs = usbQueueMs(this)) != null) e.startDirect()
        else {
            if (directAudioEnabled(this)) AppLog.i("Direct USB audio unavailable: using Android audio (AAudio)")
            e.start()
        }
        val u = Ui(this, d, e)
        ui = u
        proxy.target = u
        u.start()
        loadKit(e)
        started = System.currentTimeMillis()
        updateNotification("Running")
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        return START_NOT_STICKY
    }

    /** Loads the saved WAV kit in the background; pads keep their built-in sound until then. */
    private fun loadKit(e: AudioEngine) = Thread({
        val paths = org.openmk3.core.audio.KitStore.load(this)
        var loaded = 0
        for ((slot, path) in paths.withIndex()) {
            if (path == null) continue
            try {
                e.setSample(slot, org.openmk3.core.audio.WavDecoder.decode(java.io.File(path)))
                loaded++
            } catch (ex: Exception) {
                AppLog.i("Kit: pad ${slot + 1} could not load $path: ${ex.message}")
            }
        }
        if (loaded > 0) {
            AppLog.i("Kit: $loaded sample(s) loaded")
            ui?.invalidate()
        }
    }, "kit-loader").start()

    /** connectedDevice always; microphone too once RECORD_AUDIO is granted (MK3 input stream). */
    private fun fgsTypes(): Int {
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (captureConsent) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        return t
    }

    // ---- phone playback capture (sampling what other apps play) ----

    private var projection: android.media.projection.MediaProjection? = null
    /** The user confirmed capture; the service may (and must) carry the mediaProjection type. */
    private var captureConsent = false
    private var phoneCapture: org.openmk3.core.audio.PhoneCapture? = null
    val phoneCaptureActive get() = phoneCapture != null

    /**
     * Android needs the user's confirmation on the phone screen once per session. The MK3
     * can't show that dialog, so a notification opens it.
     */
    fun requestPhoneCapture() = handler.post {
        if (phoneCapture != null) return@post
        val pi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).setAction(ACTION_REQUEST_CAPTURE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Allow sampling phone audio")
            .setContentText("Tap, then choose \"Entire screen\" and Start")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)!!.notify(CAPTURE_NOTIF_ID, n)
        AppLog.i("Phone capture: waiting for the user to confirm on the phone")
    }

    /** Called by MainActivity with the result of the system capture dialog. */
    fun onCaptureGranted(resultCode: Int, data: Intent) = handler.post {
        try {
            val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)!!
            // Android 14+: the FGS must have the mediaProjection type before the projection is obtained.
            captureConsent = true
            startForeground(NOTIF_ID, notification("Running · phone audio sampling on"), fgsTypes())
            projection = mpm.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() { handler.post { stopPhoneCapture() } }
            }, handler)
            val pc = org.openmk3.core.audio.PhoneCapture(projection!!)
            if (pc.start()) phoneCapture = pc else stopPhoneCapture()
        } catch (e: Exception) {
            AppLog.i("Phone capture: could not start: $e")
            stopPhoneCapture()
        }
        getSystemService(NotificationManager::class.java)!!.cancel(CAPTURE_NOTIF_ID)
        ui?.invalidate()
    }

    private fun stopPhoneCapture() {
        phoneCapture?.stop()
        phoneCapture = null
        try { projection?.stop() } catch (_: Exception) {}
        val had = captureConsent
        projection = null
        captureConsent = false
        if (had && instance != null && ui != null) {
            try { startForeground(NOTIF_ID, notification("Running"), fgsTypes()) } catch (_: Exception) {}
        }
    }

    /** Called after the user grants RECORD_AUDIO while running: reopen audio with the input. */
    fun restartAudio() = handler.post {
        val e = engine ?: return@post
        if (e.direct) return@post // direct USB audio doesn't use the input permission
        try {
            startForeground(NOTIF_ID, notification("Running"), fgsTypes())
        } catch (ex: Exception) {
            AppLog.i("startForeground (update types) failed: $ex")
        }
        e.stop()
        e.start()
    }

    private fun beat() {
        val d = dev ?: return
        val e = engine ?: return
        val u = ui ?: return
        val p = PowerMonitor.sample(this)
        val screen = if (getSystemService(PowerManager::class.java)!!.isInteractive) "ON" else "OFF"
        AppLog.i(
            "HB t=${(System.currentTimeMillis() - started) / 1000}s screen=$screen reports=${d.reportCount} " +
                "displayKB=${d.displayBytes / 1024} frames=${u.framesSent} | audio buf=${e.bufferFrames}fr " +
                "underruns=${e.underruns} worstTrig=${"%.1f".format(e.worstTriggerToRenderMs)}ms " +
                "input=${if (e.direct || e.inputOpen) "on peak=${"%.3f".format(e.takeInputPeak())}" else "off"} " +
                "path=${if (e.direct) "direct-usb" else "aaudio"} | ${p.line()}"
        )
        e.worstTriggerToRenderMs = 0f
        AppLog.flush()
        updateNotification("screen $screen · ${p.percent}% · ${p.drainMa ?: "?"} mA")
    }

    private fun shutdownSession() {
        stopPhoneCapture()
        ui?.stop()
        ui = null
        dev?.close()
        dev = null
        engine?.pattern?.save(this)
        engine?.stop()
        engine = null
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, Mk3Service::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(Edition.appName)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)!!.notify(NOTIF_ID, notification(text))
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        getSystemService(AudioManager::class.java)!!.unregisterAudioDeviceCallback(audioCallback)
        shutdownSession()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        instance = null
        AppLog.i("Service stopped")
        AppLog.flush()
        super.onDestroy()
    }
}

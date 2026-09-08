package com.antigravity.btaudiorouter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

class AudioRoutingService : Service() {

    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var prefs: DevicePreferenceManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCallActive = false
    private var isWatchdogRunning = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isCallActive) {
                enforceTargetAudioRoute()
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private val deviceChangedListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        log("Communication device megváltozott: ${device?.productName ?: "Nincs"} (${device?.address ?: "-"})")
        notifyStatusUpdate()
        if (isCallActive) {
            val targetMac = prefs.targetSpeakerMac
            if (targetMac != null && (device == null || !device.address.equals(targetMac, ignoreCase = true))) {
                log("Visszaugrás észlelve az Android Auto vagy a rendszer felől! Azonnali visszakényszerítés...")
                enforceTargetAudioRoute()
            }
        }
    }

    private val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    log("Hívásállapot: RINGING (Bejövő hívás csörög)")
                    handleCallStarted()
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    log("Hívásállapot: OFFHOOK (Aktív beszélgetés vagy tárcsázás)")
                    handleCallStarted()
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    log("Hívásállapot: IDLE (Hívás befejeződött)")
                    handleCallEnded()
                }
            }
            notifyStatusUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        log("Szolgáltatás inicializálása...")
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        prefs = DevicePreferenceManager(this)

        createNotificationChannel()

        audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, deviceChangedListener)
        telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback)

        isRunning = true
        notifyStatusUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            log("Leállítási parancs érkezett az értesítésből.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_TEST_ROUTE) {
            log("Manuális teszt parancs: azonnali átirányítás a cél kihangosítóra...")
            enforceTargetAudioRoute()
            return START_STICKY
        }

        if (intent?.action == ACTION_RESET_ROUTE) {
            log("Manuális visszaállítás: Clear communication device...")
            audioManager.clearCommunicationDevice()
            notifyStatusUpdate()
            return START_STICKY
        }

        val notification = buildNotification("Figyelés aktív", "Hívások automatikus átirányítása a kihangosítóra")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, serviceTypes)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        log("Szolgáltatás előtérben fut (Anti-Revert Watchdog készenlétben).")
        return START_STICKY
    }

    private fun handleCallStarted() {
        if (!isCallActive) {
            isCallActive = true
            log("Hívás indult! Cél kihangosító kényszerítése és Watchdog indítása...")
            enforceTargetAudioRoute()
            startWatchdog()
            updateNotification("Hívás folyamatban", "Audio kényszerítve a külön kihangosítóra")
        }
    }

    private fun handleCallEnded() {
        if (isCallActive) {
            isCallActive = false
            stopWatchdog()
            log("Hívás véget ért. Kommunikációs eszköz törlése (visszaadás a médiának / Yuehoo-nak)...")
            audioManager.clearCommunicationDevice()
            updateNotification("Figyelés aktív", "Hívások automatikus átirányítása a kihangosítóra")
        }
    }

    fun enforceTargetAudioRoute(): Boolean {
        val targetMac = prefs.targetSpeakerMac
        if (targetMac.isNullOrEmpty()) {
            log("HIBA: Nincs cél kihangosító MAC cím beállítva!")
            return false
        }

        val availableDevices = audioManager.availableCommunicationDevices
        val targetDevice = availableDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    device.address.equals(targetMac, ignoreCase = true)
        } ?: availableDevices.firstOrNull { device ->
            // Fallback: cím nélküli egyezés esetén típus vizsgálat
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        if (targetDevice == null) {
            log("FIGYELEM: A cél BT kihangosító ($targetMac) nem található az aktív SCO eszközök között! Csatlakoztatva van?")
            return false
        }

        val currentDevice = audioManager.communicationDevice
        if (currentDevice?.id == targetDevice.id) {
            // Már jó eszközön van
            return true
        }

        val success = audioManager.setCommunicationDevice(targetDevice)
        log("setCommunicationDevice -> ${targetDevice.productName} [${targetDevice.address}], siker: $success")
        notifyStatusUpdate()
        return success
    }

    private fun startWatchdog() {
        if (!isWatchdogRunning) {
            isWatchdogRunning = true
            mainHandler.post(watchdogRunnable)
        }
    }

    private fun stopWatchdog() {
        isWatchdogRunning = false
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val stopIntent = Intent(this, AudioRoutingService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Leállítás", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopWatchdog()
        audioManager.removeOnCommunicationDeviceChangedListener(deviceChangedListener)
        telephonyManager.unregisterTelephonyCallback(telephonyCallback)
        audioManager.clearCommunicationDevice()
        log("Szolgáltatás leállítva.")
        notifyStatusUpdate()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "bt_audio_router_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP_SERVICE = "com.antigravity.btaudiorouter.ACTION_STOP"
        const val ACTION_TEST_ROUTE = "com.antigravity.btaudiorouter.ACTION_TEST"
        const val ACTION_RESET_ROUTE = "com.antigravity.btaudiorouter.ACTION_RESET"
        const val WATCHDOG_INTERVAL_MS = 500L

        var isRunning = false
            private set

        var statusListener: (() -> Unit)? = null
        var logListener: ((String) -> Unit)? = null

        fun log(msg: String) {
            Log.d("BTAudioRouter", msg)
            mainHandlerStatic.post {
                logListener?.invoke(msg)
            }
        }

        private val mainHandlerStatic = Handler(Looper.getMainLooper())

        fun notifyStatusUpdate() {
            mainHandlerStatic.post {
                statusListener?.invoke()
            }
        }
    }
}

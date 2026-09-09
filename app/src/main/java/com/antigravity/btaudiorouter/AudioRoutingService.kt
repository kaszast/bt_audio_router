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

/**
 * [AudioRoutingService]
 *
 * Háttérben futó előtér-szolgáltatás (Foreground Service).
 */
class AudioRoutingService : Service() {

    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var prefs: DevicePreferenceManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCallActive = false
    private var isTestMode = false
    private var isWatchdogRunning = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isCallActive || isTestMode) {
                enforceTargetAudioRoute()
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private val testResetRunnable = Runnable {
        log(getString(R.string.test_mode_ended))
        isTestMode = false
        stopWatchdog()
        audioManager.clearCommunicationDevice()
        notifyStatusUpdate()
        updatePersistentNotification()
    }

    private val deviceChangedListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        log("Communication device changed: ${device?.productName ?: "None"} (${device?.address ?: "-"})")
        notifyStatusUpdate()
        updatePersistentNotification()

        if (isCallActive || isTestMode) {
            val targetMac = prefs.targetSpeakerMac
            if (targetMac != null && (device == null || !device.address.equals(targetMac, ignoreCase = true))) {
                log("Revert detected! Re-enforcing audio route...")
                enforceTargetAudioRoute()
            }
        }
    }

    private val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    log("Call State: RINGING")
                    handleCallStarted()
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    log("Call State: OFFHOOK")
                    handleCallStarted()
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    log("Call State: IDLE")
                    handleCallEnded()
                }
            }
            notifyStatusUpdate()
            updatePersistentNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        log("Initializing service...")

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
            log("Stop command received from notification.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_TEST_ROUTE) {
            val targetName = prefs.targetSpeakerName ?: prefs.targetSpeakerMac ?: getString(R.string.not_selected)
            log(getString(R.string.test_mode_active, targetName))
            isTestMode = true
            enforceTargetAudioRoute()
            startWatchdog()
            mainHandler.removeCallbacks(testResetRunnable)
            mainHandler.postDelayed(testResetRunnable, TEST_DURATION_MS)
            updatePersistentNotification()
            return START_STICKY
        }

        if (intent?.action == ACTION_RESET_ROUTE) {
            log("Reset command: Clear communication device...")
            isTestMode = false
            stopWatchdog()
            audioManager.clearCommunicationDevice()
            notifyStatusUpdate()
            updatePersistentNotification()
            return START_STICKY
        }

        if (intent?.action == ACTION_REFRESH_NOTIFICATION) {
            updatePersistentNotification()
            return START_STICKY
        }

        val notification = createCurrentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        log("Service running in foreground.")
        return START_STICKY
    }

    private fun handleCallStarted() {
        if (!isCallActive) {
            isCallActive = true
            log("Call started! Enforcing target handsfree and starting Watchdog...")
            enforceTargetAudioRoute()
            startWatchdog()
            updatePersistentNotification()
        }
    }

    private fun handleCallEnded() {
        if (isCallActive || isTestMode) {
            isCallActive = false
            isTestMode = false
            stopWatchdog()
            mainHandler.removeCallbacks(testResetRunnable)
            log("Call ended. Clearing communication device...")
            audioManager.clearCommunicationDevice()
            updatePersistentNotification()
        }
    }

    /**
     * Enforces the target Bluetooth SCO communication device.
     * MUST ONLY be called during an active call (`isCallActive == true`) or active manual test mode (`isTestMode == true`).
     */
    fun enforceTargetAudioRoute(): Boolean {
        if (!isCallActive && !isTestMode) {
            // Idle state: do not force communication device!
            return false
        }

        val targetMac = prefs.targetSpeakerMac
        if (targetMac.isNullOrEmpty()) {
            log("ERROR: No target speaker MAC set!")
            return false
        }

        val availableDevices = audioManager.availableCommunicationDevices
        val targetDevice = availableDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    device.address.equals(targetMac, ignoreCase = true)
        } ?: availableDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        if (targetDevice == null) {
            log("WARNING: Target BT speaker ($targetMac) not found in active SCO devices!")
            return false
        }

        val currentDevice = audioManager.communicationDevice
        if (currentDevice?.id == targetDevice.id) {
            return true
        }

        val success = audioManager.setCommunicationDevice(targetDevice)
        log("setCommunicationDevice -> ${targetDevice.productName} [${targetDevice.address}], success: $success")
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

    fun updatePersistentNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createCurrentNotification())
    }

    private fun createCurrentNotification(): Notification {
        val notSelectedStr = getString(R.string.not_selected)
        val unknownDevStr = getString(R.string.unknown_device)

        val aaDevice = when {
            !prefs.sourceAaName.isNullOrEmpty() -> "${prefs.sourceAaName} (${prefs.sourceAaMac ?: "-"})"
            !prefs.sourceAaMac.isNullOrEmpty() -> prefs.sourceAaMac!!
            else -> notSelectedStr
        }

        val targetDevice = when {
            !prefs.targetSpeakerName.isNullOrEmpty() -> "${prefs.targetSpeakerName} (${prefs.targetSpeakerMac ?: "-"})"
            !prefs.targetSpeakerMac.isNullOrEmpty() -> prefs.targetSpeakerMac!!
            else -> notSelectedStr
        }

        val activeCallState = isCallActive || isTestMode

        val title = if (activeCallState) {
            getString(R.string.notif_title_call)
        } else {
            getString(R.string.notif_title_active)
        }

        val shortText = if (activeCallState) {
            getString(R.string.notif_short_call, prefs.targetSpeakerName ?: prefs.targetSpeakerMac ?: unknownDevStr)
        } else {
            getString(R.string.notif_short_idle, prefs.targetSpeakerName ?: notSelectedStr, prefs.sourceAaName ?: notSelectedStr)
        }

        val stateDescription = if (activeCallState) {
            getString(R.string.notif_desc_call)
        } else {
            getString(R.string.notif_desc_idle)
        }

        val bigText = getString(R.string.notif_big_text, stateDescription, aaDevice, targetDevice)

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
            .setContentText(shortText)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop_btn), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopWatchdog()
        mainHandler.removeCallbacks(testResetRunnable)
        audioManager.removeOnCommunicationDeviceChangedListener(deviceChangedListener)
        telephonyManager.unregisterTelephonyCallback(telephonyCallback)
        audioManager.clearCommunicationDevice()
        log("Service stopped.")
        notifyStatusUpdate()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "bt_audio_router_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP_SERVICE = "com.antigravity.btaudiorouter.ACTION_STOP"
        const val ACTION_TEST_ROUTE = "com.antigravity.btaudiorouter.ACTION_TEST"
        const val ACTION_RESET_ROUTE = "com.antigravity.btaudiorouter.ACTION_RESET"
        const val ACTION_REFRESH_NOTIFICATION = "com.antigravity.btaudiorouter.ACTION_REFRESH_NOTIF"
        const val WATCHDOG_INTERVAL_MS = 500L
        const val TEST_DURATION_MS = 5000L

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

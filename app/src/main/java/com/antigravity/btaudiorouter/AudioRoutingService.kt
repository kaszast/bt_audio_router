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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import java.util.Locale

/**
 * [AudioRoutingService]
 *
 * Háttérben futó előtér-szolgáltatás (Foreground Service).
 */
class AudioRoutingService : Service(), TextToSpeech.OnInitListener {

    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var prefs: DevicePreferenceManager

    private var tts: TextToSpeech? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCallActive = false
    private var isTestMode = false
    private var isWatchdogRunning = false

    /** True, ha az audio módot MI állítottuk (csak tesztmódban) — csak ilyenkor állítjuk vissza. */
    private var didSetAudioMode = false

    /** Ismétlődő "cél nem található" naplóüzenetek elnyomására (a watchdog 500 ms-onként fut). */
    private var hasLoggedTargetMissing = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isCallActive || isTestMode) {
                enforceTargetAudioRoute()
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private val testTimeoutRunnable = Runnable {
        log(getString(R.string.test_mode_tts_success))
        isTestMode = false
        stopWatchdog()
        clearAudioRoute()
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

        tts = TextToSpeech(applicationContext, this)

        isRunning = true
        notifyStatusUpdate()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.getDefault())
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "test_media_1") {
                        mainHandler.post {
                            log(getString(R.string.test_mode_tts_success))
                            isTestMode = false
                            stopWatchdog()
                            clearAudioRoute()
                            notifyStatusUpdate()
                            updatePersistentNotification()
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            log("Stop command received from notification.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_TEST_ROUTE) {
            performAudioChannelsTtsTest()
            return START_STICKY
        }

        if (intent?.action == ACTION_RESET_ROUTE) {
            log("Reset command: Clear communication device...")
            isTestMode = false
            stopWatchdog()
            tts?.stop()
            clearAudioRoute()
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

    /**
     * SCO Teszt Text-To-Speech (TTS) felolvasással:
     * 1-szer kimondja a híváscsatornán (STREAM_VOICE_CALL), majd 1-szer a médiacsatornán (STREAM_MUSIC).
     */
    private fun performAudioChannelsTtsTest() {
        log(getString(R.string.test_mode_tts_start))
        isTestMode = true
        hasLoggedTargetMissing = false

        // Éles hívásnál a telefónia stack adja az audio módot; teszt közben nekünk kell
        // MODE_IN_COMMUNICATION-be tenni, hogy a kommunikációs eszköz kiválasztása érvényesüljön.
        if (prefs.isCallRoutingEnabled) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            didSetAudioMode = true
        }

        enforceTargetAudioRoute()
        startWatchdog()

        val callText = getString(R.string.test_call_channel_tts)
        val mediaText = getString(R.string.test_media_channel_tts)

        mainHandler.postDelayed({
            if (tts != null) {
                // 1. Híváscsatorna tesztelése (1x)
                if (prefs.isCallRoutingEnabled) {
                    val callBundle = Bundle().apply {
                        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
                    }
                    tts?.speak(callText, TextToSpeech.QUEUE_ADD, callBundle, "test_call_1")
                }

                // 2. Médiacsatorna tesztelése (1x)
                if (prefs.isMediaRoutingEnabled) {
                    val mediaBundle = Bundle().apply {
                        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                    }
                    tts?.speak(mediaText, TextToSpeech.QUEUE_ADD, mediaBundle, "test_media_1")
                }
            }

            mainHandler.removeCallbacks(testTimeoutRunnable)
            mainHandler.postDelayed(testTimeoutRunnable, 8000L)
            updatePersistentNotification()
        }, 500)
    }

    private fun handleCallStarted() {
        if (!isCallActive) {
            isCallActive = true
            hasLoggedTargetMissing = false
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
            mainHandler.removeCallbacks(testTimeoutRunnable)
            tts?.stop()
            log("Call ended. Clearing audio route...")
            clearAudioRoute()
            updatePersistentNotification()
        }
    }

    private fun getAudioModeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "MODE_NORMAL ($mode)"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE ($mode)"
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL ($mode)"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION ($mode)"
        else -> "MODE_OTHER ($mode)"
    }

    private fun getCallStateName(state: Int): String = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> "RINGING ($state)"
        TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK ($state)"
        TelephonyManager.CALL_STATE_IDLE -> "IDLE ($state)"
        else -> "UNKNOWN ($state)"
    }

    private fun getDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "TYPE_BUILTIN_EARPIECE ($type)"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER ($type)"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET ($type)"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "TYPE_WIRED_HEADPHONES ($type)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO ($type)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP ($type)"
        AudioDeviceInfo.TYPE_HDMI -> "TYPE_HDMI ($type)"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TYPE_TELEPHONY ($type)"
        AudioDeviceInfo.TYPE_BUS -> "TYPE_BUS ($type)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "TYPE_BLE_HEADSET ($type)"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "TYPE_BLE_SPEAKER ($type)"
        else -> "TYPE_OTHER ($type)"
    }

    @Suppress("DEPRECATION")
    private fun logDetailedDiagnostics() {
        val sb = StringBuilder()
        sb.append("\n=== DIAGNOSTICS [ROUTING_ATTEMPT] ===\n")
        sb.append("OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Audio Mode: ${getAudioModeName(audioManager.mode)} | BT SCO: ${audioManager.isBluetoothScoOn} | Speaker: ${audioManager.isSpeakerphoneOn}\n")
        sb.append("Call State: ${getCallStateName(telephonyManager.callState)}\n")
        sb.append("Target Config: [${prefs.targetSpeakerName}] MAC=[${prefs.targetSpeakerMac}]\n")
        sb.append("Source AA Config: [${prefs.sourceAaName}] MAC=[${prefs.sourceAaMac}]\n")

        val commDev = audioManager.communicationDevice
        sb.append("Active Comm Dev: ${if (commDev != null) "${commDev.productName} [${getDeviceTypeName(commDev.type)}, id=${commDev.id}, addr=${commDev.address}]" else "NONE/NULL"}\n")

        val commDevices = audioManager.availableCommunicationDevices
        sb.append("Available Comm Devices (${commDevices.size}):\n")
        commDevices.forEachIndexed { i, dev ->
            sb.append("  $i) [${dev.productName}] ${getDeviceTypeName(dev.type)} id=${dev.id} addr=[${dev.address}]\n")
        }

        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        sb.append("All Output Devices (${outputDevices.size}):\n")
        outputDevices.forEachIndexed { i, dev ->
            sb.append("  $i) [${dev.productName}] ${getDeviceTypeName(dev.type)} id=${dev.id} addr=[${dev.address}]\n")
        }
        sb.append("=========================\n")

        log(sb.toString())
    }

    /**
     * Megkeresi a cél kommunikációs (SCO) eszközt.
     *
     * A forrás (Android Auto) eszközt MAC alapján zárjuk ki. Ha van mentett cél MAC,
     * KIZÁRÓLAG az alapján illesztünk — a névalapú illesztés téves eszközre találhat.
     */
    private fun findTargetCommunicationDevice(targetMac: String, targetName: String): AudioDeviceInfo? {
        val sourceMac = prefs.sourceAaMac ?: ""

        val candidates = audioManager.availableCommunicationDevices.filter { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    (sourceMac.isEmpty() || !device.address.equals(sourceMac, ignoreCase = true))
        }

        if (targetMac.isNotEmpty()) {
            return candidates.firstOrNull { it.address.equals(targetMac, ignoreCase = true) }
        }

        return candidates.firstOrNull { it.productName.toString().equals(targetName, ignoreCase = true) }
    }

    /**
     * A cél Bluetooth SCO eszköz kikényszerítése kommunikációs eszközként.
     *
     * Az audio módot éles hívásnál NEM állítjuk: azt a telefónia stack kezeli, és a
     * MODE_IN_CALL amúgy is privilegizált mód. Tesztmódban a hívó állítja MODE_IN_COMMUNICATION-re.
     */
    fun enforceTargetAudioRoute(): Boolean {
        if (!isCallActive && !isTestMode) {
            return false
        }

        if (!prefs.isCallRoutingEnabled) {
            return false
        }

        val targetMac = prefs.targetSpeakerMac ?: ""
        val targetName = prefs.targetSpeakerName ?: ""

        if (targetMac.isEmpty() && targetName.isEmpty()) {
            if (!hasLoggedTargetMissing) {
                hasLoggedTargetMissing = true
                log("ERROR: No target speaker configured!")
            }
            return false
        }

        val targetDevice = findTargetCommunicationDevice(targetMac, targetName)

        // Ha nem találjuk a célt, SZIGORÚAN megtagadjuk az átirányítást (soha nem esünk vissza az AA forrásra).
        if (targetDevice == null) {
            if (!hasLoggedTargetMissing) {
                hasLoggedTargetMissing = true
                logDetailedDiagnostics()
                log("WARNING: Target BT speaker [$targetName / $targetMac] NOT found in available SCO devices! AA source strictly excluded.")
            }
            return false
        }

        hasLoggedTargetMissing = false

        // A watchdog 500 ms-onként fut: ha már a célon vagyunk, ne csináljunk és ne naplózzunk semmit.
        val currentDevice = audioManager.communicationDevice
        if (currentDevice != null && currentDevice.id == targetDevice.id) {
            return true
        }

        logDetailedDiagnostics()

        try {
            val success = audioManager.setCommunicationDevice(targetDevice)
            log("ROUTING -> ${targetDevice.productName} [${targetDevice.address}] (id=${targetDevice.id}), setCommunicationDevice: $success")
            notifyStatusUpdate()
            return success
        } catch (e: Exception) {
            log("Error enforcing target audio route: ${e.message}")
            return false
        }
    }

    private fun clearAudioRoute() {
        try {
            audioManager.clearCommunicationDevice()
            // Az audio módhoz csak akkor nyúlunk, ha mi magunk állítottuk (tesztmód).
            if (didSetAudioMode) {
                audioManager.mode = AudioManager.MODE_NORMAL
                didSetAudioMode = false
            }
        } catch (e: Exception) {
            log("Error clearing audio route: ${e.message}")
        }
        hasLoggedTargetMissing = false
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
        mainHandler.removeCallbacks(testTimeoutRunnable)
        tts?.stop()
        tts?.shutdown()
        audioManager.removeOnCommunicationDeviceChangedListener(deviceChangedListener)
        telephonyManager.unregisterTelephonyCallback(telephonyCallback)
        clearAudioRoute()
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

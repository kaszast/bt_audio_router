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
 * Ez az alkalmazás magja: egy előtérben futó szolgáltatás (Foreground Service).
 *
 * Működése és feladatai:
 * 1. Figyeli a telefon hívásállapotát a [TelephonyCallback] segítségével.
 * 2. Ha hívás indul (csörög vagy aktív beszélgetés), lekéri a csatlakoztatott Bluetooth eszközöket.
 * 3. A hívás hangját átkényszeríti a célként kiválasztott Bluetooth kihangosítóra
 *    az [AudioManager.setCommunicationDevice] API segítségével.
 * 4. **Anti-Revert Watchdog**: Egy időzítő (500 ms) segítségével hívás alatt folyamatosan
 *    biztosítja az útvonalat, így ha az Android Auto vagy a fejegység visszavenné a hangot,
 *    a szolgáltatás azonnal felülbírálja és visszatéríti azt a kihangosítóra.
 * 5. A hívás végén alaphelyzetbe állítja az audio útvonalat ([AudioManager.clearCommunicationDevice]).
 *
 * A Junior fejlesztőknek:
 * - Az Android 12+ (API 31) óta az [AudioManager.setCommunicationDevice] a hivatalos API
 *   a hívási audio útvonalak irányítására a régi `startBluetoothSco()` helyett.
 * - A Foreground Service-hez kötelező folyamatos értesítést (Notification) megjeleníteni,
 *   hogy az Android rendszer ne állítsa le az alkalmazást memóriahiány esetén.
 */
class AudioRoutingService : Service() {

    // Rendszerszolgáltatások hivatkozásai
    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var prefs: DevicePreferenceManager

    // Handler a főszálon (UI thread) futó időzített feladatokhoz (Watchdog)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Állapotváltozók
    private var isCallActive = false
    private var isWatchdogRunning = false

    /**
     * Anti-Revert Watchdog ciklus.
     * Amíg a hívás aktív (`isCallActive == true`), 500 milliszekundumonként
     * újrahívja az [enforceTargetAudioRoute] függvényt.
     */
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isCallActive) {
                enforceTargetAudioRoute()
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    /**
     * Eseményfigyelő: Észleli, ha a rendszerben megváltozik az aktív kommunikációs eszköz.
     * Ha hívás közben az Android Auto vagy a rendszer átállítja az eszközt másra,
     * ez a listener azonnal észleli és visszakényszeríti a kiválasztott kihangosítóra.
     */
    private val deviceChangedListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        log("Communication device megváltozott: ${device?.productName ?: "Nincs"} (${device?.address ?: "-"})")
        notifyStatusUpdate()

        if (isCallActive) {
            val targetMac = prefs.targetSpeakerMac
            // Ha az aktív eszköz nem a cél kihangosító, azonnal visszairányítunk
            if (targetMac != null && (device == null || !device.address.equals(targetMac, ignoreCase = true))) {
                log("Visszaugrás észlelve az Android Auto vagy a rendszer felől! Azonnali visszakényszerítés...")
                enforceTargetAudioRoute()
            }
        }
    }

    /**
     * Telefonállapot-figyelő callback (Android 12 / API 31+).
     * Figyeli, hogy mikor csörög a telefon, mikor veszik fel, és mikor teszik le.
     */
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

    /**
     * A szolgáltatás inicializálása. Csak egyszer fut le a Service élettartama során.
     */
    override fun onCreate() {
        super.onCreate()
        log("Szolgáltatás inicializálása...")

        // Rendszerszolgáltatások lekérése
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        prefs = DevicePreferenceManager(this)

        // Értesítési csatorna (NotificationChannel) létrehozása Android 8.0+ rendszerekhez
        createNotificationChannel()

        // Figyelők regisztrálása
        audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, deviceChangedListener)
        telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback)

        isRunning = true
        notifyStatusUpdate()
    }

    /**
     * Akkor hívódik meg, amikor a szolgáltatást elindítják a `startService()` vagy `startForegroundService()` segítségével.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Leállítási akció kezeleése az értesítésben található gombra kattintva
        if (intent?.action == ACTION_STOP_SERVICE) {
            log("Leállítási parancs érkezett az értesítésből.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Manuális teszt gomb kezelése a főképernyőről
        if (intent?.action == ACTION_TEST_ROUTE) {
            log("Manuális teszt parancs: azonnali átirányítás a cél kihangosítóra...")
            enforceTargetAudioRoute()
            return START_STICKY
        }

        // Manuális alaphelyzetbe állítás gomb kezelése
        if (intent?.action == ACTION_RESET_ROUTE) {
            log("Manuális visszaállítás: Clear communication device...")
            audioManager.clearCommunicationDevice()
            notifyStatusUpdate()
            return START_STICKY
        }

        // Előtér-szolgáltatás (Foreground Service) elindítása kötelező értesítéssel
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

        // START_STICKY: Ha a rendszer memóriahiány miatt leállítaná a Service-t, újraindítja amint van elég erőforrás
        return START_STICKY
    }

    /**
     * Hívás kezdetekor lefutó logika.
     */
    private fun handleCallStarted() {
        if (!isCallActive) {
            isCallActive = true
            log("Hívás indult! Cél kihangosító kényszerítése és Watchdog indítása...")
            enforceTargetAudioRoute()
            startWatchdog()
            updateNotification("Hívás folyamatban", "Audio kényszerítve a külön kihangosítóra")
        }
    }

    /**
     * Hívás befejezésekor lefutó logika.
     */
    private fun handleCallEnded() {
        if (isCallActive) {
            isCallActive = false
            stopWatchdog()
            log("Hívás véget ért. Kommunikációs eszköz törlése (visszaadás a médiának / Yuehoo-nak)...")

            // Eltávolítjuk a kényszerített kommunikációs eszközt, így a rendszer visszatér az alapértelmezett beállításhoz
            audioManager.clearCommunicationDevice()
            updateNotification("Figyelés aktív", "Hívások automatikus átirányítása a kihangosítóra")
        }
    }

    /**
     * Megkeresi az elérhető kommunikációs eszközök között a cél Bluetooth kihangosítót a mentett MAC cím alapján,
     * és beállítja azt az aktív hívási audio-útvonalnak.
     *
     * @return `true` ha a beállítás sikeres volt, `false` ha nem található az eszköz.
     */
    fun enforceTargetAudioRoute(): Boolean {
        val targetMac = prefs.targetSpeakerMac
        if (targetMac.isNullOrEmpty()) {
            log("HIBA: Nincs cél kihangosító MAC cím beállítva!")
            return false
        }

        // Lekérjük a jelenleg elérhető kommunikációs eszközöket (pl. Bluetooth SCO headsetek, fülhangszóró, stb.)
        val availableDevices = audioManager.availableCommunicationDevices

        // Elsődlegesen MAC cím alapján keressük meg a csatlakoztatott Bluetooth SCO eszközt
        val targetDevice = availableDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    device.address.equals(targetMac, ignoreCase = true)
        } ?: availableDevices.firstOrNull { device ->
            // Fallback: Ha a rendszer nem adja vissza a MAC címet, bármelyik aktív Bluetooth SCO eszközt kiválasztjuk
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        if (targetDevice == null) {
            log("FIGYELEM: A cél BT kihangosító ($targetMac) nem található az aktív SCO eszközök között! Csatlakoztatva van?")
            return false
        }

        val currentDevice = audioManager.communicationDevice
        if (currentDevice?.id == targetDevice.id) {
            // Már a megfelelő eszköz van kiválasztva, nincs teendő
            return true
        }

        // Beállítjuk a kiválasztott eszközt hívási kommunikációs eszközként
        val success = audioManager.setCommunicationDevice(targetDevice)
        log("setCommunicationDevice -> ${targetDevice.productName} [${targetDevice.address}], siker: $success")
        notifyStatusUpdate()
        return success
    }

    /**
     * Elindítja a periodikus Watchdog ciklust.
     */
    private fun startWatchdog() {
        if (!isWatchdogRunning) {
            isWatchdogRunning = true
            mainHandler.post(watchdogRunnable)
        }
    }

    /**
     * Leállítja a Watchdog ciklust és törli az időzített feladatot a szálkezelőből.
     */
    private fun stopWatchdog() {
        isWatchdogRunning = false
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    /**
     * Értesítési csatorna (NotificationChannel) létrehozása Android 8.0 (Oreo) feletti rendszerekhez.
     */
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

    /**
     * Megépíti a folyamatos előtér-értesítést.
     */
    private fun buildNotification(title: String, text: String): Notification {
        // Leállítás gomb szándéka (PendingIntent)
        val stopIntent = Intent(this, AudioRoutingService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Értesítésre kattintáskor a MainActivity nyílik meg
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

    /**
     * Frissíti az előtér-értesítés szövegét (pl. hívás indításakor).
     */
    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    /**
     * A szolgáltatás leállásakor lefutó takarító logika.
     */
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopWatchdog()

        // Eseményfigyelők leiratkoztatása az erőforrás-szivárgások (memory leak) megelőzésére
        audioManager.removeOnCommunicationDeviceChangedListener(deviceChangedListener)
        telephonyManager.unregisterTelephonyCallback(telephonyCallback)

        // Audio útvonal törlése
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

        /**
         * Statikus állapotjelző, hogy a UI tudja, fut-e a szolgáltatás.
         */
        var isRunning = false
            private set

        /**
         * Callback-ek a UI frissítéséhez és naplózásához.
         */
        var statusListener: (() -> Unit)? = null
        var logListener: ((String) -> Unit)? = null

        private val mainHandlerStatic = Handler(Looper.getMainLooper())

        /**
         * Üzenet küldése a Logcat-re és a felhasználói felület naplójába.
         */
        fun log(msg: String) {
            Log.d("BTAudioRouter", msg)
            mainHandlerStatic.post {
                logListener?.invoke(msg)
            }
        }

        /**
         * Értesíti a UI-t, hogy frissítse a megjelenített állapotokat (aktív eszköz, hívásállapot).
         */
        fun notifyStatusUpdate() {
            mainHandlerStatic.post {
                statusListener?.invoke()
            }
        }
    }
}

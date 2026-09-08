package com.antigravity.btaudiorouter

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: DevicePreferenceManager
    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager

    private lateinit var layoutPermissions: LinearLayout
    private lateinit var btnGrantPermissions: Button
    private lateinit var spinnerSourceAA: Spinner
    private lateinit var spinnerTargetSpeaker: Spinner
    private lateinit var btnRefreshDevices: Button
    private lateinit var switchService: Switch
    private lateinit var tvCallState: TextView
    private lateinit var tvActiveDevice: TextView
    private lateinit var btnTestRoute: Button
    private lateinit var btnResetRoute: Button
    private lateinit var tvLog: TextView

    private val pairedDevices = mutableListOf<BtDeviceItem>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    data class BtDeviceItem(val name: String, val mac: String) {
        override fun toString(): String = "$name ($mac)"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = DevicePreferenceManager(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        loadPairedDevices()
        updateStatus()

        AudioRoutingService.statusListener = {
            runOnUiThread { updateStatus() }
        }

        AudioRoutingService.logListener = { msg ->
            runOnUiThread { appendLog(msg) }
        }
    }

    override fun onPause() {
        super.onPause()
        AudioRoutingService.statusListener = null
        AudioRoutingService.logListener = null
    }

    private fun initViews() {
        layoutPermissions = findViewById(R.id.layoutPermissions)
        btnGrantPermissions = findViewById(R.id.btnGrantPermissions)
        spinnerSourceAA = findViewById(R.id.spinnerSourceAA)
        spinnerTargetSpeaker = findViewById(R.id.spinnerTargetSpeaker)
        btnRefreshDevices = findViewById(R.id.btnRefreshDevices)
        switchService = findViewById(R.id.switchService)
        tvCallState = findViewById(R.id.tvCallState)
        tvActiveDevice = findViewById(R.id.tvActiveDevice)
        btnTestRoute = findViewById(R.id.btnTestRoute)
        btnResetRoute = findViewById(R.id.btnResetRoute)
        tvLog = findViewById(R.id.tvLog)

        switchService.isChecked = AudioRoutingService.isRunning
    }

    private fun setupListeners() {
        btnGrantPermissions.setOnClickListener {
            requestRequiredPermissions()
        }

        btnRefreshDevices.setOnClickListener {
            loadPairedDevices()
            appendLog("Párosított eszközök frissítve.")
        }

        switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.isServiceEnabled = isChecked
            if (isChecked) {
                if (!AudioRoutingService.isRunning) {
                    startAudioService()
                }
            } else {
                if (AudioRoutingService.isRunning) {
                    stopAudioService()
                }
            }
            updateStatus()
        }

        btnTestRoute.setOnClickListener {
            appendLog("Manuális teszt indítása...")
            val intent = Intent(this, AudioRoutingService::class.java).apply {
                action = AudioRoutingService.ACTION_TEST_ROUTE
            }
            if (AudioRoutingService.isRunning) {
                startService(intent)
            } else {
                startAudioService(intent)
            }
        }

        btnResetRoute.setOnClickListener {
            appendLog("Audio útvonal visszaállítása alaphelyzetbe...")
            val intent = Intent(this, AudioRoutingService::class.java).apply {
                action = AudioRoutingService.ACTION_RESET_ROUTE
            }
            if (AudioRoutingService.isRunning) {
                startService(intent)
            } else {
                audioManager.clearCommunicationDevice()
                updateStatus()
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val required = getRequiredPermissions()
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val hasAll = missing.isEmpty()
        layoutPermissions.visibility = if (hasAll) View.GONE else View.VISIBLE
        return hasAll
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions
    }

    private fun requestRequiredPermissions() {
        val missing = getRequiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkPermissions()
        loadPairedDevices()
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        if (!hasBtConnectPermission()) return

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = btManager.adapter

        pairedDevices.clear()
        val bonded: Set<BluetoothDevice>? = adapter?.bondedDevices
        if (bonded != null) {
            for (dev in bonded) {
                val name = dev.name ?: "Ismeretlen eszköz"
                val mac = dev.address
                pairedDevices.add(BtDeviceItem(name, mac))
            }
        }

        val adapterList = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pairedDevices)
        spinnerSourceAA.adapter = adapterList
        spinnerTargetSpeaker.adapter = adapterList

        // Korábban mentett eszközök kiválasztása
        val savedAaMac = prefs.sourceAaMac
        val savedTargetMac = prefs.targetSpeakerMac

        val aaIdx = pairedDevices.indexOfFirst { it.mac.equals(savedAaMac, ignoreCase = true) }
        if (aaIdx >= 0) spinnerSourceAA.setSelection(aaIdx)

        val targetIdx = pairedDevices.indexOfFirst { it.mac.equals(savedTargetMac, ignoreCase = true) }
        if (targetIdx >= 0) spinnerTargetSpeaker.setSelection(targetIdx)

        spinnerSourceAA.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                if (pos in pairedDevices.indices) {
                    val item = pairedDevices[pos]
                    prefs.sourceAaMac = item.mac
                    appendLog("Android Auto (forrás) beállítva: ${item.name} [${item.mac}]")
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        spinnerTargetSpeaker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                if (pos in pairedDevices.indices) {
                    val item = pairedDevices[pos]
                    prefs.targetSpeakerMac = item.mac
                    appendLog("Cél BT kihangosító beállítva: ${item.name} [${item.mac}]")
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun hasBtConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun startAudioService(intent: Intent? = null) {
        val serviceIntent = intent ?: Intent(this, AudioRoutingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        switchService.isChecked = true
        appendLog("Szolgáltatás elindítva.")
    }

    private fun stopAudioService() {
        stopService(Intent(this, AudioRoutingService::class.java))
        switchService.isChecked = false
        appendLog("Szolgáltatás leállítva.")
    }

    private fun updateStatus() {
        switchService.isChecked = AudioRoutingService.isRunning

        val commDev: AudioDeviceInfo? = audioManager.communicationDevice
        val devText = if (commDev != null) {
            val typeStr = when (commDev.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Hangszóró"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Telefon fülhangszóró"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Vezetékes headset"
                else -> "Egyéb (${commDev.type})"
            }
            "${commDev.productName ?: "Névtelen"} [$typeStr - ${commDev.address ?: "-"}]"
        } else {
            "Rendszer alapértelmezett (Nem aktív SCO)"
        }
        tvActiveDevice.text = "Aktív audio eszköz: $devText"

        val callState = if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            when (telephonyManager.callState) {
                TelephonyManager.CALL_STATE_RINGING -> "RINGING (Bejövő hívás)"
                TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK (Hívás aktív)"
                else -> "IDLE (Nincs hívás)"
            }
        } else {
            "Ismeretlen (Nincs engedély)"
        }
        tvCallState.text = "Hívás állapota: $callState"
    }

    private fun appendLog(message: String) {
        val time = timeFormat.format(Date())
        val logLine = "[$time] $message\n"
        tvLog.append(logLine)
    }
}

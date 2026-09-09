package com.antigravity.btaudiorouter

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.text.method.ScrollingMovementMethod
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

/**
 * [MainActivity]
 *
 * Az alkalmazás főképernyője.
 */
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

    private var headsetProxy: BluetoothProfile? = null
    private var a2dpProxy: BluetoothProfile? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HEADSET) headsetProxy = proxy
            if (profile == BluetoothProfile.A2DP) a2dpProxy = proxy
            loadPairedDevices()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) headsetProxy = null
            if (profile == BluetoothProfile.A2DP) a2dpProxy = null
        }
    }

    data class BtDeviceItem(
        val name: String,
        val mac: String,
        val statusText: String
    ) {
        override fun toString(): String = if (mac.isEmpty()) name else "$name ($mac)\n   └ $statusText"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = DevicePreferenceManager(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        initViews()
        setupListeners()
        initBluetoothProxies()
    }

    private fun initBluetoothProxies() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        adapter?.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
        adapter?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
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

    override fun onDestroy() {
        super.onDestroy()
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        headsetProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
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

        tvLog.movementMethod = ScrollingMovementMethod()
        switchService.isChecked = AudioRoutingService.isRunning
    }

    private fun setupListeners() {
        btnGrantPermissions.setOnClickListener {
            requestRequiredPermissions()
        }

        btnRefreshDevices.setOnClickListener {
            loadPairedDevices()
            updateStatus()
            appendLog("Connected Bluetooth devices refreshed.")
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
    private fun getDeviceCapabilitiesAndStatus(
        dev: BluetoothDevice,
        isCallConnected: Boolean,
        isMediaConnected: Boolean
    ): String {
        val btClass = dev.bluetoothClass
        val capabilities = mutableListOf<String>()

        val hasCallCap = btClass?.hasService(BluetoothClass.Service.TELEPHONY) == true ||
                btClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
                btClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
                btClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                isCallConnected

        val hasMediaCap = btClass?.hasService(BluetoothClass.Service.AUDIO) == true ||
                btClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
                btClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER ||
                btClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                isMediaConnected

        val hasHidCap = btClass?.hasService(BluetoothClass.Service.RENDER) == true ||
                btClass?.majorDeviceClass == BluetoothClass.Device.Major.PERIPHERAL

        if (hasCallCap) capabilities.add(getString(R.string.cap_phone_call))
        if (hasMediaCap) capabilities.add(getString(R.string.cap_media_audio))
        if (hasHidCap) capabilities.add(getString(R.string.cap_keyboard_input))

        if (capabilities.isEmpty()) {
            capabilities.add(getString(R.string.cap_general_bt))
        }

        val capText = capabilities.joinToString(" + ")

        val connStatusText = when {
            isCallConnected && isMediaConnected -> getString(R.string.status_connected_both)
            isCallConnected -> getString(R.string.status_connected_call)
            isMediaConnected -> getString(R.string.status_connected_media)
            else -> getString(R.string.status_connected_active)
        }

        return "$capText | $connStatusText"
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        if (!hasBtConnectPermission()) return

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = btManager.adapter

        val connectedHeadset = headsetProxy?.connectedDevices ?: emptyList()
        val connectedA2dp = a2dpProxy?.connectedDevices ?: emptyList()
        val commDevices = audioManager.availableCommunicationDevices

        pairedDevices.clear()
        val bonded: Set<BluetoothDevice>? = adapter?.bondedDevices
        if (bonded != null) {
            for (dev in bonded) {
                val mac = dev.address

                // Csatlakozás ellenőrzése
                val isCallConnected = connectedHeadset.any { it.address.equals(mac, ignoreCase = true) } ||
                        commDevices.any { it.address.equals(mac, ignoreCase = true) }
                val isMediaConnected = connectedA2dp.any { it.address.equals(mac, ignoreCase = true) }
                val isConnected = isCallConnected || isMediaConnected

                // KIZÁRÓLAG A CSATLAKOZTATOTT ESZKÖZÖKET ENGEDJÜK KIVÁLASZTANI
                if (isConnected) {
                    val displayName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !dev.alias.isNullOrEmpty()) {
                        dev.alias!!
                    } else {
                        dev.name ?: getString(R.string.unknown_device)
                    }

                    val infoText = getDeviceCapabilitiesAndStatus(dev, isCallConnected, isMediaConnected)
                    pairedDevices.add(BtDeviceItem(displayName, mac, infoText))
                }
            }
        }

        if (pairedDevices.isEmpty()) {
            pairedDevices.add(BtDeviceItem(getString(R.string.no_connected_devices), "", ""))
        }

        val adapterList = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pairedDevices)
        spinnerSourceAA.adapter = adapterList
        spinnerTargetSpeaker.adapter = adapterList

        val savedAaMac = prefs.sourceAaMac
        val savedTargetMac = prefs.targetSpeakerMac

        val aaIdx = pairedDevices.indexOfFirst { it.mac.isNotEmpty() && it.mac.equals(savedAaMac, ignoreCase = true) }
        if (aaIdx >= 0) spinnerSourceAA.setSelection(aaIdx)

        val targetIdx = pairedDevices.indexOfFirst { it.mac.isNotEmpty() && it.mac.equals(savedTargetMac, ignoreCase = true) }
        if (targetIdx >= 0) spinnerTargetSpeaker.setSelection(targetIdx)

        spinnerSourceAA.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                if (pos in pairedDevices.indices) {
                    val item = pairedDevices[pos]
                    if (item.mac.isNotEmpty() && prefs.sourceAaMac != item.mac) {
                        prefs.sourceAaMac = item.mac
                        prefs.sourceAaName = item.name
                        appendLog("Android Auto: ${item.name} [${item.mac}]")
                        refreshServiceNotification()
                    }
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        spinnerTargetSpeaker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                if (pos in pairedDevices.indices) {
                    val item = pairedDevices[pos]
                    if (item.mac.isNotEmpty() && prefs.targetSpeakerMac != item.mac) {
                        prefs.targetSpeakerMac = item.mac
                        prefs.targetSpeakerName = item.name
                        appendLog("Target Handsfree: ${item.name} [${item.mac}]")
                        refreshServiceNotification()
                        updateStatus()
                    }
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun refreshServiceNotification() {
        if (AudioRoutingService.isRunning) {
            val intent = Intent(this, AudioRoutingService::class.java).apply {
                action = AudioRoutingService.ACTION_REFRESH_NOTIFICATION
            }
            startService(intent)
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
        appendLog("Service started.")
    }

    private fun stopAudioService() {
        stopService(Intent(this, AudioRoutingService::class.java))
        switchService.isChecked = false
        appendLog("Service stopped.")
    }

    private fun updateStatus() {
        switchService.isChecked = AudioRoutingService.isRunning

        val commDev: AudioDeviceInfo? = audioManager.communicationDevice
        val devText = if (commDev != null) {
            val typeStr = when (commDev.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> getString(R.string.dev_type_bt_sco)
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> getString(R.string.dev_type_speaker)
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> getString(R.string.dev_type_earpiece)
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> getString(R.string.dev_type_headset)
                else -> getString(R.string.dev_type_other, commDev.type)
            }
            "${commDev.productName ?: getString(R.string.dev_unnamed)} [$typeStr - ${commDev.address ?: "-"}]"
        } else {
            val targetName = prefs.targetSpeakerName ?: prefs.targetSpeakerMac ?: getString(R.string.not_selected)
            getString(R.string.dev_active_idle_format, targetName)
        }
        tvActiveDevice.text = getString(R.string.active_device_format, devText)

        val callStateStr = if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            when (telephonyManager.callState) {
                TelephonyManager.CALL_STATE_RINGING -> getString(R.string.call_ringing)
                TelephonyManager.CALL_STATE_OFFHOOK -> getString(R.string.call_offhook)
                else -> getString(R.string.call_idle)
            }
        } else {
            getString(R.string.call_unknown_perm)
        }
        tvCallState.text = getString(R.string.call_state_format, callStateStr)
    }

    private fun appendLog(message: String) {
        val time = timeFormat.format(Date())
        val logLine = "[$time] $message\n"
        tvLog.append(logLine)

        tvLog.post {
            val scrollAmount = tvLog.layout?.getLineTop(tvLog.lineCount)?.minus(tvLog.height) ?: 0
            if (scrollAmount > 0) {
                tvLog.scrollTo(0, scrollAmount)
            }
        }
    }
}

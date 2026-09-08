package com.antigravity.btaudiorouter

import android.content.Context
import android.content.SharedPreferences

class DevicePreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("bt_audio_router_prefs", Context.MODE_PRIVATE)

    var sourceAaMac: String?
        get() = prefs.getString(KEY_SOURCE_AA_MAC, null)
        set(value) = prefs.edit().putString(KEY_SOURCE_AA_MAC, value).apply()

    var targetSpeakerMac: String?
        get() = prefs.getString(KEY_TARGET_SPEAKER_MAC, null)
        set(value) = prefs.edit().putString(KEY_TARGET_SPEAKER_MAC, value).apply()

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    companion object {
        private const val KEY_SOURCE_AA_MAC = "key_source_aa_mac"
        private const val KEY_TARGET_SPEAKER_MAC = "key_target_speaker_mac"
        private const val KEY_SERVICE_ENABLED = "key_service_enabled"
    }
}

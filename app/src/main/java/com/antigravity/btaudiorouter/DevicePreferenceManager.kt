package com.antigravity.btaudiorouter

import android.content.Context
import android.content.SharedPreferences

/**
 * [DevicePreferenceManager]
 *
 * Ez az osztály felelős az alkalmazás beállításainak és a kiválasztott Bluetooth
 * eszközök nevének és MAC címeinek tartós tárolásáért az Android [SharedPreferences] API-ján keresztül.
 */
class DevicePreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("bt_audio_router_prefs", Context.MODE_PRIVATE)

    /**
     * A csatlakoztatott Android Auto (forrás) fejegység Bluetooth MAC címe.
     */
    var sourceAaMac: String?
        get() = prefs.getString(KEY_SOURCE_AA_MAC, null)
        set(value) = prefs.edit().putString(KEY_SOURCE_AA_MAC, value).apply()

    /**
     * A csatlakoztatott Android Auto (forrás) fejegység neve.
     */
    var sourceAaName: String?
        get() = prefs.getString(KEY_SOURCE_AA_NAME, null)
        set(value) = prefs.edit().putString(KEY_SOURCE_AA_NAME, value).apply()

    /**
     * A hívásokhoz kiválasztott dedikált Bluetooth kihangosító (cél) MAC címe.
     */
    var targetSpeakerMac: String?
        get() = prefs.getString(KEY_TARGET_SPEAKER_MAC, null)
        set(value) = prefs.edit().putString(KEY_TARGET_SPEAKER_MAC, value).apply()

    /**
     * A hívásokhoz kiválasztott dedikált Bluetooth kihangosító (cél) neve.
     */
    var targetSpeakerName: String?
        get() = prefs.getString(KEY_TARGET_SPEAKER_NAME, null)
        set(value) = prefs.edit().putString(KEY_TARGET_SPEAKER_NAME, value).apply()

    /**
     * Jelzi, hogy a felhasználó bekapcsolta-e az automatikus hívásátirányító háttérszolgáltatást.
     */
    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    companion object {
        private const val KEY_SOURCE_AA_MAC = "key_source_aa_mac"
        private const val KEY_SOURCE_AA_NAME = "key_source_aa_name"
        private const val KEY_TARGET_SPEAKER_MAC = "key_target_speaker_mac"
        private const val KEY_TARGET_SPEAKER_NAME = "key_target_speaker_name"
        private const val KEY_SERVICE_ENABLED = "key_service_enabled"
    }
}

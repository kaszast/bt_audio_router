package com.antigravity.btaudiorouter

import android.content.Context
import android.content.SharedPreferences

/**
 * [DevicePreferenceManager]
 *
 * Ez az osztály felelős az alkalmazás beállításainak és a kiválasztott Bluetooth
 * eszközök MAC címeinek tartós tárolásáért az Android [SharedPreferences] API-ján keresztül.
 *
 * A Junior fejlesztőknek:
 * - A [SharedPreferences] egy kulcs-érték tároló az Androidban, ami az alkalmazás újraindítása
 *   vagy a telefon újraindítása után is megőrzi az adatokat.
 * - Kotlin `var` getter és setter testreszabásával tisztán, változóként érhetjük el a beállításokat.
 */
class DevicePreferenceManager(context: Context) {

    // Privát SharedPreferences példány létrehozása "bt_audio_router_prefs" néven.
    // MODE_PRIVATE: Csak ez az alkalmazás érheti el a tárolt adatokat.
    private val prefs: SharedPreferences =
        context.getSharedPreferences("bt_audio_router_prefs", Context.MODE_PRIVATE)

    /**
     * A csatlakoztatott Android Auto (forrás) fejegység Bluetooth MAC címe.
     */
    var sourceAaMac: String?
        get() = prefs.getString(KEY_SOURCE_AA_MAC, null)
        set(value) = prefs.edit().putString(KEY_SOURCE_AA_MAC, value).apply()

    /**
     * A hívásokhoz kiválasztott dedikált Bluetooth kihangosító (cél) MAC címe.
     */
    var targetSpeakerMac: String?
        get() = prefs.getString(KEY_TARGET_SPEAKER_MAC, null)
        set(value) = prefs.edit().putString(KEY_TARGET_SPEAKER_MAC, value).apply()

    /**
     * Jelzi, hogy a felhasználó bekapcsolta-e az automatikus hívásátirányító háttérszolgáltatást.
     */
    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    companion object {
        // A SharedPreferences kulcsok konstansai
        private const val KEY_SOURCE_AA_MAC = "key_source_aa_mac"
        private const val KEY_TARGET_SPEAKER_MAC = "key_target_speaker_mac"
        private const val KEY_SERVICE_ENABLED = "key_service_enabled"
    }
}

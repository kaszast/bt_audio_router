package com.antigravity.btaudiorouter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * [BootReceiver]
 *
 * Ez az osztály egy [BroadcastReceiver], amely a rendszer újraindítása után fut le.
 * Amikor a telefon bekapcsolása befejeződik, az Android rendszer egy [Intent.ACTION_BOOT_COMPLETED]
 * üzenetet sugároz.
 *
 * A Junior fejlesztőknek:
 * - Ahhoz, hogy ez működjön, az `AndroidManifest.xml`-ben regisztrálni kell a vevőt a
 *   `RECEIVE_BOOT_COMPLETED` engedéllyel együtt.
 * - Ha a szolgáltatás be volt kapcsolva és van beállítva cél kihangosító, automatikusan elindítja
 *   az [AudioRoutingService]-t háttérben/előtérben.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Ellenőrizzük, hogy a kapott esemény valóban a rendszerindítás befejeződése-e
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = DevicePreferenceManager(context)

            // Csak akkor indítjuk el a szolgáltatást, ha a felhasználó korábban bekapcsolta azt,
            // ÉS van érvényes cél kihangosító MAC cím elmentve.
            if (prefs.isServiceEnabled && !prefs.targetSpeakerMac.isNullOrEmpty()) {
                val serviceIntent = Intent(context, AudioRoutingService::class.java)

                // Android 8.0 (API 26 - Oreo) óta a háttérszolgáltatásokat előtér-szolgáltatásként
                // (Foreground Service) kell elindítani a startForegroundService() hívással.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}

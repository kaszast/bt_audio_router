# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Projekt

Natív Android alkalmazás (Kotlin, `com.antigravity.btaudiorouter`), amely hívás közben átkényszeríti a hang útvonalát egy dedikált Bluetooth kihangosítóra, miközben a telefon Android Auto fejegységhez is csatlakozik. Részletes funkcionális leírás: `README.md`.

## Build

Gradle wrapper (Gradle 9.6.1, AGP 9.3.2, JDK toolchain 21, Java 17 target):

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK (isMinifyEnabled = false)
./gradlew :app:lint               # Android Lint
./gradlew tasks                   # elérhető taskok
```

A build Android SDK-t igényel (`ANDROID_HOME` vagy `local.properties` → `sdk.dir`). A `local.properties` gitignore-olt, a WSL környezetben alapból nincs SDK — ilyenkor a build nem futtatható, ezt mondd ki ahelyett, hogy fordítást állítanál.

Az `app/build.gradle.kts` csak a `com.android.application` plugint alkalmazza; a `kotlin-android` alias definiált a `gradle/libs.versions.toml`-ban, de nincs alkalmazva — a Kotlin fordítás vélhetően az AGP 9 beépített támogatásán keresztül történik (konfidencia: közepes, SDK hiányában nem ellenőrizhető). Egyetlen függőség: `androidx.core:core-ktx`.

## Validáció

Nincs `test/` és `androidTest/` forrásmappa, nincs CI, nincs unit teszt — **kódmódosítás után nem tudsz automatizált teszttel bizonyítani**. A jelenlegi verifikációs útvonal fizikai eszközön manuális:

1. APK telepítése, engedélyek megadása.
2. Forrás (Android Auto) és cél (kihangosító) eszköz kiválasztása a Spinnerekben.
3. „Átirányítás tesztelése” gomb → TTS felolvasás a híváscsatornán (`STREAM_VOICE_CALL`) és médiacsatornán (`STREAM_MUSIC`).
4. A napló (fullscreen + vágólapra másolás) és `adb logcat -s BTAudioRouter` kiolvasása. A `logDetailedDiagnostics()` minden routing-kísérletnél kiírja az Audio Mode-ot, hívásállapotot, a konfigurált cél/forrás eszközt és az összes elérhető kommunikációs + output eszközt ID/típus/MAC szerint — ez az elsődleges hibakeresési forrás.

A `BTAudioRouter-v1.0.apk` szándékosan verziókövetett (`.gitignore`: `!BTAudioRouter-v1.0.apk`); a korábbi release-ek a bináris frissítésével jártak.

## Architektúra

Négy Kotlin fájl, `app/src/main/java/com/antigravity/btaudiorouter/`:

- **`AudioRoutingService.kt`** (~520 sor) — a teljes routing-logika. `connectedDevice` típusú Foreground Service.
- **`MainActivity.kt`** (~670 sor) — UI, eszközlista, napló, közvetlen TTS-teszt.
- **`DevicePreferenceManager.kt`** — `SharedPreferences` wrapper (`bt_audio_router_prefs`): forrás/cél név + MAC, service- és csatorna-kapcsolók.
- **`BootReceiver.kt`** — `BOOT_COMPLETED` esetén indítja a service-t, ha `isServiceEnabled` és van mentett cél MAC.

### Service ↔ Activity kommunikáció

Nincs binding (`onBind` = null). A csatolás **statikus mezőkön** keresztül megy az `AudioRoutingService.companion object`-ben:

- `isRunning: Boolean` — az UI ebből olvassa a kapcsoló állapotát.
- `statusListener: (() -> Unit)?` és `logListener: ((String) -> Unit)?` — a `MainActivity` `onResume`-ban regisztrálja, `onPause`-ban null-ra állítja.

Következmény: **a napló nem pufferelt**. Ha az Activity nincs előtérben, az akkori log-sorok elvesznek az UI számára (a `Log.d("BTAudioRouter", …)` viszont mindig kimegy). Vezérlés az Activity felől kizárólag `startService()` + action konstans (`ACTION_TEST_ROUTE`, `ACTION_RESET_ROUTE`, `ACTION_STOP_SERVICE`, `ACTION_REFRESH_NOTIFICATION`).

### A routing-döntés egyetlen helye

Minden útvonal-kényszerítés az `enforceTargetAudioRoute()`-on megy át. A guard-ok sorrendje adja a tényleges viselkedést:

1. Csak akkor fut, ha `isCallActive || isTestMode` — nyugalmi állapotban szándékosan nem nyúl az audio útvonalhoz.
2. `isCallRoutingEnabled` false → kilép (a teszt is ezen az ágon megy, hogy ugyanazt az útvonalat járja, mint az éles hívás).
3. A `findTargetCommunicationDevice()` az `availableCommunicationDevices` listából **kizárólag `TYPE_BLUETOOTH_SCO`** eszközt fogad el, a forrás AA MAC-jét eleve kiszűrve. Ha van mentett cél MAC, **kizárólag** MAC-egyezés számít; név csak MAC hiányában, pontos egyezéssel.
4. Ha nincs találat: **szigorú megtagadás**, nincs fallback (ez akadályozza meg, hogy a hang az AA fejegységre kerüljön).
5. Ha a jelenlegi kommunikációs eszköz már a cél, a hívás no-op — a watchdog így nem termel naplózajt.
6. Egyébként: `setCommunicationDevice(target)`. **Az audio módhoz éles hívásnál nem nyúlunk** (azt a telefónia stack kezeli, a `MODE_IN_CALL` privilegizált); tesztmódban a `performAudioChannelsTtsTest()` állítja `MODE_IN_COMMUNICATION`-re, és a `didSetAudioMode` flag alapján a `clearAudioRoute()` állítja vissza. Deprecated SCO API-t (`startBluetoothSco`) a routing nem használ.

Két, egymást átfedő „anti-revert” mechanizmus tartja fenn az útvonalat: a `watchdogRunnable` 500 ms-onként (`WATCHDOG_INTERVAL_MS`) újrahívja az `enforceTargetAudioRoute()`-ot a main threaden, és az `OnCommunicationDeviceChangedListener` eltérés esetén azonnal újrakényszerít. Mindkettő csak `isCallActive || isTestMode` alatt aktív.

Életciklus: `TelephonyCallback.CallStateListener` → `RINGING`/`OFFHOOK` → `handleCallStarted()` (enforce + watchdog indítás), `IDLE` → `handleCallEnded()` → `clearAudioRoute()` (`clearCommunicationDevice`, és `MODE_NORMAL` csak akkor, ha az app maga állította a módot).

### Eszközlista az UI-ban

A `loadPairedDevices()` a bonded eszközökből **csak a ténylegesen csatlakozottakat** jeleníti meg, három forrás uniója alapján: `BluetoothProfile.HEADSET` proxy, `BluetoothProfile.A2DP` proxy, és `audioManager.availableCommunicationDevices`. A profil-proxyk aszinkron állnak fel (`profileListener`), ezért a lista a callbackből újratöltődik. Ugyanaz az adapter-példány szolgálja ki mindkét Spinnert; a kiválasztás azonnal a `SharedPreferences`-be íródik és értesítés-frissítést vált ki.

### Lokalizáció

Minden UI-szöveg erőforrás; `values/strings.xml` (angol, alapértelmezett) és `values-hu/strings.xml` (magyar) jelenleg 64–64 azonos kulccsal. **Új string hozzáadásakor mindkét fájlt frissítsd** — a kulcskészletek eltérése a magyar rendszernyelvnél töréshez vezet. A TTS-teszt szövege is erőforrásból jön (`test_call_channel_tts`, `test_media_channel_tts`), és a `Locale.getDefault()` hangját használja.

## Médiacsatorna — architekturális korlát

A rendszer média (A2DP) hangjának átirányítása egy konkrét Bluetooth eszközre **harmadik fél alkalmazásból nem megoldható**. Az aktív A2DP eszköz váltása a `BluetoothA2dp.setActiveDevice()` @SystemApi-n keresztül történik, ami `BLUETOOTH_PRIVILEGED` engedélyt igényel; a rendszer kimenetválasztója (Output Switcher) a `MediaRouter2Manager` @SystemApi-t használja `MEDIA_CONTENT_CONTROL`-lal. Publikus API csak a saját app hangját tudja irányítani (`AudioTrack.setPreferredDevice`), illetve a kommunikációs (SCO) útvonalat (`setCommunicationDevice`).

Ezért az `isMediaRoutingEnabled` kapcsoló hatóköre szándékosan a beépített TTS-teszt média-részére korlátozott; ezt a UI-felirat, a bekapcsoláskor kiírt naplóüzenet (`log_media_routing_limited`) és a README is kimondja. **Ne implementálj ide „megoldást” rejtett API-k reflexiós hívásával vagy audio-capture bridge-dzsel a felhasználó kifejezett jóváhagyása nélkül** — mindkettőt elvetette (reflection: normál telepítésnél blokkolt; capture bridge: a navigációs hangot és a DRM-védett appokat nem fogja el, SCO-minőségben).

## Korábbi hibák és javításuk

A `tasks/todo.md` tartalmazza a 2026-09-09-i hibajavítási kört (A1–A7): a `MODE_IN_CALL` beállításának megszüntetése, a deprecated SCO API-k eltávolítása, a téves eszközillesztést okozó névtisztító regex törlése, MAC-alapú forráskizárás, a `MODE_NORMAL` vak visszaállításának megszüntetése, a watchdog naplózajának megszüntetése és a teszt/éles guard-ok összehangolása. Ezek fizikai eszközön még nem validáltak.

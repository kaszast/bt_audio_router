# BT Audio Router

**BT Audio Router** egy natív Android alkalmazás (Kotlin), amely megoldást nyújt arra a problémára, amikor a telefon egyszerre van csatlakoztatva **Android Auto** (vagy autós fejegység, pl. Yuehoo) rendszerhez és egy **különálló Bluetooth hívás-kihangosítóhoz**.

---

## 📌 A Probléma
Amikor a telefon Android Auto-hoz van csatlakoztatva, az Android rendszer a bejövő és kimenő hívások hangját automatikusan az Android Auto fejegységre irányítja. Ha a felhasználó egy dedikált, különálló Bluetooth kihangosítót szeretne használni a hívásokhoz, a fejegység vagy az Android Auto csatorna gyakran visszaveszi a hívás hangját (auto-revert).

## 💡 A Megoldás
A **BT Audio Router** egy háttérben futó előtér-szolgáltatást (**Foreground Service**) használ, amely:
1. Folyamatosan figyel a telefon hívási állapotára (`RINGING`, `OFFHOOK`, `IDLE`).
2. Amint hívás indul, megkeresi a csatlakoztatott eszközök közül a cél Bluetooth SCO kihangosítót.
3. Az Android 12+ hívási audio API-jával (`AudioManager.setCommunicationDevice()`) átkényszeríti a hívás hangját a kihangosítóra.
4. **Anti-Revert Watchdog**: Egy 500 ms-os cikluson futó ellenőrzéssel garantálja, hogy ha az Android Auto vagy a fejegység megpróbálná visszavenni a hívást, az alkalmazás azonnal visszatérítse a hangot a kihangosítóra.
5. **Részletes Állandó Értesítés**: Az értesítési sávban élőben kijelzi a szolgáltatás állapotát és a két kiválasztott eszközt (névvel és MAC címmel).
6. A hívás végén alaphelyzetbe állítja az audio-útvonalat (`clearCommunicationDevice()`), így a média- és navigációs hangok továbbra is akadálytalanul szólnak a fejegységen.

---

## ✨ Főbb Funkciók

- **Automatikus Hívás-Átirányítás**: Bejövő és kimenő hívások automatikus kezelése.
- **Eszközválasztás**: A telefonhoz párosított Bluetooth eszközök közül külön kiválasztható az Android Auto forrás és a cél kihangosító.
- **Anti-Revert Watchdog**: Felülbírálja az Android Auto automatikus audio-visszaállítási kísérleteit.
- **Állandó Állapotértesítés**: Kijelzi a szervíz állapotát, valamint a kiválasztott forrás (Android Auto) és cél (Kihangosító) eszközöket.
- **Android 14+ Kompatibilitás**: A `connectedDevice` előtér-szolgáltatási típussal biztonságosan és hiba nélkül fut Android 14, 15 és 16 rendszereken.
- **Cyberpunk OLED Ikon**: Modern, lekerekített vektorgrafikus alkalmazásikon (Adaptive Icon).
- **Automatikus Indítás**: A rendszer újraindítása után (`BOOT_COMPLETED`) automatikusan elindul, ha a szolgáltatás be volt kapcsolva.
- **Diagnosztika és Élő Napló**: A főképernyőn élőben követhető az aktív kommunikációs eszköz, a hívás állapota és a valós idejű eseménynapló.
- **Manuális Teszt és Visszaállítás**: Teszt gomb az audio-útvonal azonnali kipróbálásához hívás nélkül is.

---

## 🏗️ Architektúra és Állományok

| Állomány | Szerep / Feladat |
| :--- | :--- |
| **`AudioRoutingService.kt`** | A fő előtér-szolgáltatás (`connectedDevice` FGS típus). Kezeli a hívásállapotokat (`TelephonyCallback`), az audio útvonalat (`AudioManager`), az állandó értesítést, és futtatja az Anti-Revert Watchdog időzítőt. |
| **`MainActivity.kt`** | A felhasználói felület (UI). Kezeli az engedélykéréseket, a Bluetooth eszközök kiválasztását, a szolgáltatás ki/bekapcsolását és a napló megjelenítését. |
| **`BootReceiver.kt`** | `BroadcastReceiver`, amely a telefon bekapcsolása után automatikusan elindítja a szolgáltatást. |
| **`DevicePreferenceManager.kt`** | `SharedPreferences` wrapper a kiválasztott nevek, MAC címek és állapotok tartós tárolásához. |

---

## ⚙️ Rendszerkövetelmények

- **Minimum SDK**: Android 12 (API level 31)
- **Target SDK**: Android 16 (API level 36)
- **Fordító**: Java 17 / Kotlin 1.9+
- **Szükséges engedélyek**:
  - `BLUETOOTH_CONNECT` és `BLUETOOTH_SCAN` (Eszközök beolvasásához)
  - `READ_PHONE_STATE` (Hívások észleléséhez)
  - `POST_NOTIFICATIONS` (Előtér-szolgáltatás értesítéséhez)
  - `MODIFY_AUDIO_SETTINGS` (Audio útvonal módosításához)
  - `RECEIVE_BOOT_COMPLETED` (Automatikus indításhoz)
  - `FOREGROUND_SERVICE` és `FOREGROUND_SERVICE_CONNECTED_DEVICE`

---

## 🎓 Útmutató Junior Fejlesztőknek

Ha most ismerkedsz az Android audio-kezeléssel és a háttérszolgáltatásokkal, az alábbi fogalmak megértése kulcsfontosságú a kód tanulmányozásakor:

### 1. Audio Kommunikációs Eszközök (`setCommunicationDevice`)
Android 12 (API 31) óta a hivatalos és ajánlott API az `AudioManager.setCommunicationDevice(AudioDeviceInfo)`.
- Az `audioManager.availableCommunicationDevices` visszaadja az összes csatlakoztatott hívási eszközt.
- Az `AudioDeviceInfo.TYPE_BLUETOOTH_SCO` azonosítja a Bluetooth headseteket/kihangosítókat.

### 2. Előtér-szolgáltatás (Foreground Service) Android 14+ alatt
Android 14 (API 34) óta kötelező specifikus FGS típust megadni. Mivel a külső Bluetooth kiegészítők vezérlése nem igényli a rendszertárcsázó (`DIALER`) szerepkört, a szolgáltatás a `connectedDevice` típust használja a `FOREGROUND_SERVICE_CONNECTED_DEVICE` engedéllyel.

### 3. TelephonyCallback
Android 12 felett a `TelephonyCallback` és annak `CallStateListener` interfésze használandó a hívásállapotok (`RINGING`, `OFFHOOK`, `IDLE`) figyelésére.

---

## 🚀 Használat és Beállítás

1. Töltsd le vagy fordítsd le a **`BTAudioRouter-v1.0.apk`** telepítőt.
2. Nyisd meg a **BT Audio Router** alkalmazást a telefonodon.
3. Kattints az **"Engedélyek megadása"** gombra és hagyd jóvá a kért engedélyeket.
4. Az **1. Eszközök beállítása** résznél válaszd ki:
   - **Forrás (Android Auto)**: a fejegységedet.
   - **Cél (Bluetooth kihangosító)**: a hívásokhoz használni kívánt kihangosítót.
5. Kapcsold be a **Hívás-átirányító háttérszolgáltatás** kapcsolót.
6. A teszteléshez nyomd meg az **"Átirányítás tesztelése"** gombot.

---
---

# English Documentation

**BT Audio Router** is a native Android application written in Kotlin designed to solve audio routing conflicts when a smartphone is simultaneously connected to **Android Auto** (or a car head unit) and a **dedicated Bluetooth hands-free speaker/headset**.

---

## 📌 The Problem
When connected to Android Auto, the Android system automatically routes call audio to the car head unit. If a driver wants to handle phone calls through a separate dedicated Bluetooth hands-free speaker, Android Auto or the car system frequently forces the audio back (auto-revert).

## 💡 The Solution
**BT Audio Router** runs a persistent **Foreground Service** that:
1. Monitors phone call states (`RINGING`, `OFFHOOK`, `IDLE`) via `TelephonyCallback`.
2. Locates the configured target Bluetooth SCO hands-free speaker upon call initiation.
3. Enforces call audio routing to the target speaker using `AudioManager.setCommunicationDevice()`.
4. **Anti-Revert Watchdog**: Executes an active 500ms loop during calls to override any attempts by Android Auto to revert the audio path.
5. **Persistent Notification**: Displays real-time status and configured device details (Name & MAC) in the status bar.
6. Resets the audio route (`clearCommunicationDevice()`) upon call completion so media and navigation audio continue smoothly through the head unit.

---

## ✨ Features

- **Automatic Call Routing**: Hands-free routing for incoming and outgoing calls.
- **Device Selector**: Independent selection of Android Auto source and target Bluetooth speaker from paired devices.
- **Anti-Revert Watchdog**: Overrides Android Auto audio hijacking attempts.
- **Live Status Notification**: Persistent notification displaying service state and both device names.
- **Android 14+ Ready**: Uses `connectedDevice` Foreground Service type to prevent `SecurityException` on Android 14, 15, and 16.
- **Cyberpunk OLED Launcher Icon**: Modern adaptive vector icon.
- **Boot Autostart**: Launches automatically after phone reboot (`BOOT_COMPLETED`) if enabled.
- **Diagnostics & Live Log**: Real-time log viewer and active audio device tracker.

---

## ⚙️ System Requirements

- **Minimum SDK**: Android 12 (API level 31)
- **Target SDK**: Android 16 (API level 36)
- **Compiler**: Java 17 / Kotlin 1.9+
- **Permissions**: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`, `MODIFY_AUDIO_SETTINGS`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`.

---

## 📄 License
This project is open-source under the MIT / Apache 2.0 license. Free to use and modify.

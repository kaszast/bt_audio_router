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
5. A hívás végén alaphelyzetbe állítja az audio-útvonalat (`clearCommunicationDevice()`), így a média- és navigációs hangok továbbra is akadálytalanul szólnak a fejegységen.

---

## ✨ Főbb Funkciók

- **Automatikus Hívás-Átirányítás**: Bejövő és kimenő hívások automatikus kezelése.
- **Eszközválasztás**: A telefonhoz párosított Bluetooth eszközök közül külön kiválasztható az Android Auto forrás és a cél kihangosító.
- **Anti-Revert Watchdog**: Felülbírálja az Android Auto automatikus audio-visszaállítási kísérleteit.
- **Automatikus Indítás**: A rendszer újraindítása után (`BOOT_COMPLETED`) automatikusan elindul, ha a szolgáltatás be volt kapcsolva.
- **Diagnosztika és Élő Napló**: A főképernyőn élőben követhető az aktív kommunikációs eszköz, a hívás állapota és a valós idejű eseménynapló.
- **Manuális Teszt és Visszaállítás**: Teszt gomb az audio-útvonal azonnali kipróbálásához hívás nélkül is.

---

## 🏗️ Architektúra és Állományok

| Állomány | Szerep / Feladat |
| :--- | :--- |
| **`AudioRoutingService.kt`** | A fő előtér-szolgáltatás (Foreground Service). Kezeli a hívásállapotokat (`TelephonyCallback`), az audio útvonalat (`AudioManager`), és futtatja az Anti-Revert Watchdog időzítőt. |
| **`MainActivity.kt`** | A felhasználói felület (UI). Kezeli az engedélykéréseket, a Bluetooth eszközök kiválasztását, a szolgáltatás ki/bekapcsolását és a napló megjelenítését. |
| **`BootReceiver.kt`** | `BroadcastReceiver`, amely a telefon bekapcsolása után automatikusan elindítja a szolgáltatást. |
| **`DevicePreferenceManager.kt`** | `SharedPreferences` wrapper a kiválasztott MAC címek és állapotok tartós tárolásához. |

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
  - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_PHONE_CALL`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`

---

## 🎓 Útmutató Junior Fejlesztőknek

Ha most ismerkedsz az Android audio-kezeléssel és a háttérszolgáltatásokkal, az alábbi fogalmak megértése kulcsfontosságú a kód tanulmányozásakor:

### 1. Audio Kommunikációs Eszközök (`setCommunicationDevice`)
Android 12 (API 31) előtt a hívási hangirányítást a `startBluetoothSco()` és `setSpeakerphoneOn()` függvényekkel végezték. Android 12-től a hivatalos és ajánlott API az `AudioManager.setCommunicationDevice(AudioDeviceInfo)`.
- Az `audioManager.availableCommunicationDevices` visszaadja az összes csatlakoztatott hívási eszközt.
- Az `AudioDeviceInfo.TYPE_BLUETOOTH_SCO` azonosítja a Bluetooth headseteket/kihangosítókat.

### 2. Előtér-szolgáltatás (Foreground Service)
Az Android szigorúan korlátozza a háttérben futó folyamatokat a telepes üzemidő védelme érdekében. Mivel az alkalmazásunknak akkor is figyelnie kell a hívásokat, ha nincs megnyitva a képernyőn:
- A szolgáltatást `startForegroundService()` hívással indítjuk.
- Kötelező egy értesítést (`Notification`) megjeleníteni a `startForeground()` hívással.

### 3. TelephonyCallback vs. régi PhoneStateListener
Android 12 felett a `PhoneStateListener` deprecated. Helyette a `TelephonyCallback` és annak `CallStateListener` interfésze használandó a hívásállapotok (`RINGING`, `OFFHOOK`, `IDLE`) figyelésére.

### 4. Handler / Runnable (Watchdog)
A `Handler(Looper.getMainLooper())` segítségével időzített vagy ismétlődő feladatokat futtathatunk a főszálon. A `postDelayed()` függvénnyel az Anti-Revert Watchdog 500 ms-onként újra lefut.

---

## 🚀 Használat és Beállítás

1. Telepítsd az APK-t a telefonodra.
2. Nyisd meg a **BT Audio Router** alkalmazást.
3. Kattints az **"Engedélyek megadása"** gombra és hagyd jóvá a kért engedélyeket.
4. Az **1. Eszközök beállítása** résznél válaszd ki:
   - **Forrás (Android Auto)**: a fejegységedet.
   - **Cél (Bluetooth kihangosító)**: a hívásokhoz használni kívánt kihangosítót.
5. Kapcsold be a **Hívás-átirányító háttérszolgáltatás** kapcsolót.
6. A teszteléshez nyomd meg az **"Átirányítás tesztelése"** gombot.

---

## 📄 Licenc
Ez a projekt nyílt forráskódú. Szabadon módosítható és felhasználható.

# BT Audio Router — hibajavítási terv

Kör: 2026-09-09. Állapot: kód kész, **fordítás és eszközön való validáció még nem történt meg** (a WSL környezetben nincs Android SDK).

## A. Hívási (SCO) útvonal javításai

- [x] A1. `MODE_IN_CALL` beállításának eltávolítása éles hívásnál.
      A `MODE_IN_CALL` rendszer-telefónia mód (MODIFY_PHONE_STATE privilégium), harmadik fél appból nem érvényes; hívás alatt a telefónia stack állítja be.
      Tesztmódban `MODE_IN_COMMUNICATION`-t állítunk, és a `didSetAudioMode` flag alapján állítjuk vissza.

- [x] A2. Deprecated SCO API-k (`startBluetoothSco`, `stopBluetoothSco`, `isBluetoothScoOn` írása) eltávolítása a routing-útvonalból.
      A `logDetailedDiagnostics()`-ban szándékosan megmaradt az `isBluetoothScoOn` **olvasása** diagnosztikai célból.

- [x] A3. A `.*?[–-]\s*` névtisztító regex törlése, illesztés átírása.
      Új: `findTargetCommunicationDevice()` — ha van mentett cél MAC, kizárólag MAC-egyezés; név csak MAC hiányában, `equals` alapján (nem `contains`).

- [x] A4. Forrás (AA) kizárása MAC alapján, már a jelöltek szűrésénél.

- [x] A5. `clearAudioRoute()` nem állít vakon `MODE_NORMAL`-t; csak azt a módot állítja vissza, amit az app maga állított.

- [x] A6. A `logDetailedDiagnostics()` nem fut 500 ms-onként.
      Ha a kommunikációs eszköz már a cél, az `enforceTargetAudioRoute()` no-op; a "cél nem található" üzenet a `hasLoggedTargetMissing` flaggel egyszer íródik ki.

- [x] A7. Tesztmód és éles hívás guard-jainak összehangolása: a `isCallRoutingEnabled` a tesztre is érvényes, így a teszt ugyanazt az útvonalat járja, mint az éles hívás.

## B. Médiacsatorna

- [x] B1. Döntés: a rendszer média (A2DP) átirányítása nem implementálható harmadik fél appból.
      A felhasználó mindhárom felkínált utat (reflection a `BluetoothA2dp.setActiveDevice`-ra, AudioPlaybackCapture bridge, kombinált fallback) elvetette.
      Megvalósítva: a kapcsoló hatóköre a UI-feliratban, a bekapcsoláskor kiírt naplóüzenetben (`log_media_routing_limited`) és a README-ben őszintén szerepel. A kapcsoló és a prefs-kulcs változatlan.

## Hátralévő validáció (a javítások NEM igazoltak)

1. `./gradlew :app:assembleDebug` — Android SDK szükséges.
2. Eszközön: cél kihangosító + AA fejegység csatlakoztatva, hívás indítása, a napló `ROUTING -> <cél MAC>` sorának ellenőrzése, és hogy a hang ténylegesen a célon szól.
3. Hívás bontása után: a média (zene, navigáció) visszatér a fejegységre, nincs beragadt néma állapot (ez az A1/A5 javítás célja).
4. Kikapcsolt Hívás-kapcsolóval: a hívás a rendszer alapértelmezett útvonalán (AA) szól, nem néma.

## Review

A bejelentett tünet ("bekapcsolva mindig az utoljára megérintett eszközön szól, kikapcsolva sehol") legvalószínűbb gyökéroka két, egymást erősítő hiba volt:

- A `MODE_IN_CALL` beállítása harmadik fél appból: a rendszer telefonhívásnak látja az állapotot, ami elnémíthatja a médiát; a `clearAudioRoute()` pedig csak hívás után állította vissza `MODE_NORMAL`-ra, így az állapot beragadhatott ("sehol nem szól").
- A névtisztító regex az első kötőjelig levágta a cél nevét, a `contains()` alapú kétirányú illesztés pedig téves eszközre találhatott, vagy semmire — ilyenkor a routing megtagadódott, és a hang a rendszer által választott (utoljára érintett) eszközön maradt.

A média-ág nem oldható meg publikus API-val; ezt a kód, a README és a `CLAUDE.md` is rögzíti, hogy a következő kör ne kezdje elölről a keresést.

# SafetyPlease

Aplicație Android de raportare de incidente și chat care merge fără date mobile, fără Wi-Fi și fără server.
Telefoanele formează o rețea Bluetooth LE, iar mesajele sar din telefon în telefon până la destinatar.

## Stare: ce e verificat și ce nu

**Nimic nu a fost încă testat pe telefoane reale.** Până acum nu a fost conectat niciun telefon.

| Unde | Ce înseamnă | Ce a trecut |
|---|---|---|
| Teste unitare (125) | Logica fără radio, pe un radio simulat în memorie | Tot: protocol, criptografie, relay, store-and-forward, chat, grupuri, incidente, clustering, hartă |
| 4 emulatoare Android 16 | Stiva Bluetooth Android reală, dar radio virtual (fără pierderi, semnal constant) | Lanțul A → B → C → D cu 3 hop-uri; raport urgent → staff → „preluat de”; chat cu zonă și pin pe hartă; staff offline 2 minute, apoi raportul ajunge singur; onboarding cu dialogurile reale de permisiuni |
| Telefoane reale | Singurul test care contează pentru rază, baterie, ecran stins, Samsung/Xiaomi | **Nimic** |

Ce **nu** e verificat nicăieri:
- scanarea QR cu camera (decodarea e testată pe imagini generate; camera reală, nu);
- fragmentarea pe BLE real (pe emulatoare MTU-ul e mereu 517, deci nu se fragmentează nimic);
- grupurile, rotația legăturilor și modul ancoră peste Bluetooth (au doar teste unitare);
- ecran stins, Doze, optimizările de baterie;
- Android 12–13 și Android 11 sau mai vechi (permisiunile diferă).

O problemă deschisă de transport, găsită pe emulatoare: când mai multe telefoane se conectează **în aceeași
secundă** la același telefon, o parte din conectări se blochează 5 secunde și se reiau. Rețeaua s-a refăcut de
fiecare dată în cel mult 45 de secunde, dar cauza nu e stabilită. Detalii la [Limite cunoscute](#limite-cunoscute).

## Cum funcționează

### 1. Identitate
La prima pornire telefonul își generează două perechi de chei: X25519 (criptare) și Ed25519 (semnare). Sunt
păstrate într-un fișier criptat cu o cheie din Android Keystore. `nodeId` = primii 8 octeți din
SHA-256(cheia publică Ed25519 ‖ cheia publică X25519). Nickname-ul e doar un nume afișat.

### 2. Descoperire
Fiecare telefon face simultan advertising și scanare. În advertising pune UUID-ul serviciului, iar în scan
response 6 octeți: `versiune, flags, primii 4 octeți din nodeId`. Flags: staff, ancoră, „am un incident
netrimis”, „mai accept conexiuni”. Scanarea filtrează mereu pe UUID (obligatoriu cu ecranul stins).

### 3. Conexiuni
- Fiecare telefon e și server GATT, și client. O singură caracteristică: clientul scrie fără răspuns, serverul
  răspunde prin notificări.
- **Regula inițiatorului:** dintre doi vecini se conectează cel cu `nodeId` mai mic. Dacă acela nu o face în
  20 de secunde, se conectează celălalt. Legăturile duble se rezolvă la `HELLO`.
- Limite: 4 conexiuni inițiate și 3 primite (ancora: 3 și 4). La conectare au prioritate staff-ul și ancorele,
  apoi vecinii cu incident netrimis, apoi semnalul mai bun.
- La fiecare 60 de secunde, dacă sloturile sunt pline și există vecini nelegați, se închide cea mai veche
  legătură inactivă.
- La conectare: `HELLO` (cine sunt), apoi `SUMMARY` (ce incidente am în cache).

### 4. Pachetul
Binar, big-endian. Antet de 26 de octeți: `versiune, tip, ttl, flags, id (8), expeditor (8), timestamp (4),
lungime (2)`, apoi opțional destinatar (8) și fragment (4), apoi cel mult 480 de octeți de payload.
26 + 8 + 480 = 514, exact cât încape într-o scriere la MTU 517.

Tipuri: `HELLO`, `SUMMARY`, `REQUEST` (rămân pe legătură), `INCIDENT_REPORT`, `INCIDENT_ACK`, `PRIVATE`, `TEST`
(doar în build-ul debug).

### 5. Relay
- Originea trimite cu `ttl = 7`. Un pachet nevăzut, cu `ttl > 1`, se retrimite cu `ttl − 1` pe toate legăturile
  în afară de cea de pe care a venit, după 50–250 ms aleatorii.
- Hop-uri afișate = `8 − ttl` la primire, adică numărul de legături traversate.
- Un mesaj cu destinatar circulă la fel; destinatarul îl consumă, ceilalți îl dau mai departe.
- Dedup: ultimele 4.096 de pachete din ultimele 15 minute.
- Coadă cu priorități per legătură: incident > ACK > chat > restul.
- Anti-abuz: cel mult 30 de pachete la 10 secunde primite de la un vecin; un vecin care trimite pachete
  malformate e ignorat 10 minute.

### 6. Store-and-forward
Fiecare telefon păstrează 30 de minute (ancorele 60) până la 50 de rapoarte și ACK-urile lor. La fiecare
legătură nouă, și apoi la fiecare minut, cei doi își spun ce au (`SUMMARY`) și cer ce le lipsește (`REQUEST`).
Așa un raport ajunge și la un telefon care apare mai târziu. Cache-ul e doar în memorie: se pierde dacă
aplicația e oprită.

### 7. Incidente
- **Participant:** alege categoria și apasă „Trimite raportul” (2 tap-uri). Gravitatea, zona, descrierea și
  anonimatul sunt opționale. Cel mult 3 rapoarte în 10 minute.
- Raportul e criptat (sealed box) către cheia de staff; în clar rămâne doar `incidentId`. Telefoanele prin care
  trece nu îl pot citi și nu știu cine l-a trimis.
- **Staff:** telefonul deschide raportul, confirmă automat primirea, afișează alerta. „Preiau” și „Rezolvat”
  trimit ACK-uri semnate; un ACK cu semnătură greșită e aruncat de primul telefon care îl vede.
- Raportorul vede: se transmite → ajuns la staff → preluat de {echipă} → rezolvat.

### 8. Prieteni și chat
- Prietenii se adaugă prin cod QR (sau lipind codul ca text). **Amândoi trebuie să se scaneze reciproc.**
- Mesajele sunt criptate cap-coadă (`crypto_box`). Fiecare are stare: în așteptare, trimis, livrat.
- Mesajele nelivrate se retrimit singure (după 15 s, 30 s, 1, 2, apoi la 5 minute) până la 24 de ore.
- Răspunsuri rapide și „Trimite zona mea”; un tap pe mesajul cu zonă deschide harta cu pin.
- Grupuri de cel mult 8: fiecare mesaj pleacă separat către fiecare membru.

### 9. Hartă și zone
Layout-ul evenimentului e [app/src/main/assets/venue.json](app/src/main/assets/venue.json): limite, zone
(poligoane) și punct de întâlnire. Harta e desenată local, fără internet. Zona vine din GPS; fără GPS sau în
afara zonelor, o alegi de mână.

### 10. Serviciu
Totul rulează într-un serviciu în prim-plan (`connectedDevice`), cu notificare permanentă, ca rețeaua să
rămână pornită cu ecranul stins. Sub 20% baterie scanarea și advertising-ul trec pe consum redus, mai puțin
în cele 15 secunde de după un incident trimis.

## Structura codului

Totul e în `app/src/main/java/ro/safetyplease/app/`:

| Pachet | Ce conține |
|---|---|
| `protocol` | Formatul pachetelor, fragmentare, payload-uri |
| `mesh` | Motorul de mesh, independent de radio: legături, relay, dedup, cozi, cache, politica de conectare |
| `ble` | Radioul Bluetooth LE real (scanare, advertising, GATT) |
| `crypto` | libsodium (prin lazysodium), identitate, chei de staff, coduri QR |
| `chat`, `incidents` | Logica de chat și de incidente |
| `venue`, `location` | Zone, point-in-polygon, GPS |
| `service` | Serviciul în prim-plan și notificările |
| `ui` | Ecranele (Jetpack Compose) |
| `demo` (doar în `src/debug`) | Modul demo și comenzile de test prin `adb` |

Numele aplicației stă într-un singur loc: `appName` din [gradle.properties](gradle.properties).

## Build și instalare

Din PowerShell, în folderul proiectului:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

```powershell
.\gradlew.bat assembleDebug
```

```powershell
.\gradlew.bat testDebugUnitTest
```

Calea proiectului conține `,` și `!`. Din PowerShell sau `cmd` merge; din Git Bash `gradlew.bat` nu pornește.

Instalare pe un telefon conectat prin USB, cu depanarea USB activată:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

Sau deschide folderul în Android Studio și apasă Run.

## Testul pe telefoane

Ai nevoie de 4 telefoane cu build-ul debug, în modul avion, cu Bluetooth pornit: A participant, B și C relay,
D staff. Modul demo e în Setări › Mod demo.

1. Pe fiecare telefon treci prin onboarding și aștepți să apară legăturile (sus: „N legături”).
2. Pe D: Mod demo › **Staff**.
3. Pe A: Mod demo › la peer-i, pornește **Ignoră** pentru C și D. Pe B: **Ignoră** pentru D.
   Rămâne lanțul A → B → C → D.
4. Pe A: **Trimite TEST**. Pe D trebuie să apară „TEST … hop-uri 3” în mai puțin de 15 secunde.
5. Pe A: Raportează › Medical › Trimite raportul. D primește alerta; A vede „Ajuns la staff”.
6. Pe D: deschide incidentul › **Preiau**. A vede „Preluat de {echipă}”.
7. A și C își scanează reciproc codurile (Prieteni › Codul meu / Scanează). A trimite „Unde ești?”, C răspunde
   cu „Trimite zona mea”, A apasă pe mesaj și vede pinul.
8. Pe D oprește Bluetooth 2 minute. A raportează. Repornește Bluetooth pe D: raportul trebuie să ajungă singur.

Dacă ceva nu merge, logul de pe telefon:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -s Mesh:V MeshDebug:V
```

Măsurătorile (latență per hop, rată de livrare, rază, baterie, ecran stins) nu au fost făcute.

## Testul pe emulatoare

Emulatorul are Bluetooth virtual între instanțe (verificat cu emulatorul 36.6 și imaginea API 36), deci
protocolul se poate testa fără telefoane. Există 4 AVD-uri create pentru asta: `sp_a`, `sp_b`, `sp_c`, `sp_d`.

Build-ul debug primește comenzi prin `adb` (răspunsul apare în logcat, tag `MeshDebug`):

```bash
adb -s emulator-5554 shell am broadcast -n ro.safetyplease.app/.demo.DebugCommandReceiver --es cmd status
```

Comenzi: `onboard --es nick X`, `status`, `myqr`, `friend --es qr COD`, `ignore --es prefix a1b2c3d4,…`,
`unignore`, `test`, `incident --es zone ID`, `staff`, `anchor --es zone ID`, `participant`, `take`, `resolve`,
`text --es to NODEID --es msg TEXT`, `where --es to NODEID`, `zone --es to NODEID --es zone ID`,
`simulate --es zone ID`.

## Chei de staff

```bash
python tools/gen_staff_keys.py --teams "Medical 1" "Medical 2" --anchors main-stage bar
```

Scrie cheile publice în `app/src/main/assets/staff_public.json` și codurile QR în `tools/out/` (ignorat de git).
Are nevoie de `pip install pynacl "qrcode[pil]"`.

**Cheile din repo sunt chei de demo:** semințele lor secrete sunt incluse în build-ul debug, ca modul demo să
poată activa staff fără QR. Pentru un eveniment real rulează cu `--new` și fără `--demo`, apoi reconstruiește.

## Limite cunoscute

- **Conectări simultane la același telefon.** Pe emulatoare, la pornirea simultană a 4 aplicații, în 13 din 24
  de reporniri unele conectări au rămas fără răspuns la negocierea MTU și au fost reluate după câteva secunde.
  Din 102 încercări, cele 40 care nu s-au suprapus cu alta au reușit toate; toate cele 37 de eșecuri au fost
  încercări suprapuse. Rețeaua a fost completă în 45 de secunde de fiecare dată. Nu știu dacă problema e a
  emulatorului, a stivei Android sau apare și pe telefoane. De verificat cu `logcat -s Mesh:V`, căutând
  `MTU nenegociat`.
- Cache-ul de store-and-forward e doar în memorie.
- Un mesaj cu destinatar ajunge tot prin toată rețeaua (flood); la fel fiecare retransmisie din outbox.
- Un nod rău-voitor poate umple cache-ul de rapoarte cu rapoarte false; doar limita de rată îl încetinește.
- Primul vecin vede `ttl = 7` și știe că expeditorul e originea raportului.
- Chatul nu are forward secrecy.
- `targetSdk` e 36; comportamentul pentru 37 nu a fost verificat.
- Decizia de a păstra apelul `gattServer.connect()` și întârzierea aleatoare la reîncercare nu sunt validate
  prin măsurători; sunt notate în jurnal.

Jurnalul complet, cu deciziile luate și cu tot ce a fost măsurat, e în `docs/LOGS.md`
(local, în afara git-ului).

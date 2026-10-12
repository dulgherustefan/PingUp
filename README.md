# PingUp
O aplicație Android pentru raportarea incidentelor și mesajelor, care merge fără internet, fără date mobile, fără Wi-Fi, fără server.
Ideea de la care am pornit:  In timpul unui festival sau a unui eveniment aglomerat, trimiterea mesajelor este aproape imposibila din cauza lipsei de semnal.

## Cum funcționează, pe scurt
Telefoanele se conectează între ele prin Bluetooth Low Energy și formează o rețea (mesh). Un mesaj sare din telefon în telefon până ajunge la destinatar. Dacă A vrea să trimită ceva către D, dar nu e în raza lui, mesajul trece prin B și C.  

A  →  B  →  C  →  D

## Ce poate face
- Raportare de incidente: în doar 2 tap-uri (alegi categoria și trimiți). Poți adăuga și gravitatea, zona și o descriere, dar sunt opționale.
- Staff-ul primește alerta, apasă „Preiau" sau „Rezolvat", iar cel care a raportat vede în ce stadiu e: se transmite → ajuns la staff → preluat → rezolvat.
- Chat criptat cap-coadă cu prietenii, adăugați prin cod QR (se scanează amândoi). Merg și grupuri de maxim 8 persoane.
- „Trimite zona mea" apeși pe mesaj și se deschide harta cu un pin.
- Hartă offline, desenată local, pe baza unui fișier cu zonele evenimentului (`venue.json`).
- Store-and-forward: dacă un telefon e offline un timp, primește raportul când revine în rețea.

## Tehnologii
Kotlin, Jetpack Compose, Bluetooth LE (GATT), libsodium (prin lazysodium), Gradle, Python (script pentru chei de staff).

## Structura
Două module: `core/` are toată logica (protocolul, rețeaua mesh, criptarea, chatul, incidentele) în Kotlin pur, fără Android; `app/` are Bluetooth-ul, notificările și ecranele. Detalii și reguli în [ARCHITECTURE.md](ARCHITECTURE.md).

## Cum rulezi proiectul
Cel mai simplu: deschizi folderul în Android Studio și dai Run.

Din terminal, cu JDK-ul din Android Studio:

macOS / Linux
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug test
```

Windows (PowerShell)
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug test
```

`test` rulează testele din ambele module. Apoi instalezi pe telefon (cu USB debugging activat):
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Instalare

Se poate instala si de pe [ping-up.org](https://ping-up.org)

## Release

Un tag `v1.2.3` pornește `.github/workflows/release.yml`: teste, APK de release semnat, release pe GitHub cu
`ping-up.apk`, SHA-256-ul și amprenta certificatului. Site-ul îl preia singur în câteva minute.

```
git tag v0.2.0
git push origin v0.2.0
```

O singură dată, cheia de semnare (păstreaz-o: fără ea nu mai poți publica actualizări peste instalările existente):

```
keytool -genkeypair -keystore release.jks -alias pingup -keyalg RSA -keysize 4096 -validity 10000
base64 -i release.jks
```

În Settings → Secrets and variables → Actions: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Cheile de staff ale unui eveniment: `python tools/gen_staff_keys.py`. Semințele rămân în `tools/out/`, nu în git.
Build-ul debug are o pereche separată, publică (`--demo`), pe care release-ul nu o acceptă.

## Demo

Pentru prezentări (Beach, Please!, CEM 2026) și repetiții, numai cu build-ul debug. Modul demo se deschide din Eu: 7 atingeri pe Versiune, apoi „Mod demo”. Cheile de demo sunt publice și marcate ca demo (`app/src/debug/assets/demo_chain.json`): un root, un organizator și un eveniment de test, pe care doar build-ul debug le acceptă. Release-ul nu are nici cheile, nici rădăcina lor și refuză tot ce semnează ele.

Comenzile merg din butoanele modului demo sau prin adb (răspunsul apare în logcat, eticheta `MeshDebug`):
```
adb -s SERIAL shell am broadcast -n org.pingup.app/ro.safetyplease.app.demo.DebugCommandReceiver --es cmd COMANDA
adb -s SERIAL logcat -s MeshDebug:V
```

| Comandă | Ce face |
|---|---|
| `pack` | Încarcă pachetul de eveniment demo (harta cu reperele numerotate, șabloanele de anunțuri RO/EN, paginile de informații, fișele de prim ajutor, cheile de raport pentru azi și următoarele 3 zile) și repornește aplicația |
| `control` | Telefonul devine consola, cu o tură semnată de lanțul demo |
| `staff --es team "Medical 1" --es zone main-stage --ez lead true` | Echipă în teren; `lead` o face șef de rol (sună ca la consolă pentru urgențele rolului) |
| `anchor --es zone bar` | Ancoră în zona dată |
| `participant` | Înapoi la participant |
| `scenario` | Pe consolă: incidente de test în toate categoriile și stările, starea punctelor, două ancore cu acoperire și un anunț de căldură activ (care pleacă și la telefoanele din jur) |
| `reset` | Scoate datele scenariului și retrage anunțul de căldură |

Datele de test se recunosc: descrierile încep cu „Demo”, iar id-urile incidentelor cu `dec0de`. În demo, fiecare echipă deschide rapoartele, ca să le vadă orice telefon din prezentare.

Patru telefoane:

1. **Consola** (tabletă sau telefon pe orizontală): `control`.
2. **Staff**: `staff --es team "Medical 1" --es zone main-stage --ez lead true`.
3. **Ancora**: `anchor --es zone main-stage`, lăsată lângă scenă.
4. **Participantul**: rămâne participant, cu locația simulată în zona scenei (`simulate --es zone main-stage`).

Ordinea:

1. Instalează APK-ul debug pe toate (`adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`) și treci de onboarding.
2. Opțional, `pack` pe toate patru; pachetul se încarcă înainte de roluri, pentru că schimbarea evenimentului lasă telefonul participant.
3. Dă rolurile de mai sus.
4. Pe participant: raport medical urgent în 2 atingeri. Consola îl primește, cu triajul și locul pe grilă.
5. Pe consolă: alocă incidentul echipei „Medical 1”. Telefonul de staff sună; staff-ul apasă „Preiau”, apoi „La fața locului”, apoi „Predat la PMA”.
6. Pe participant: starea raportului se schimbă la fiecare pas; consola îi poate trimite un răspuns rapid.
7. Pe consolă: anunț de căldură către toți; harta de acoperire arată ancora.
8. Pentru capturi de ecran cu o tură plină: `scenario` pe consolă; la final `reset`.

# PingUp
O aplicație pentru Android și iPhone pentru raportarea incidentelor și mesajelor, care merge fără internet, fără date mobile, fără Wi-Fi, fără server.
Ideea de la care am pornit:  In timpul unui festival sau a unui eveniment aglomerat, trimiterea mesajelor este aproape imposibila din cauza lipsei de semnal.

## Cum funcționează, pe scurt
Telefoanele se conectează între ele prin Bluetooth Low Energy și formează o rețea (mesh). Un mesaj sare din telefon în telefon până ajunge la destinatar. Dacă A vrea să trimită ceva către D, dar nu e în raza lui, mesajul trece prin B și C.  

A  →  B  →  C  →  D

## Ce poate face
- Raportare de incidente: în doar 2 tap-uri (alegi categoria și trimiți). Poți adăuga și gravitatea, zona și o descriere, dar sunt opționale.
- Staff-ul primește alerta, apasă „Preiau" sau „Rezolvat", iar cel care a raportat vede în ce stadiu e: se transmite → ajuns la staff → preluat → rezolvat.
- Chat criptat cap-coadă cu prietenii, adăugați prin cod QR: e de ajuns ca unul să-l scaneze pe celălalt. Merg și grupuri de maxim 8 persoane.
- „Trimite zona mea" apeși pe mesaj și se deschide harta cu un pin.
- Hartă offline, desenată local, pe baza unui fișier cu zonele evenimentului (`venue.json`).
- Store-and-forward: dacă un telefon e offline un timp, primește raportul când revine în rețea.
- Fără server și fără conturi. Ce date folosește aplicația și unde ajung: [PRIVACY.md](PRIVACY.md).

## Tehnologii
Kotlin Multiplatform, Compose Multiplatform (aceleași ecrane pe Android și iPhone), Bluetooth LE (GATT; CoreBluetooth pe iPhone), libsodium, Gradle, SwiftUI doar cât să găzduiască ecranele pe iPhone, Python (script pentru chei de staff).

## Structura
Un singur cod pentru ambele telefoane:
- `core/` are toată logica (protocolul, rețeaua mesh, criptarea, chatul, incidentele), în Kotlin comun;
- `ui/` are ecranele, comune pentru Android și iPhone, plus ce e al fiecărui telefon (`androidMain`, `iosMain`: Bluetooth-ul iPhone-ului, notificările, locația, Keychain);
- `app/` e aplicația Android: Bluetooth-ul, serviciul din fundal, notificările;
- `iosApp/` e aplicația iPhone: proiectul Xcode, făcut cu XcodeGen din `project.yml`.

Detalii și reguli în [ARCHITECTURE.md](ARCHITECTURE.md).

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

### Pe iPhone

Pe un Mac, cu Xcode 26 sau mai nou și JDK-ul de mai sus:

```
brew install xcodegen
cd iosApp && xcodegen
open PingUp.xcodeproj
```

Xcode construiește întâi ecranele din Kotlin, prin Gradle, apoi aplicația. Simulatorul nu are Bluetooth și cameră: rețeaua și scanarea QR se încearcă pe un iPhone real. Testele comune rulează și pe iPhone:

```
./gradlew :core:iosSimulatorArm64Test :ui:iosSimulatorArm64Test
```

Pe un iPhone real ajunge un cont Apple gratuit, pentru build-ul Debug (Release cere Apple Developer Program, pentru notificările urgente care trec prin Focus):

1. Pe iPhone: Settings → Privacy & Security → Developer Mode, pornit (telefonul repornește). Îl legi cu cablul și alegi „Trust”.
2. În Xcode: Settings → Accounts → adaugi contul Apple. La target-ul PingUp, Signing & Capabilities → Team: echipa ta („Personal Team”).
3. Ca să nu alegi echipa din nou după fiecare `xcodegen`, o treci în `iosApp/Local.xcconfig`, care nu intră în git. ID-ul echipei îl scoți așa, după ce ai ales-o o dată:

   ```
   grep -m1 DEVELOPMENT_TEAM iosApp/PingUp.xcodeproj/project.pbxproj
   ```

   ```
   DEVELOPMENT_TEAM = ID-UL_TĂU
   PRODUCT_BUNDLE_IDENTIFIER = org.pingup.app.numele-tău
   ```

   A doua linie trebuie doar dacă Xcode spune că `org.pingup.app` e luat: un cont gratuit nu poate semna un id deja înregistrat de altă echipă, de exemplu de un coleg.
4. Alegi iPhone-ul sus în Xcode și dai Run. Prima dată, pe iPhone: Settings → General → VPN & Device Management → ai încredere în dezvoltator. Cu un cont gratuit aplicația merge 7 zile, apoi o instalezi din nou.
5. Pentru rețeaua din fundal, oprești aplicația în Xcode și o deschizi de pe ecranul principal: sub debugger, iOS n-o suspendă ca de obicei. Ce face rețeaua se vede în Console.app pe Mac: alegi iPhone-ul, apeși Start și cauți `Mesh:`.

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

În Settings → Secrets and variables → Actions: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Opțional, `SITE_DEPLOY_HOOK`: un deploy hook al site-ului din Vercel (proiectul site-ului → Settings → Git → Deploy Hooks), ca după fiecare release site-ul să se construiască din nou, cu versiunea nouă scrisă și în pagină.

Pe iPhone aplicația se publică prin App Store; ce trebuie pentru prima trimitere e în [APP_STORE.md](APP_STORE.md).

Cheile de staff ale unui eveniment: `python tools/gen_staff_keys.py`. Semințele rămân în `tools/out/`, nu în git.
Build-ul debug are o pereche separată, publică (`--demo`), pe care release-ul nu o acceptă.

Harta unui festival nou se pune în `app/src/main/assets/venue.json` și se verifică cu `python3 tools/check_venue.py app/src/main/assets/venue.json`: zonele nu au voie să se suprapună, id-urile încap în protocol, iar numerele reperelor sunt unice. Aceeași verificare rulează în CI și înainte ca `gen_staff_keys.py pack` să semneze pachetul de eveniment. Formatul e descris în script.

## Licență
Copyright © 2026 Dulgheru Ștefan Alexandru, Cîrciumaru Radu, Ilie-Goga Radu, Farkaș Andrei-Oliver.

- **Codul**: [GPL-3.0-or-later](LICENSE). Îl poți folosi, studia, modifica și distribui; o versiune modificată pe care o distribui rămâne tot sub GPL, cu codul ei sursă.
- **Documentația**, inclusiv [protocolul](PROTOCOL.md) și vectorii lui de test, și **textele din aplicație** (paginile cu informații, fișele de prim ajutor, harta demo, anunțurile): [CC BY 4.0](LICENSES/CC-BY-4.0.txt), cu numele autorilor. Oricine poate face o aplicație compatibilă.
- **Numele „Ping Up”, logo-ul și mascota** nu intră sub aceste licențe: [TRADEMARKS.md](TRADEMARKS.md). O versiune modificată are alt nume și alt logo.
- **Ce e făcut de alții** își păstrează licența: fonturile Inter și Noto Sans (OFL-1.1), iconițele Phosphor și cele ale hărții, desenate de Mapzen (MIT), datele hărții, de la OpenStreetMap (ODbL-1.0), scripturile Gradle (Apache-2.0) și bibliotecile din Gradle.

Licența fiecărui fișier e în [REUSE.toml](REUSE.toml), iar textele licențelor în [LICENSES/](LICENSES). În aplicație, „Eu › Licențe open source” arată licența Ping Up și tot ce folosește, cu textul fiecărei licențe; listele le rescrie `./gradlew exportLicenses`. Pentru alte condiții, de exemplu Ping Up într-o aplicație care nu e open source, scrie-ne: contactul e în [PRIVACY.md](PRIVACY.md).

Ping Up nu e un serviciu de urgență și nu înlocuiește 112. E oferit fără nicio garanție (GPL, secțiunile 15 și 16).

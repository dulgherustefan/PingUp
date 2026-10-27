# PingUp
*In English: [summary below](#english).*

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

`test` rulează testele din toate modulele (`:core`, `:ui`, `:app`). Apoi instalezi pe telefon (cu USB debugging activat):
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
`ping-up.apk`, SHA-256-ul și amprenta certificatului. Notele release-ului sunt secțiunea versiunii din [CHANGELOG.md](CHANGELOG.md). Site-ul îl preia singur în câteva minute.

```
git tag v0.5.0
git push origin v0.5.0
```

O singură dată, cheia de semnare (păstreaz-o: fără ea nu mai poți publica actualizări peste instalările existente):

```
keytool -genkeypair -keystore release.jks -alias pingup -keyalg RSA -keysize 4096 -validity 10000
base64 -i release.jks
```

În Settings → Secrets and variables → Actions: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Opțional, `SITE_DEPLOY_HOOK`: un deploy hook al site-ului din Vercel (proiectul site-ului → Settings → Git → Deploy Hooks), ca după fiecare release site-ul să se construiască din nou, cu versiunea nouă scrisă și în pagină.

Pe iPhone aplicația se publică prin App Store; ce trebuie pentru prima trimitere e în [APP_STORE.md](APP_STORE.md).

Cheile de staff ale unui eveniment: `python tools/gen_staff_keys.py` (`pip install pynacl "qrcode[pil]"`). Semințele rămân în `tools/out/`, nu în git.
Build-ul debug are o pereche separată, publică (`demo`), pe care release-ul nu o acceptă.

### Cheia rădăcină

Release-ul încarcă pachete de eveniment și coduri de staff pe tură (SPS2) doar dacă `app/src/main/assets/staff_public.json` are cheia publică a rădăcinii; fără ea, `release.yml` se oprește. Rădăcina semnează doar cheile organizatorilor, de câteva ori pe an, așa că sămânța ei nu stă pe laptop, ci în două fișiere criptate cu o parolă, pe două stickuri. Se face o singură dată:

1. Două stickuri goale, Wi-Fi-ul oprit și o parolă de cel puțin 16 caractere (de exemplu cinci cuvinte alese la întâmplare), scrisă pe hârtie.
2. `python3 tools/gen_staff_keys.py root --out /Volumes/STICK1/pingup-root.json /Volumes/STICK2/pingup-root.json`
3. `staff_public.json` intră în commit (are doar cheia publică); id-ul cheii afișat se notează lângă parolă.
4. Stickurile stau în două locuri diferite, iar hârtia cu parola în al treilea. Fără un stick și parolă nu se mai pot semna organizatori, iar o rădăcină nouă cere o versiune nouă a aplicației.

Un organizator nou: `python3 tools/gen_staff_keys.py organiser --name "Fest SRL" --root /Volumes/STICK1/pingup-root.json`.

Harta unui festival nou se pune în `app/src/main/assets/venue.json` și se verifică cu `python3 tools/check_venue.py app/src/main/assets/venue.json`: zonele nu au voie să se suprapună, id-urile încap în protocol, iar numerele reperelor sunt unice. Aceeași verificare rulează în CI și înainte ca `gen_staff_keys.py pack` să semneze pachetul de eveniment. Formatul e descris în script.

## Licență
Copyright © 2026 Dulgheru Ștefan Alexandru, Cîrciumaru Radu, Ilie-Goga Radu, Farkaș Andrei-Oliver.

- **Codul**: [GPL-3.0-or-later](LICENSE). Îl poți folosi, studia, modifica și distribui; o versiune modificată pe care o distribui rămâne tot sub GPL, cu codul ei sursă.
- **Documentația**, inclusiv [protocolul](PROTOCOL.md) și vectorii lui de test, și **textele din aplicație** (paginile cu informații, fișele de prim ajutor, harta demo, anunțurile): [CC BY 4.0](LICENSES/CC-BY-4.0.txt), cu numele autorilor. Oricine poate face o aplicație compatibilă.
- **Numele „Ping Up”, logo-ul și mascota** nu intră sub aceste licențe: [TRADEMARKS.md](TRADEMARKS.md). O versiune modificată are alt nume și alt logo.
- **Ce e făcut de alții** își păstrează licența: fonturile Inter și Noto Sans (OFL-1.1), iconițele Phosphor și cele ale hărții, desenate de Mapzen (MIT), datele hărții, de la OpenStreetMap (ODbL-1.0), scripturile Gradle (Apache-2.0) și bibliotecile din Gradle.

Licența fiecărui fișier e în [REUSE.toml](REUSE.toml), iar textele licențelor în [LICENSES/](LICENSES). În aplicație, „Eu › Licențe open source” arată licența Ping Up și tot ce folosește, cu textul fiecărei licențe; listele le rescrie `./gradlew exportLicenses`. Pentru alte condiții, de exemplu Ping Up într-o aplicație care nu e open source, scrie-ne: contactul e în [PRIVACY.md](PRIVACY.md).

Ping Up nu e un serviciu de urgență și nu înlocuiește 112. E oferit fără nicio garanție (GPL, secțiunile 15 și 16).

## English

**Ping Up is a free Android and iPhone app for festivals and crowded events that keeps messages and safety reports moving when the mobile network fails. It needs no internet, no servers and no accounts.**

Phones connect to each other over Bluetooth Low Energy and form a mesh: a message hops from phone to phone until it reaches its recipient, and a phone that is out of range gets it when it comes back. Everything personal is end-to-end encrypted with libsodium.

**What it does**
- **Incident reports to the festival staff** in two taps (category, then send), with optional severity, zone and description. The reporter follows the state: on its way, reached the staff, taken, resolved.
- **Staff roles without a server:** shift certificates signed by the organiser's keys, a control console for the control room, and fixed anchors that relay and keep messages.
- **Chat with friends**, added by scanning a QR code once, and groups of up to 8.
- **An offline map** of the event's zones and landmarks; on Android, an OpenStreetMap base map drawn with MapLibre.
- **Location only by choice:** in a report, or shared with chosen friends for a set time.
- **Offline first-aid sheets and information pages**, in Romanian and English.

**Status:** Android releases are on [GitHub Releases](https://github.com/dulgherustefan/PingUp/releases) and [ping-up.org](https://ping-up.org). The iPhone app shares the same code and runs on the simulator; the mesh still needs testing on real phones and in the field.

**Build:** with JDK 21 (or Android Studio's), `./gradlew assembleDebug test` builds the Android app and runs the tests. On a Mac with Xcode 26 or newer, `brew install xcodegen`, then `cd iosApp && xcodegen && open PingUp.xcodeproj`; `./gradlew :core:iosSimulatorArm64Test :ui:iosSimulatorArm64Test` runs the tests on iOS.

**Code:** `core/` holds the protocol, the mesh, the cryptography, chat and incidents in shared Kotlin; `ui/` holds the screens (Compose Multiplatform) for both phones; `app/` is the Android app and `iosApp/` the iPhone one. The wire protocol, with golden test vectors for other implementations, is specified in [PROTOCOL.md](PROTOCOL.md).

**Licence:** the code is under [GPL-3.0-or-later](LICENSE); the documentation, the protocol with its test vectors and the app's content (information pages, first-aid sheets, the demo map, notices) are under [CC BY 4.0](LICENSES/CC-BY-4.0.txt); the name, logo and mascot are not licensed ([TRADEMARKS.md](TRADEMARKS.md)). Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Privacy: [PRIVACY.md](PRIVACY.md).

Ping Up is not an emergency service and does not replace 112.

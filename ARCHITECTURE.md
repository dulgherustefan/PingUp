# Arhitectura

Ping Up are trei module Gradle și aplicația de iPhone. Regula de bază: tot ce face aplicația să meargă stă în `:core`, care nu are voie să importe nimic din Android; ecranele stau în `:ui`, aceleași pe ambele telefoane, iar `:app` aduce doar ce ține de Android (Bluetooth, locație, keystore, notificări, serviciul din prim-plan). `:core` și `:ui` sunt Kotlin Multiplatform: același cod se compilează pentru Android și pentru iPhone. `iosApp/` e aplicația de iPhone, care găzduiește ecranele și dă sesiunii telefonul.

```
          :app  (Android)
   ┌──────────────────────────────┐
   │ ui/          ecranele         │
   │ service/     serviciul mesh   │
   │ platform/    Bluetooth, GPS,  │──── implementează interfețele din :core
   │              keystore         │     (AppPlatform, Radio, Crypto, Clock)
   │ AppContainer sesiunea comună  │
   └──────────────┬───────────────┘
                  │ depinde de
   ┌──────────────▼───────────────┐
   │ :core  (Kotlin Multiplatform) │
   │ protocol → mesh → chat,       │
   │                   incidents   │
   │ crypto, data, venue, util     │
   │ session: PingUpSession        │
   └──────────────────────────────┘
```

## `:core`: logica, fără Android

`core/src/commonMain/kotlin/ro/safetyplease/core/`. Ce diferă de la o platformă la alta e puțin și stă în `jvmMain/` și `iosMain/`, prin `expect`/`actual`: SHA-256 (`MessageDigest` / CommonCrypto), libsodium (lazysodium / biblioteca statică legată prin cinterop), scrierea sincronizată a fișierelor (`fd.sync()` / `F_BARRIERFSYNC`), formatarea zecimalelor și descompunerea NFD a textului. Fișierele trec prin Okio, timpul local prin kotlinx-datetime, lacătele prin atomicfu. Pe iOS, libsodium se compilează din sursa semnată cu `tools/build_libsodium_ios.sh`, pe care Gradle îl rulează singur, doar pe macOS.

| Pachet | Ce face |
|---|---|
| `protocol/` | Formatul pachetelor pe fir: codare, decodare, fragmentare. Specificația, cu vectorii de test, e în `PROTOCOL.md`. |
| `mesh/` | Rutarea mesh: cui trimitem, ce am văzut deja, cozi, limite, consum. `Radio` e interfața pe care o implementează Bluetooth-ul. |
| `ble/` | Ce au în comun radiourile Bluetooth ale celor două telefoane: serviciul GATT, cei șase octeți din reclamă, bitul nostru din „overflow area” a unui iPhone cu aplicația în fundal, media semnalului și ce ajunge la motor din scanare, ciclul de scanare al planului de putere. |
| `crypto/` | Identitatea, criptarea cap-coadă (libsodium), lanțul de chei al evenimentului (certificate de tură, cheia rapoartelor pe zi, revocări), codurile QR de prieten și de staff. |
| `event/` | Pachetul de eveniment semnat (venue, grila, fișele, paginile de informații, programul scenelor, planul ilustrat al organizatorului cu cele patru colțuri, cheile evenimentului), verificat pe lanțul de chei, pachetele ținute pe telefon și cel folosit, exercițiile cu fișierele lor separate (`PROTOCOL.md`, secțiunea 16). |
| `chat/` | Prietenii, grupurile, mesajele și reîncercările; prezentarea trimisă la scanare (o scanare îi face prieteni pe amândoi), blocarea, ieșirea din grup cu anunț și ultima zonă a prietenilor (fără difuzare periodică). |
| `incidents/` | Rapoartele, confirmările și stările din teren ale staff-ului, răspunsurile rapide ale consolei către raportor și actualizările raportorului, urgența dedusă din triaj, anulările de verificat și semnalarea rapoartelor venite din aceeași zonă în același timp, semnalul de aglomerație pentru consolă (`CrowdWatch`), codurile rapide de staff (`StaffCodes`) și raportul discret (`Discreet`, rutat către rolul Safeguarding). Rutarea pe roluri (`Routing`): cine primește alerta insistentă, cine doar vibrația și când se extinde; `AlertPlanner` o transformă în notificări, fără repetări inutile. |
| `announce/` | Anunțurile semnate ale consolei: alertă (informare, pauză, evacuare, cu planul de evacuare), nivelul de căldură, schimbări de program și transport, starea hărții (2 biți pe zonă) și setul de zone căruia îi e adresat un anunț (`NoticeExtras`). `MapState`, starea zonelor și evacuarea în vigoare citite din ultimele anunțuri, e expusă de sesiune; rutele, ecranele și consola o citesc pe aceeași. Formatul de pe fir, ultimul anunț pe subiect, retragerea, retrimiterea de către consolă și șabloanele RO/EN din pachet (`PROTOCOL.md`, secțiunea 6.8). |
| `control/` | Jurnalul consolei de control: rapoartele, ACK-urile cu semnături și deciziile operatorului, starea fiecărui incident pe consolă, exportul CSV și JSON. Tot aici, exportul metricilor de pilot și legătura lor cu jurnalul, mesajele canalului de control (`ControlMessages`), starea punctelor trimisă de staff (`PointBoard`) și harta de acoperire din ancore (`Coverage`). |
| `report/` | Raportul de tură și de ediție (`EditionReports`) pentru ISU, asigurator și sponsor, din jurnalele consolei (real și exercițiu, separat), metrici și ancorele auzite: incidente pe tip, urgență, oră și pătrat, timpi raport → primit → preluat → închis (mediană și p90, nearest-rank), escaladări, alarme false și anulări, sănătatea rețelei și secțiunea „ce acoperă și ce nu”, generată mereu. `ReportExport` scrie două CSV-uri fără id-uri, porecle, echipe, descrieri sau coordonate. |
| `metrics/` | Metricile de pilot: timpii raportorului, instantaneele rețelei mesh și jurnalul rețelei mobile (secțiunea de mai jos). |
| `data/` | Modelele salvate, `JsonStore` (fișiere JSON scrise atomic) și `ChatStore` (chat-ul, câte un fișier pe conversație). |
| `venue/` | Harta evenimentului din `venue.json` sau din pachetul de eveniment: zone, punct de întâlnire, grila sitului (A1, B2...), apa, gardurile cu porțile lor, zonele interzise, reperele de întâlnire și planurile de evacuare, verificate de `EvacuationPlans`. `WalkDistance` e distanța de mers între două puncte; până la rutele pe jos, `StraightLine` (linia dreaptă), expusă de sesiune ca `walkDistance`. |
| `location/` | Poziția, aceeași pe orice telefon: porțile de calitate ale fixărilor (`FixGate`: precizie, viteze imposibile, apă), ciclul de lucru al GNSS-ului (`DutyCycle`), direcția din curs sau busolă, regula L5 și jurnalul de diagnostic GNSS cu CSV-ul lui. `Positioning` face poziția arătată și trimisă: filtrul de afișare (`DisplayFilter`, Kalman cu viteză constantă, niciodată în afara cercului de precizie al fixării), reperul la care spune omul că stă (cel mult 10 minute, până îl contrazice o fixare bună) și, pe Android, după setare, pașii (`StepDetector`, din accelerometru) care acoperă golurile GNSS de cel mult 30 s, cât îi cere un raport deschis, o partajare exactă sau ruta pe jos (care îi spune prin `Positioning.navigating`). „M-am pierdut de grup”, ultimul semnal la 5% baterie și ultima poziție din raportul de persoană pierdută stau în `position/Regasire`. |
| `session/` | `PingUpSession`: ce trăiește cât procesul (identitatea, magazinele, motorul mesh, managerii, buclele de fundal, `runMesh` cu planul de putere și bătăile ancorei), la fel pe Android și pe iPhone. `AppPlatform` e ce cere de la telefon: cheile (`Vault`), Bluetooth-ul (`MeshRadio`), notificările, GPS-ul, bateria, fișierele și asset-urile aplicației. |
| `util/` | Octeți, hex, `sha256`, ceasul. |

Testele de aici stau în `commonTest` și rulează pe JVM, fără emulator (`./gradlew :core:test`); pe un Mac rulează și pe simulatorul de iPhone (`./gradlew :core:iosSimulatorArm64Test`). Telefoanele simulate din `SimNet` și `TestPhone` verifică scenarii întregi, de la raport până la confirmarea staff-ului.

## `:ui`: ecranele comune

`ui/src/commonMain/`, în Compose Multiplatform: toate ecranele, `AppViewModel` și navigarea (`MainScreen`), câte un pachet pe funcționalitate (`chat/`, `people/`, `report/`, `incidents/`, `map/`, `me/`, `control/`...), cu tema și componentele în `designsystem/`. Ce cer de la telefon fără să fie sesiune trece prin `PhoneUi` (pornirea rețelei, permisiunile, 112, share sheet-ul, lanterna, toast-urile, harta offline, diagnosticul GNSS, ritmul GPS-ului la un raport sau la o partajare); unde telefonul nu are ce da, ca harta MapLibre pe iPhone, membrul e null și ecranul folosește harta desenată (`VenueMap`). Ce desenează funcționalitățile peste hartă (trasee, stările zonelor, pinuri, planul organizatorului) e un `MapOverlay`: fiecare își pune stratul lui în `MapOverlays` (din `AppViewModel`), iar ambele hărți, MapLibre pe Android (`PingMapEngine.setOverlay`) și `VenueMap`, desenează straturile unite; ce trebuie să fie un ecran al telefonului e `expect`/`actual` în `androidMain/` și `iosMain/`: camera care citește coduri QR (CameraX cu zxing, AVFoundation), alegerea fișierelor, PDF-ul raportului (`PdfDocument`, contextul PDF din UIKit, cu așezarea în pagină comună în `ReportLayout`), permisiunile, consola în landscape, blur-ul. Codul QR se desenează din `qrcode-kotlin`, același pe ambele telefoane. Textele, în română și engleză, stau o singură dată pentru ambele aplicații, în `composeResources/values*/strings.xml`, iar cele ale unei funcționalități noi în fișierul ei, `strings_<nume>.xml` (`route`, `control`, `regasire`, `layers`, `safety`, `staff`), ca lucrul în paralel să nu se ciocnească. Pe Android, codul din afara ecranelor (notificările) le citește tot de acolo, ca `R.string`, copiate la build. Limba aplicației se fixează la pornire, pe Android în `AppLocale`, pe iPhone în `pinLanguage`, ca textele să-și ia și regulile de plural din ea: un telefon în germană primește româna cu pluralul românesc. Compose Multiplatform completează doar `%1$s` și `%1$d` și afișează escape-urile Android (`\'`, `\"`) așa cum sunt, de aceea textele folosesc numai argumente numerotate și ghilimelele și apostroful tipografice; `TranslationTest` verifică. Testele logicii ecranelor (navigarea, consola, rapoartele, harta, paginile, fișele de prim ajutor) stau în `ui/src/commonTest` și rulează pe JVM (`./gradlew :ui:test`) și pe simulatorul de iPhone (`./gradlew :ui:iosSimulatorArm64Test`); în `androidHostTest` rămân doar cele cu biblioteci Java (zxing, `java.text`).

## `:app`: Android

`app/src/main/java/ro/safetyplease/app/`

| Pachet | Ce face |
|---|---|
| `App`, `MainActivity`, `AppContainer`, `AndroidPlatform`, `AndroidPhoneUi` | Pornirea. `AppContainer` e sesiunea comună (`PingUpSession`) cu piesele Android pe tipurile lor; `AndroidPlatform` îi dă telefonul: Keystore, BLE, notificări, GPS, bateria, asset-urile; `AndroidPhoneUi` dă ecranelor comune ce cer de la telefon. |
| `platform/` | Adaptoarele Android: `ble/` (radio BLE GATT, peste `core/ble/`), `location/` (GNSS pe ciclu de lucru, diagnosticul și datele orbitelor descărcate cât e internet), `map/` (harta MapLibre cu datele zonei în APK), `keystore/`, `network/` (rețeaua mobilă, pentru jurnalul de pilot), `power/`, `share/` (fișierele exportate, spre share sheet), `torch/`. |
| `service/` | Serviciul din prim-plan care ține rețeaua pornită și notificările. |
| `text/` | `SharedTexts`: textele comune citite din codul Android din afara ecranelor. |

`src/debug/` conține modul demo (simulare, comenzi adb); `src/release/` are aceeași clasă `Demo`, dar goală.

## `iosApp/` și `ui/src/iosMain/`: iPhone

`iosApp/` e doar gazda Swift: `PingUpApp.swift` arată `MainViewController()` și spune iOS-ului ce orientări sunt permise (landscape doar pe consolă). Proiectul Xcode se generează din `project.yml` (`xcodegen`) și construiește framework-ul Kotlin prin Gradle; tot el pune în aplicație asset-urile din `app/src/main/assets`, iar cele de debug (cheile demo de staff) doar în build-ul Debug.

`ui/src/iosMain/kotlin/ro/safetyplease/ui/ios/` e telefonul pentru sesiune: `IosApp` ține sesiunea și starea ecranelor cât procesul, `IosPlatform` e `AppPlatform` (Keychain în `KeychainVault`, folderele aplicației, asset-urile, `IosNotifications`, `IosLocation`, bateria, `IosNetwork` pentru jurnalul rețelei mobile, descărcarea pachetelor), `IosPhoneUi` e `PhoneUi`, iar `Toasts` dă notele scurte pe care iOS nu le are. Cheile din Keychain se deschid de la primul unlock după repornire și nu pleacă de pe telefon; folderul de date nu intră în backup, ca pe Android. iOS păstrează însă Keychain-ul și după ce aplicația e ștearsă, așa că o pornire fără folderul de date (o instalare nouă) șterge întâi cheile rămase: ștergerea aplicației șterge tot, cum spune PRIVACY.md. iOS nu lasă o aplicație să se repornească singură, așa că alegerea altui eveniment și ștergerea datelor fac o sesiune nouă în același proces (`IosApp.restart`, `IosApp.wipe`). `IosRadio` e rețeaua peste CoreBluetooth, pe același serviciu ca pe Android: serverul GATT la care se conectează celelalte telefoane și un central care se conectează la cele găsite. Ce trebuie să fie identic pe ambele telefoane stă în `core/ble/`: UUID-urile, cei șase octeți din reclamă (`AdvertData`), media semnalului și ce ajunge la motor (`Sightings`), ciclul de scanare (`ScanCycle`). iOS nu lasă o aplicație să pună date în reclamă, așa că un iPhone spune că nu poate reclama și pornește el legăturile: cât aplicația e în față, telefoanele Android nu se conectează la el, iar între două iPhone-uri regula duplicatelor închide a doua legătură. Cu aplicația în fundal, iOS mută UUID-ul într-un bit din datele de producător Apple („overflow area”, `OverflowArea`), pe care telefoanele Android îl caută și ele și se conectează la iPhone, fiindcă în fundal el scanează rar; capătul a cărui legătură o închide regula duplicatelor nu se mai întoarce la adresa aceea cât ține cealaltă. Nici nu poate închide o legătură deschisă de altcineva: trimite notificarea goală care îi spune celuilalt să plece. Bluetooth-ul merge și cu aplicația în fundal (`UIBackgroundModes`), iar iOS o poate porni singur pentru Bluetooth; de aceea sesiunea pornește din app delegate, nu din primul ecran. În fundal iOS rulează aplicația doar cât îi dă un eveniment Bluetooth, deci acolo nu merge niciun timer: scanarea nu face pauze și o pornesc din nou chiar evenimentele, imediat după o legătură pierdută, altfel cel mult o dată pe minut, cum cere Apple. Centralul și serverul sunt făcute o dată pe proces și trec de la un radio la următorul (o sesiune nouă pentru alt eveniment), fiindcă iOS le restaurează după identificator. Simulatorul nu are Bluetooth: radioul se încearcă pe telefoane reale.

## Metrici de pilot

Pilotul măsoară ce n-a publicat nimeni: dacă raportul ajunge la consolă, în cât timp se întoarce ACK-ul și unde cade rețeaua mobilă. Totul rămâne pe telefon până la un export făcut de om, din Eu › Exportă metricile (și cu comanda de debug `metrics`). Pe fir nu se schimbă nimic. Exportul nu conține nodeId, porecle, nume de echipă, descrieri sau coordonate; locul e doar pătratul din grila sitului (`SiteGrid`, ~50 m).

- **Raportorul** (`MyReport`, `metrics/ReportTiming.kt`). Pe ceasul raportorului: `receivedAt`, primul ACK venit de la alt telefon, oricare ar fi starea; `acknowledgedAt`, primul care spune că un om îl are (ACKNOWLEDGED, ON_SCENE, HANDED_OVER sau RESOLVED, aceeași regulă ca pe consolă); `firstAckHops`, hop-urile primului ACK; `republished`, de câte ori a fost republicat după o repornire. ACK-urile semnate chiar pe telefon nu se cronometrează, așa că un raport scris de staff are timpi doar de la ceilalți. În export: `ms_to_received` și `ms_to_acknowledged`, adică diferențe pe același ceas.
- **Rețeaua mesh** (`MeshCounters`, `metrics/MeshMetrics.kt`). La 5 minute, un instantaneu cu ce s-a întâmplat de la cel precedent: pachete trimise, primite, releuate și aruncate pe motiv, pe tip de pachet, histograma hop-urilor rapoartelor și ACK-urilor noi, legături deschise și închise (și pe oră), plus legăturile, vecinii văzuți și rapoartele ținute în acel moment. Rolul telefonului e notat. Se păstrează ultimele 864 (trei zile), în `metrics.json`.
- **Rețeaua mobilă** (`metrics/NetworkLog.kt`, `platform/network/MobileNetwork.kt`). Doar pe telefoanele organizatorului (staff, consolă, ancoră) și doar cu comutatorul din Eu pornit; implicit e oprit. La 2 minute: nivelul semnalului (0–4) și dBm, tehnologia celulei (lte, nr, gsm...), ce poartă datele (mobil, Wi-Fi, nimic), dacă Android a validat internetul și, numai pe rețea mobilă, o cerere HEAD la `connectivitycheck.gstatic.com/generate_204` (aceeași adresă pe care o întreabă Android), reușită dacă răspunde 204, cu durata. Pătratul vine dintr-o poziție GPS mai nouă de 2 minute; fără ea rămâne gol. Nu cere permisiuni la rulare: semnalul vine fără READ_PHONE_STATE de la Android 9 (pe 8 nu se notează), iar starea rețelei și cererea cer doar `ACCESS_NETWORK_STATE` și `INTERNET`, acordate la instalare. Mesh-ul nu folosește internetul. Ultimele 2.160 de eșantioane (trei zile), în `network_log.json`.
- **Legătura cu consola** (`control/MetricsJoin.kt`). Operatorul adaugă pe consolă exporturile JSON ale raportorilor (Eu › Adaugă metricile raportorilor). La exportul consolei, `livrare.csv` pune pe un rând, după `incidentId`, ce spune raportorul (creat, timpii lui) și ce spune jurnalul consolei (când l-a deschis, cu câte hop-uri). Cele două ceasuri nu se scad niciodată unul din altul: livrarea e da sau nu, iar latența e doar dus-întors pe ceasul raportorului.

Fișierele exportului (`metrici-<dată>`): `-rapoarte.csv`, `-retea.csv`, `-mobil.csv` (dacă jurnalul are eșantioane), `-livrare.csv` (pe consolă) și `.json` (formatul `pingup-metrics`, versiunea 1, cu tot ce e mai sus). Coloanele sunt în `MetricsExport`; celulele sunt coduri, ca în `PROTOCOL.md`, iar orele sunt locale, ISO 8601.

## Autonomie pe tură

Totul rămâne pe telefon, nimic nu circulă pe mesh. `BatteryMonitor` (`platform/power`) citește bateria cât trăiește procesul, iar `BatteryWatch` (`core/mesh`) îi dă profilul de putere; `BatteryDrain` măsoară ritmul de scădere din momentele în care nivelul a coborât cu un procent, pe profilul de putere în vigoare, și dă minutele rămase (rotunjite la 5) abia după 15 minute și cel puțin 2 procente. Un alt profil, un încărcător sau un nivel care urcă încep o măsurătoare nouă. Sub 20% fără încărcător intră profilul ECONOMY (scanare mai rară) și un banner pe Hartă și în Eu; sub 5% (`PowerPolicy.textOnly`) harta e înlocuită de o notă și animațiile sunt oprite (`LocalTextOnly`, `LocalReduceMotion`). Paginile de informații stau în `ui/info`, din pachet sau din `assets/pages`.

## Reguli

1. **`:core` nu importă Android.** Compilatorul o verifică: modulul nu are Android pe classpath.
2. **Telefonul intră prin interfețe.** `AppPlatform` (cu `Vault`, `Notifications`, `MeshRadio`), `Radio`, `Crypto` și `Clock` sunt definite în `:core`; pe Android le implementează `AndroidPlatform` și `platform/`, pe iPhone `IosPlatform` și vecinii lui din `ui/src/iosMain/.../ios/`, în teste `FakePlatform` și `SimNet` din `:core`. Ecranele cer telefonul prin `PhoneUi`, din `:ui`.
3. **Ecranele nu se importă între ele.** Ce folosesc două ecrane merge în `ui/common/`; ce e pur vizual, în `designsystem/` din `:ui`, care nu știe nimic despre mesaje sau incidente.
4. **Dependențele merg într-o singură direcție:** ecranele → `ui/common` → `designsystem`, și totul → `:core`.

## Unde pun cod nou

- Un tip de pachet nou sau o regulă de rutare: `core/protocol/` sau `core/mesh/`, cu test în `core/src/commonTest/`. Dacă se schimbă octeții de pe fir, se actualizează și `PROTOCOL.md` și vectorii golden.
- Un ecran nou: un pachet nou în `ui/src/commonMain/.../ui/`; mută în `common/` doar ce ajunge să fie folosit și de alt ecran. Ce trebuie să arate altfel pe un telefon decât pe altul e `expect`/`actual`, lângă ecran.
- Ceva ce cere un API al telefonului: o interfață în `:core` (de obicei în `AppPlatform`) dacă o folosește sesiunea, în `PhoneUi` dacă o folosesc doar ecranele, cu implementarea în `platform/` pe Android și în `ui/src/iosMain/.../ios/` pe iPhone.

## De ce așa

- **Trei module, nu zece.** Ghidul Android avertizează că modulele prea mărunte aduc doar „build complexity and boilerplate” [1]. La aproape 15.000 de linii, o împărțire pe funcționalități ca în Now in Android [3] ar fi exagerată; separarea logică / Android aduce aproape tot câștigul. `:ui` există pentru că ecranele trebuie să ruleze și pe iPhone.
- **Logica separată de platformă,** ca la Briar: protocolul `bramble-core` e Java pur, iar `bramble-android` aduce doar adaptoarele [4]. Așa se testează rapid și se poate refolosi pe altă platformă.
- **Straturi UI și date** sunt „strongly recommended” în recomandările Android [2]; DI manual e acceptat pentru aplicații de mărimea asta [2].
- **Pachete pe funcționalități în `ui/`** și separarea `designsystem` / `common` urmează Now in Android (`core:designsystem`, `core:ui`, `feature:*`) [3].

1. https://developer.android.com/topic/modularization
2. https://developer.android.com/topic/architecture/recommendations
3. https://github.com/android/nowinandroid/blob/main/docs/ModularizationLearningJourney.md
4. https://code.briarproject.org/briar/briar (modulele `bramble-*`)

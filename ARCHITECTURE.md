# Arhitectura

Ping Up are două module Gradle. Regula de bază: tot ce face aplicația să meargă stă în `:core`, care nu are voie să importe nimic din Android; `:app` aduce doar ce ține de telefon (Bluetooth, locație, keystore, notificări) și interfața.

```
          :app  (Android)
   ┌──────────────────────────────┐
   │ ui/          ecranele         │
   │ service/     serviciul mesh   │
   │ platform/    Bluetooth, GPS,  │──── implementează interfețele din :core
   │              keystore         │     (Radio, Crypto, Clock)
   │ AppContainer leagă totul      │
   └──────────────┬───────────────┘
                  │ depinde de
   ┌──────────────▼───────────────┐
   │ :core  (Kotlin pur, JVM)      │
   │ protocol → mesh → chat,       │
   │                   incidents   │
   │ crypto, data, venue, util     │
   └──────────────────────────────┘
```

## `:core`: logica, fără Android

`core/src/main/kotlin/ro/safetyplease/core/`

| Pachet | Ce face |
|---|---|
| `protocol/` | Formatul pachetelor pe fir: codare, decodare, fragmentare. Specificația, cu vectorii de test, e în `PROTOCOL.md`. |
| `mesh/` | Rutarea mesh: cui trimitem, ce am văzut deja, cozi, limite, consum. `Radio` e interfața pe care o implementează Bluetooth-ul. |
| `crypto/` | Identitatea, criptarea cap-coadă (libsodium), lanțul de chei al evenimentului (certificate de tură, cheia rapoartelor pe zi, revocări), codurile QR de prieten și de staff. |
| `event/` | Pachetul de eveniment semnat (venue, grila, fișele, cheile evenimentului), verificat pe lanțul de chei, pachetele ținute pe telefon și cel folosit, exercițiile cu fișierele lor separate (`PROTOCOL.md`, secțiunea 16). |
| `chat/` | Prietenii, grupurile, mesajele și reîncercările; blocarea, ieșirea din grup cu anunț și ultima zonă a prietenilor (fără difuzare periodică). |
| `incidents/` | Rapoartele, confirmările și stările din teren ale staff-ului, răspunsurile rapide ale consolei către raportor și actualizările raportorului, urgența dedusă din triaj, anulările de verificat și semnalarea rapoartelor venite din aceeași zonă în același timp, semnalul de aglomerație pentru consolă (`CrowdWatch`), codurile rapide de staff (`StaffCodes`) și raportul discret (`Discreet`, rutat către rolul Safeguarding). Rutarea pe roluri (`Routing`): cine primește alerta insistentă, cine doar vibrația și când se extinde; `AlertPlanner` o transformă în notificări, fără repetări inutile. |
| `control/` | Jurnalul consolei de control: rapoartele, ACK-urile cu semnături și deciziile operatorului, starea fiecărui incident pe consolă, exportul CSV și JSON. Tot aici, exportul metricilor de pilot și legătura lor cu jurnalul. |
| `metrics/` | Metricile de pilot: timpii raportorului, instantaneele rețelei mesh și jurnalul rețelei mobile (secțiunea de mai jos). |
| `data/` | Modelele salvate, `JsonStore` (fișiere JSON scrise atomic) și `ChatStore` (chat-ul, câte un fișier pe conversație). |
| `venue/` | Harta evenimentului din `venue.json` sau din pachetul de eveniment: zone, punct de întâlnire, grila sitului (A1, B2...). |
| `util/` | Octeți, hex, `sha256`, ceasul. |

Testele de aici rulează pe JVM simplu, fără emulator. Telefoanele simulate din `SimNet` și `TestPhone` verifică scenarii întregi, de la raport până la confirmarea staff-ului.

## `:app`: Android și interfața

`app/src/main/java/ro/safetyplease/app/`

| Pachet | Ce face |
|---|---|
| `App`, `MainActivity`, `AppContainer` | Pornirea și legarea dependențelor (DI manual, un singur container). |
| `platform/` | Adaptoarele Android: `ble/` (radio BLE GATT), `location/`, `keystore/`, `network/` (rețeaua mobilă, pentru jurnalul de pilot). |
| `service/` | Serviciul din prim-plan care ține rețeaua pornită și notificările. |
| `text/` | Textele pentru codurile de protocol (categorii, stări), folosite de notificări și de ecrane. |
| `ui/` | `AppViewModel` și `MainScreen` (tab-urile și navigarea), apoi câte un pachet pe funcționalitate. |
| `ui/designsystem/` | Tema, culorile, tipografia, componentele de bază. Nu știe nimic despre mesaje sau incidente. |
| `ui/common/` | Componente folosite de mai multe ecrane: harta, cardul QR, antetul de incident, starea rețelei. |
| `ui/chat/`, `people/`, `report/`, `incidents/`, `map/`, `me/`, `onboarding/`, `staffcodes/`, `safety/` | Ecranele, câte un pachet pe funcționalitate. |
| `ui/control/` | Consola camerei de control, în landscape pe o tabletă: coada, harta cu grila, alocarea, fraza pentru stație, rețeaua și exportul jurnalului. |

`src/debug/` conține modul demo (simulare, comenzi adb); `src/release/` are aceeași clasă `Demo`, dar goală.

## Metrici de pilot

Pilotul măsoară ce n-a publicat nimeni: dacă raportul ajunge la consolă, în cât timp se întoarce ACK-ul și unde cade rețeaua mobilă. Totul rămâne pe telefon până la un export făcut de om, din Eu › Exportă metricile (și cu comanda de debug `metrics`). Pe fir nu se schimbă nimic. Exportul nu conține nodeId, porecle, nume de echipă, descrieri sau coordonate; locul e doar pătratul din grila sitului (`SiteGrid`, ~50 m).

- **Raportorul** (`MyReport`, `metrics/ReportTiming.kt`). Pe ceasul raportorului: `receivedAt`, primul ACK venit de la alt telefon, oricare ar fi starea; `acknowledgedAt`, primul care spune că un om îl are (ACKNOWLEDGED, ON_SCENE, HANDED_OVER sau RESOLVED, aceeași regulă ca pe consolă); `firstAckHops`, hop-urile primului ACK; `republished`, de câte ori a fost republicat după o repornire. ACK-urile semnate chiar pe telefon nu se cronometrează, așa că un raport scris de staff are timpi doar de la ceilalți. În export: `ms_to_received` și `ms_to_acknowledged`, adică diferențe pe același ceas.
- **Rețeaua mesh** (`MeshCounters`, `metrics/MeshMetrics.kt`). La 5 minute, un instantaneu cu ce s-a întâmplat de la cel precedent: pachete trimise, primite, releuate și aruncate pe motiv, pe tip de pachet, histograma hop-urilor rapoartelor și ACK-urilor noi, legături deschise și închise (și pe oră), plus legăturile, vecinii văzuți și rapoartele ținute în acel moment. Rolul telefonului e notat. Se păstrează ultimele 864 (trei zile), în `metrics.json`.
- **Rețeaua mobilă** (`metrics/NetworkLog.kt`, `platform/network/MobileNetwork.kt`). Doar pe telefoanele organizatorului (staff, consolă, ancoră) și doar cu comutatorul din Eu pornit; implicit e oprit. La 2 minute: nivelul semnalului (0–4) și dBm, tehnologia celulei (lte, nr, gsm...), ce poartă datele (mobil, Wi-Fi, nimic), dacă Android a validat internetul și, numai pe rețea mobilă, o cerere HEAD la `connectivitycheck.gstatic.com/generate_204` (aceeași adresă pe care o întreabă Android), reușită dacă răspunde 204, cu durata. Pătratul vine dintr-o poziție GPS mai nouă de 2 minute; fără ea rămâne gol. Nu cere permisiuni la rulare: semnalul vine fără READ_PHONE_STATE de la Android 9 (pe 8 nu se notează), iar starea rețelei și cererea cer doar `ACCESS_NETWORK_STATE` și `INTERNET`, acordate la instalare. Mesh-ul nu folosește internetul. Ultimele 2.160 de eșantioane (trei zile), în `network_log.json`.
- **Legătura cu consola** (`control/MetricsJoin.kt`). Operatorul adaugă pe consolă exporturile JSON ale raportorilor (Eu › Adaugă metricile raportorilor). La exportul consolei, `livrare.csv` pune pe un rând, după `incidentId`, ce spune raportorul (creat, timpii lui) și ce spune jurnalul consolei (când l-a deschis, cu câte hop-uri). Cele două ceasuri nu se scad niciodată unul din altul: livrarea e da sau nu, iar latența e doar dus-întors pe ceasul raportorului.

Fișierele exportului (`metrici-<dată>`): `-rapoarte.csv`, `-retea.csv`, `-mobil.csv` (dacă jurnalul are eșantioane), `-livrare.csv` (pe consolă) și `.json` (formatul `pingup-metrics`, versiunea 1, cu tot ce e mai sus). Coloanele sunt în `MetricsExport`; celulele sunt coduri, ca în `PROTOCOL.md`, iar orele sunt locale, ISO 8601.

## Reguli

1. **`:core` nu importă Android.** Compilatorul o verifică: modulul nu are Android pe classpath.
2. **Android intră prin interfețe.** `Radio`, `Crypto` și `Clock` sunt definite în `:core`; implementarea reală stă în `platform/`, cea de test în testele din `:core`.
3. **Ecranele nu se importă între ele.** Ce folosesc două ecrane merge în `ui/common/`; ce e pur vizual, în `ui/designsystem/`.
4. **Dependențele merg într-o singură direcție:** `ui` → `ui/common` → `ui/designsystem`, și totul → `:core`.

## Unde pun cod nou

- Un tip de pachet nou sau o regulă de rutare: `core/protocol/` sau `core/mesh/`, cu test în `core/src/test/`. Dacă se schimbă octeții de pe fir, se actualizează și `PROTOCOL.md` și vectorii golden.
- Un ecran nou: un pachet nou în `ui/`; mută în `ui/common/` doar ce ajunge să fie folosit și de alt ecran.
- Ceva ce cere un API Android: o interfață în `:core` și implementarea în `platform/`.

## De ce așa

- **Două module, nu zece.** Ghidul Android avertizează că modulele prea mărunte aduc doar „build complexity and boilerplate” [1]. La aproape 15.000 de linii, o împărțire pe funcționalități ca în Now in Android [3] ar fi exagerată; separarea logică / Android aduce aproape tot câștigul.
- **Logica separată de platformă,** ca la Briar: protocolul `bramble-core` e Java pur, iar `bramble-android` aduce doar adaptoarele [4]. Așa se testează rapid și se poate refolosi pe altă platformă.
- **Straturi UI și date** sunt „strongly recommended” în recomandările Android [2]; DI manual e acceptat pentru aplicații de mărimea asta [2].
- **Pachete pe funcționalități în `ui/`** și separarea `designsystem` / `common` urmează Now in Android (`core:designsystem`, `core:ui`, `feature:*`) [3].

1. https://developer.android.com/topic/modularization
2. https://developer.android.com/topic/architecture/recommendations
3. https://github.com/android/nowinandroid/blob/main/docs/ModularizationLearningJourney.md
4. https://code.briarproject.org/briar/briar (modulele `bramble-*`)

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
| `protocol/` | Formatul pachetelor pe fir: codare, decodare, fragmentare. |
| `mesh/` | Rutarea mesh: cui trimitem, ce am văzut deja, cozi, limite, consum. `Radio` e interfața pe care o implementează Bluetooth-ul. |
| `crypto/` | Identitatea, criptarea cap-coadă (libsodium), codurile QR de prieten și de staff. |
| `chat/` | Prietenii, grupurile, mesajele și reîncercările. |
| `incidents/` | Rapoartele, confirmările staff-ului și gruparea alertelor. |
| `data/` | Modelele salvate și `JsonStore` (fișiere JSON scrise atomic). |
| `venue/` | Harta evenimentului din `venue.json`: zone, punct de întâlnire. |
| `util/` | Octeți, hex, `sha256`, ceasul. |

Testele de aici (140) rulează pe JVM simplu, fără emulator. Telefoanele simulate din `SimNet` și `TestPhone` verifică scenarii întregi, de la raport până la confirmarea staff-ului.

## `:app`: Android și interfața

`app/src/main/java/ro/safetyplease/app/`

| Pachet | Ce face |
|---|---|
| `App`, `MainActivity`, `AppContainer` | Pornirea și legarea dependențelor (DI manual, un singur container). |
| `platform/` | Adaptoarele Android: `ble/` (radio BLE GATT), `location/`, `keystore/`. |
| `service/` | Serviciul din prim-plan care ține rețeaua pornită și notificările. |
| `text/` | Textele pentru codurile de protocol (categorii, stări), folosite de notificări și de ecrane. |
| `ui/` | `AppViewModel` și `MainScreen` (tab-urile și navigarea), apoi câte un pachet pe funcționalitate. |
| `ui/designsystem/` | Tema, culorile, tipografia, componentele de bază. Nu știe nimic despre mesaje sau incidente. |
| `ui/common/` | Componente folosite de mai multe ecrane: harta, cardul QR, antetul de incident, starea rețelei. |
| `ui/chat/`, `people/`, `report/`, `incidents/`, `map/`, `me/`, `onboarding/` | Ecranele, câte un pachet pe funcționalitate. |

`src/debug/` conține modul demo (simulare, comenzi adb); `src/release/` are aceeași clasă `Demo`, dar goală.

## Reguli

1. **`:core` nu importă Android.** Compilatorul o verifică: modulul nu are Android pe classpath.
2. **Android intră prin interfețe.** `Radio`, `Crypto` și `Clock` sunt definite în `:core`; implementarea reală stă în `platform/`, cea de test în testele din `:core`.
3. **Ecranele nu se importă între ele.** Ce folosesc două ecrane merge în `ui/common/`; ce e pur vizual, în `ui/designsystem/`.
4. **Dependențele merg într-o singură direcție:** `ui` → `ui/common` → `ui/designsystem`, și totul → `:core`.

## Unde pun cod nou

- Un tip de pachet nou sau o regulă de rutare: `core/protocol/` sau `core/mesh/`, cu test în `core/src/test/`.
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

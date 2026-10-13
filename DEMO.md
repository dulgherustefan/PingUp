# Demo

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
| `reset` | Scoate datele scenariului, prietenii demo și retrage anunțul de căldură |
| `prieteni` | Trei prieteni demo care își partajează poziția lângă tine (unul acum și aproape, unul de acum 8 min la 150 m, unul de acum 40 min), o partajare a ta cu primul și un punct de întâlnire propus de el, ca harta, listele și conversația să se vadă fără radio |
| `vitrina` | Capturile funcțiilor noi pe un singur emulator, sub exercițiul demo (vezi mai jos) |
| `vitrina-stop` | Scoate ce a pus `vitrina` și întoarce telefonul la evenimentul de dinainte |

**Vitrina.** `vitrina` trece întâi telefonul pe exercițiul demo (pachetul `demo-exercitiu`, cu cheile lui, semnat de organizatorul demo) și aplicația repornește; rulată a doua oară, acolo, semnează ca o consolă de exercițiu starea hărții (Poarta est închisă, Barurile aglomerate) și o evacuare a Scenei 2 marcată EXERCIȚIU, care doar vibrează, apoi face telefonul echipa „Medical 1” în Scena 2, cu prietenii demo proaspeți, grupul lor cu un „M-am pierdut” și un check-in ratat, nivelurile de aglomerație și un raport „nu răspunde, nu respiră” pentru alerta de răspuns; cu `--ez evacuare false` lasă evacuarea deoparte (sau o retrage), ca dialogurile prietenilor, care așteaptă cât ține ea, să se vadă. `vitrina-stop` retrage anunțurile, scoate prietenii, grupul, alertele și poziția simulată și repornește pe evenimentul de dinainte. Pentru consolă, după `vitrina`: `control`.
```
adb -s SERIAL shell am broadcast -n org.pingup.app/ro.safetyplease.app.demo.DebugCommandReceiver --es cmd vitrina
adb -s SERIAL shell am broadcast -n org.pingup.app/ro.safetyplease.app.demo.DebugCommandReceiver --es cmd vitrina-stop
```

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

## Harta offline
Harta reală (MapLibre Native, date OpenStreetMap din Protomaps) apare doar când există `app/src/main/assets/maps/<data>/`; fără ea, aplicația desenează harta evenimentului, ca pe iPhone. Pachetul se face cu `python tools/map_extract.py <AAAALLZZ>` (cere `pip install pmtiles`), iar fonturile și sprite-urile se adaugă după [tools/map_assets.md](tools/map_assets.md).

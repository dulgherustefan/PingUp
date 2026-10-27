# Ping Up în Google Play

Ce trebuie pentru prima publicare în Google Play, după regulile din octombrie 2026. APK-ul de pe site și de pe GitHub rămâne. Play e al doilea canal, cu aceeași cheie de semnare. Pentru iPhone: [APP_STORE.md](APP_STORE.md).

## Ce e gata în proiect

- Pachetul pentru Play: `./gradlew bundleRelease`, semnat cu cheia de release, care devine cheia de upload. Ambele limbi rămân în fiecare instalare.
- `targetSdk 36`, cât cere Play din 31 august 2026 pentru aplicații noi și actualizări; `minSdk 26` (Android 8).
- Bibliotecile native (libsodium, JNA, MapLibre, CameraX, androidx.graphics) sunt aliniate la pagini de 16 KB, cerința Play din 1 februarie 2027. Verificat pe APK-ul release din 27 octombrie 2026.
- Politica de confidențialitate: [ping-up.org/confidentialitate.html](https://ping-up.org/confidentialitate.html) și [PRIVACY.md](PRIVACY.md), aceeași ca în aplicație (`PrivacyTest` verifică).
- Fără reclame, conturi, SDK-uri de statistici și fără ID de publicitate.
- Blocare și „Raportează la staff” din profilul unui prieten sau al unui grup.

## De făcut înainte de prima trimitere

1. **Contul.** Unul personal, pe numele liderului (18 ani împliniți), costă 25 $ o dată. Cere buletinul și un card pe același nume, coduri pe e-mail și pe telefon și o verificare de pe un telefon real cu Android 10 sau mai nou. Google afișează public numele legal, țara și e-mailul de dezvoltator, deci folosiți o adresă a proiectului. Adresa completă apare doar dacă aplicația aduce bani. Un cont de organizație cere o entitate juridică și un număr D-U-N-S (gratuit, până la 30 de zile), dar nu are nevoie de testul închis.
2. **Fișele de prim ajutor: decizia mare.** La declarația de sănătate, „Emergency and First Aid” stă la Medical, iar aplicațiile medicale se publică doar dintr-un cont de organizație. Dintr-un cont personal, aplicația va fi probabil respinsă. Variantele:
   - un cont de organizație;
   - un build pentru Play fără fișe (cardul 112 rămâne);
   - întâi o întrebare la suportul Play, înainte de cei 25 $.

   Dacă fișele rămân, descrierea spune că aplicația nu e un dispozitiv medical și aplicația amintește că ajutorul vine de la un profesionist. Le aprobă un medic, ca pentru App Store.
3. **Regulile chatului.** Pentru mesajele dintre utilizatori, Play cere reguli acceptate înainte de primul mesaj, raportare din aplicație și blocare. Blocarea și raportarea există; regulile lipsesc. Propunerea de mai jos cere o schimbare în aplicație, după ce echipa aprobă textul.
4. **Testul închis.** Conturile personale create după 13 noiembrie 2023 au nevoie de 12 testeri înscriși fără pauză 14 zile. Urmează un chestionar în trei părți și o recenzie de ~7 zile. Strângeți ~20 de testeri, fiindcă unii ies, și puneți-i într-un Google Group.
5. **Aceeași cheie de semnare.** La crearea aplicației în Play, încărcați cheia de pe GitHub (`release.jks`). Altfel, cine are APK-ul de pe site nu poate actualiza din Play.
6. **Verificarea dezvoltatorilor Android.** Din 30 septembrie 2026 se aplică doar instalărilor din magazinele listate în Brazilia, Indonezia, Singapore și Thailanda. În 2027 se extinde la toate aplicațiile de pe telefoanele certificate, inclusiv la APK-urile de pe site; pentru România nu e încă o dată. Un APK neînregistrat se va instala doar cu un „flux avansat”, cu o zi de așteptare, sau cu ADB.
   - Contul Play acoperă ambele canale: o aplicație nouă din Play se înregistrează singură. Dacă Play semnează cu altă cheie, se înregistrează și cheia de pe GitHub.
   - Contul gratuit pentru studenți merge doar pe 20 de dispozitive, deci nu ajută la un festival.
7. **Locația precisă.** Din 27 ianuarie 2027, orice aplicație cu `ACCESS_FINE_LOCATION` completează o declarație nouă în Play Console. Când `targetSdk` trece la 37, poziția atașată o singură dată la un raport trebuie să vină din noul buton de locație al Google.
8. **Alerta pe tot ecranul.** Play dă `USE_FULL_SCREEN_INTENT` implicit doar aplicațiilor de alarmă și de apeluri. Celorlalte le-o scoate la instalare, iar evacuarea și alerta consolei rămân notificări urgente. Când permisiunea lipsește, aplicația arată în Eu rândul „Alertele pe tot ecranul”, care deschide setarea. Telefoanele de staff o pornesc la instruire.
9. **Codul de staff pentru recenzent.** Play vrea un cod care nu expiră, ca imagine la o adresă fixă, cu instrucțiuni în engleză. Codurile pe tură (SPS2) expiră, deci merge doar un cod SPS1, cu cheile comune ale build-ului: `python3 tools/gen_staff_keys.py legacy --teams Review`.
   - Riscul: cine are codul deschide rapoartele sigilate pentru cheile comune, adică cele trimise de pe telefoane fără pachetul evenimentului.
   - Puneți-l la o adresă nelistată și schimbați cheile comune (`legacy --new`, apoi un build nou) înainte de primul eveniment real.
   - Altă variantă: scoateți SPS1 din release după ce tot staff-ul folosește SPS2 ([PROTOCOL.md](PROTOCOL.md), §14.7).

## Declarațiile din Play Console

| Declarație | Răspunsul pentru Ping Up |
|---|---|
| Serviciu în prim-plan, `connectedDevice` | rețeaua Bluetooth cu telefoanele din jur, ca transfer continuu de date către dispozitive externe; descriere, impact și un video scurt |
| Serviciu în prim-plan, `location` | partajarea poziției pornită de utilizator; descriere, impact și un video scurt |
| Alerta pe tot ecranul | nu (vezi punctul 8) |
| Scutirea de optimizarea bateriei | aplicație de siguranță, fără internet, deci fără notificări prin FCM |
| Locația | doar în prim-plan, fără locație în fundal; formularul nou din 27 ianuarie 2027 |
| Sănătate | vezi punctul 2 |
| Data safety | „Data Not Collected”: chatul, pozițiile împărțite și rapoartele sunt criptate cap-coadă, iar raportul îl deschide doar staff-ul. HELLO-ul din release nu are poreclă, iar metricile pleacă doar printr-un export făcut de om. Dacă recenzentul nu acceptă, se declară pentru „App functionality”, nepartajate și criptate: Precise location, Name (porecla din raport) și Other user-generated content |
| Clasificarea vârstei (IARC) | utilizatorii comunică între ei și pot împărți locația; nu sunt cumpărături |
| Publicul țintă | 13 ani sau mai mult, niciodată sub 13: regulile Families cer Bluetooth prin Companion Device Manager, pe care o rețea mesh nu-l poate folosi |
| Categoria | Communication, nu Social (Social, Dating și chatul anonim au reguli mai stricte din 26 august 2026) |
| Reclame, ID de publicitate, funcții financiare, aplicație guvernamentală, știri | nu |
| Acces pentru recenzent | punctul 9 |

## Regulile Ping Up (propunere pentru punctul 3)

În aplicație: un rând pe ultima pagină a onboarding-ului, „Continuând, accepți regulile Ping Up”, cu un link spre pagina de mai jos (și pe site).

```text
Regulile Ping Up
- Scrii doar oamenilor adăugați cu codul QR și grupurilor tale.
- Nu trimite amenințări, hărțuire, ură, instigare la violență sau conținut sexual cu minori.
- Poți bloca pe oricine din profilul lui; „Raportează la staff” trimite hărțuirea la echipa festivalului.
- Un raport fals de incident poate lua ajutorul altcuiva: trimite doar ce se întâmplă.
- Ping Up nu e un serviciu de urgență: la pericol, sună la 112.
Mesajele nu ajung la noi. Altceva de semnalat: stdulgh@gmail.com.
```

## Textele din magazin

| | Română | English |
|---|---|---|
| Titlu (30) | Ping Up: chat fără internet | Ping Up: Offline Mesh Chat |
| Scurtă (80) | Mesaje și rapoarte la festival prin Bluetooth, când rețeaua mobilă cade. | Chat and safety reports at festivals over Bluetooth, when the network fails. |

Descrierea lungă:

```text
Ping Up ține mesajele și rapoartele de siguranță în mișcare când rețeaua mobilă cade, la festivaluri și în mulțimi. Telefoanele cu aplicația își duc mesajele unul altuia, prin Bluetooth, fără internet, fără server și fără cont: telefonul tău duce mai departe și pachetele celor din jur, criptate, fără să le poată citi.

Cu prietenii
• Chat criptat cap-coadă cu prietenii adăugați prin cod QR și grupuri de până la 8 persoane.
• Poziția ta, doar pentru prietenii aleși și cât timp vrei; e oprită până o pornești.
• „M-am pierdut de grup”, punct de întâlnire și „Vin la tine”.
• Check-in pe drum: dacă nu confirmi că ai ajuns, prietenii aleși primesc o alertă.
• Harta offline a festivalului și drumul pe jos până la orice loc.

Când ai nevoie de ajutor
• Raport către staff-ul festivalului în două atingeri: medical, violență, copil sau prieten pierdut, hărțuire, aglomerație, incendiu.
• Vezi când raportul a ajuns la staff, când a fost preluat și când e rezolvat.
• Cardul 112, cu locația ta.
• Anunțurile și evacuările semnate de organizator ajung la cei din zonă.

Pentru organizatori
Staff-ul primește rapoartele pe telefoanele lui, iar camera de control vede incidentele și starea zonelor. Codurile de staff vin de la organizator.

Confidențialitate
Fără reclame, conturi sau urmărire. Mesajele și poziția sunt criptate cap-coadă; la noi nu ajunge nimic. Codul e open source (GPL-3.0-or-later): github.com/dulgherustefan/PingUp

Important
Ping Up nu e un serviciu de urgență și nu înlocuiește 112. Merge doar unde sunt și alte telefoane cu aplicația, în raza Bluetooth.
```

```text
Ping Up keeps messages and safety reports moving when the mobile network fails at festivals and in crowds. Phones with the app carry each other's messages over Bluetooth, with no internet, no server and no account: your phone also passes on the packets of people around you, encrypted, without being able to read them.

With your friends
• End-to-end encrypted chat with friends added by QR code, and groups of up to 8.
• Your position, only for the friends you pick and for as long as you want; it's off until you turn it on.
• "I lost my group", meeting points and "I'm coming to you".
• Check-in on the way: if you don't confirm you arrived, the friends you picked get an alert.
• The festival's offline map and walking directions to any place.

When you need help
• A report to the festival staff in two taps: medical, violence, lost child or friend, harassment, crowd, fire.
• See when your report reached the staff, when it was taken and when it is resolved.
• The 112 card, with your location.
• Notices and evacuations signed by the organiser reach everyone in the zone.

For organisers
Staff get the reports on their phones, and the control room sees the incidents and the state of each zone. Staff codes come from the organiser.

Privacy
No ads, no accounts, no tracking. Messages and positions are end-to-end encrypted; nothing reaches us. The code is open source (GPL-3.0-or-later): github.com/dulgherustefan/PingUp

Important
Ping Up is not an emergency service and doesn't replace 112. It works only where other phones with the app are within Bluetooth range.
```

„Ce e nou” vine din secțiunea versiunii din [CHANGELOG.md](CHANGELOG.md), scurtată la 500 de caractere.

## Imaginile

- Iconița de 512 × 512 și imaginea mare de 1024 × 500, obligatorie pentru recomandări.
- Între 2 și 8 capturi pe telefon. În ordine:
  1. chatul fără semnal;
  2. raportul în două atingeri;
  3. starea raportului;
  4. harta cu prietenii;
  5. check-in-ul;
  6. evacuarea;
  7. consola.

  Fiecare are un titlu scurt. Video-ul nu pornește singur în Play, deci e opțional.

## Surse

Pagini citite pe 27 octombrie 2026: [contul](https://support.google.com/googleplay/android-developer/answer/6112435), [testul închis](https://support.google.com/googleplay/android-developer/answer/14151465), [API-ul țintă](https://support.google.com/googleplay/android-developer/answer/11926878), [verificarea dezvoltatorilor](https://developer.android.com/developer-verification), [serviciile în prim-plan și alerta pe tot ecranul](https://support.google.com/googleplay/android-developer/answer/13392821), [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469), [declarația de sănătate](https://support.google.com/googleplay/android-developer/answer/10788890), [aplicațiile medicale din cont de organizație](https://support.google.com/googleplay/android-developer/answer/14738291), [textul pentru aplicațiile de sănătate](https://support.google.com/googleplay/android-developer/answer/16679511?hl=en), [conținutul de la utilizatori](https://support.google.com/googleplay/android-developer/answer/9876937), [accesul recenzentului](https://support.google.com/googleplay/android-developer/answer/15748846?hl=en), [locația precisă](https://support.google.com/googleplay/android-developer/answer/17033915?hl=en).

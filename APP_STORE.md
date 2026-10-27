# Ping Up în App Store

Ce trebuie pentru prima trimitere a aplicației iPhone, după regulile Apple din octombrie 2026. Partea de Android nu e aici: aceeași aplicație se publică pe site (README, „Release”).

## Ce e gata în proiect

- Manifestul de confidențialitate (`iosApp/PingUp/PrivacyInfo.xcprivacy`): fără tracking, fără date colectate, motivele pentru API-urile pe care Apple le cere declarate.
- Textele pentru permisiuni, în română și engleză: Bluetooth (și cu telefonul în buzunar), camera (doar pentru coduri QR), locația (în rapoarte și la prietenii aleși).
- Modurile de fundal sunt doar cele două de Bluetooth, folosite exact pentru rețea (regula 2.5.4).
- Notificările urgente (Time Sensitive) sunt doar în build-ul Release și doar pentru ce nu poate aștepta: incidentele urgente, întrebarea și alerta de check-in și anunțurile care sună, ca evacuarea; nu cer formular la Apple.
- Ecranul de lansare, iconița, iOS 16 sau mai nou, build-ul Release pentru telefoane verificat nesemnat cu Xcode 27 (Apple cere Xcode 26 sau mai nou la upload).
- În aplicație: blocare din profil și „Raportează la staff” pentru hărțuirea venită prin aplicație (regula 1.2); „Nu înlocuiește 112” și butonul „Sună la 112” lângă raport (regula 5.1.5: aplicația nu e un serviciu de urgență); locația pleacă doar cu un raport sau, criptată cap-coadă, la prietenii aleși: în Eu › Partajarea locației, într-o alertă de check-in ratat și, timp de 15 minute, la grup după „M-am pierdut de grup”; fără permisiune se alege zona de pe hartă (5.1.1).
- Licența: codul e GPL-3.0-or-later. Condițiile App Store contrazic GPL-ul doar pentru cine distribuie codul GPL al altcuiva; noi publicăm codul nostru, ca autori, deci nu e conflict (așa publică și Signal, Mullvad sau Proton). De aceea, cod din afară intră doar cu acordul din `CONTRIBUTING.md`. La „License Agreement” rămâne EULA standard a Apple.

## De făcut înainte de trimitere

1. **Contul Apple Developer.** O persoană fizică trebuie să aibă 18 ani; altfel îl face un părinte sau școala (ca organizație, cu număr D-U-N-S). Școlile acreditate și ONG-urile pot cere scutire de taxă.
2. **Textele cu „(sursa: organizatorul)”** din `app/src/main/assets/pages` și `pages-en`: Apple respinge conținutul provizoriu (2.1). Se completează cu organizatorul.
3. **Fișele de prim ajutor** (`firstaid`, `firstaid-en`) apar ca ciornă, „neverificate de un medic”: le aprobă un medic înainte de trimitere (1.4.1), altfel e risc de respingere.
4. **Adresa de contact** (regula 1.2 cere date de contact) e acum în `PRIVACY.md` și în aplicație; se trece și în App Store Connect.
5. **Un cod de staff pentru recenzie**, făcut cu cheile evenimentului (`python tools/gen_staff_keys.py`): build-ul Release nu acceptă cheile demo. Se pune ca imagine în „Attachment” la notele pentru recenzie, împreună cu o scrisoare de la organizator.
6. **Un video scurt** cu două iPhone-uri, unul blocat în buzunar: prieteni, mesaj, raport, alerta la staff. Apple nu-l cere, dar o rețea Bluetooth fără internet e greu de încercat de recenzent.
7. **Criptarea la export**, decizia cu Franța (mai jos).
8. **Statutul de comerciant (DSA)** pentru magazinele din UE: o echipă de elevi care nu vinde nimic e cel mai probabil „non-trader”; un comerciant își vede adresa și telefonul afișate public.

## Criptarea la export

Aplicația folosește algoritmi standard (libsodium: X25519, XChaCha20-Poly1305, Ed25519) pentru mesajele criptate cap-coadă, pe lângă criptarea sistemului. În App Store Connect se răspunde **„standard algorithms”**. Pentru SUA intră la „mass market” (EAR 740.17(b)(1)) și nu se încarcă documente.

**Franța** cere o declarație la ANSSI pentru orice aplicație cu criptare oferită acolo (decretul 2007-663). Nu e o autorizare, iar excepția pentru Bluetooth acoperă doar criptarea legăturii radio. Se depune online (demarche.numerique.gouv.fr), cu cel puțin o lună înainte. Atestatul primit se încarcă în App Store Connect, iar Apple dă un cod pentru `ITSEncryptionExportComplianceCode`.

- **Fără Franța:** Franța se scoate din disponibilitate. După primul upload, dacă App Store Connect spune că nu trebuie documente, se pune `ITSAppUsesNonExemptEncryption = NO` în `iosApp/project.yml`, ca TestFlight să nu mai întrebe la fiecare build. Obligația franceză rămâne pentru APK-ul de pe site, dacă e descărcat în Franța.
- **Cu Franța:** se depune declarația acum. Când vine atestatul, se pune `ITSAppUsesNonExemptEncryption = YES` și codul.

Cheia lipsește intenționat din `Info.plist` până la decizie.

## În App Store Connect

**Confidențialitate („App Privacy”).** Apple consideră o dată „colectată” doar dacă pleacă de pe telefon și dezvoltatorul sau partenerii lui (furnizori al căror cod e în aplicație) o pot citi mai mult decât durează cererea. La Ping Up nu ajunge nimic la noi: mesajele sunt criptate cap-coadă, iar rapoartele merg prin Bluetooth la staff-ul organizatorului. Răspunsul e **„Data Not Collected”**. Dacă organizatorul e cel care publică aplicația sau o rulăm în numele lui, se declară Precise Location, Other User Content și Name (rapoartele), pentru App Functionality, fără tracking. Eticheta se poate schimba oricând, fără build nou.

**Vârsta.** Răspunsurile pe care le dăm:

| Întrebare | Răspuns |
|---|---|
| Messaging and Chat | Yes |
| User-Generated Content | No (chatul e doar între prieteni adăugați cu codul QR și în grupurile pe care le fac din prietenii lor) |
| Medical or Treatment Information | Infrequent (fișele de prim ajutor) |
| Alcohol, Tobacco, or Drug Use or References | Infrequent (pagina despre reducerea riscurilor) |
| Mature/Suggestive Themes | None |
| Violence, Weapons | None |

Rezultatul probabil e **13+**. Statele americane cu verificarea vârstei (Texas, Utah, Louisiana) cer și API-ul de vârstă, așa că prima lansare e mai simplă doar în UE.

**Recenzie grăbită.** Se poate cere când lansarea e legată de un eveniment.

## Notele pentru recenzie

Se înlocuiesc parantezele drepte.

```
Ping Up is a free, fully offline safety and messaging app for [Festival], made with its organiser (letter attached).
There are no accounts and no servers: phones form a Core Bluetooth LE mesh (central and peripheral) and relay each
other's end-to-end encrypted packets.

The background modes bluetooth-central and bluetooth-peripheral keep this relay running while the phone is locked in
a pocket, which is the app's core function. No other background mode is used.

Testing needs two iPhones near each other with Bluetooth on (Wi-Fi and cellular may be off). A video of every step,
with one phone locked: [URL]
1. Phone A: Messages > Add friend > My code. Phone B: Messages > Add friend > Scan. One scan makes them friends; chat.
2. Phone B: tap the profile button (top left) > I have a staff code, and scan the attached staff code. This unlocks the
   staff screens and the control console.
3. Phone A: Report > Medical > Send report. Phone B shows a Time Sensitive alert, used only for urgent incidents,
   check-in alerts and evacuation notices.

Chat exists only between people who scanned each other's code in person, and in groups one of them makes from their
friends: there is no public, random or anonymous chat.
From a friend's or a group's profile, users can block them or report harassment to the event staff on site.
Contact: stdulgh@gmail.com

Location goes only with a report the user sends, as the report screen says, or, end-to-end encrypted, to friends the
user picks: under Me > Location sharing while the app is open, in a missed check-in alert, and to their group for 15
minutes after "I lost my group"; without permission the user picks a zone on the map. Ping Up is not an emergency
service: onboarding and the report screen say it doesn't replace 112 and offer a Call 112 button. The first-aid sheets
were approved by [name, role].
```

## Surse

- Apple: [export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance), [documentele](https://developer.apple.com/help/app-store-connect/reference/export-compliance-documentation-for-encryption), [`ITSAppUsesNonExemptEncryption`](https://developer.apple.com/documentation/bundleresources/information-property-list/itsappusesnonexemptencryption), [regulile de recenzie](https://developer.apple.com/app-store/review/guidelines/), [App Privacy](https://developer.apple.com/app-store/app-privacy-details/), [vârsta](https://developer.apple.com/help/app-store-connect/reference/app-information/age-ratings-values-and-definitions), [DSA](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements), [cerințele noi](https://developer.apple.com/news/upcoming-requirements/), [înscrierea](https://developer.apple.com/programs/enroll/).
- SUA: [EAR 740.17](https://www.ecfr.gov/current/title-15/subtitle-B/chapter-VII/subchapter-C/part-740/section-740.17).
- Franța: [ANSSI, controlul mijloacelor de criptare](https://cyber.gouv.fr/reglementation/reglementation-identite-confiance-numerique/controles-reglementaires-cryptographie/controle-moyen-de-cryptologie/), [declarația online](https://demarche.numerique.gouv.fr/commencer/declaration-relative-a-un-moyen-de-cryptologie), [decretul 2007-663](https://www.arcep.fr/fileadmin/reprise/textes/decrets/d2007-663.pdf).

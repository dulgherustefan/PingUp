# Numele, logo-ul și mascota

[English below](#the-name-the-logo-and-the-mascot)

Numele „Ping Up”, logo-ul (pinul cu raze de lumină) și mascota (pinul care zâmbește) sunt ale autorilor: Dulgheru Ștefan Alexandru, Cîrciumaru Radu, Ilie-Goga Radu și Farkaș Andrei-Oliver. Licența codului ([GPL-3.0-or-later](LICENSE)) și cea a documentației ([CC BY 4.0](LICENSES/CC-BY-4.0.txt)) nu dau niciun drept asupra lor. Fișierele logo-ului și ale mascotei au licența lor: [LicenseRef-PingUp-Brand](LICENSES/LicenseRef-PingUp-Brand.txt).

Ping Up e o aplicație de siguranță: cine o instalează trebuie să știe că e cea făcută și verificată de noi. O versiune modificată cu același nume ar putea induce oamenii în eroare tocmai când contează.

## Ce poți face fără să ne întrebi

- Să spui adevărul despre Ping Up: „bazat pe Ping Up”, „compatibil cu Ping Up”, „folosește protocolul Ping Up”.
- Să distribui Ping Up nemodificat, cu numele, logo-ul și mascota lui: codul sursă sau aplicația, inclusiv compilată de altcineva din codul nostru nemodificat (de exemplu, de F-Droid).
- Să scrii, să vorbești sau să predai despre Ping Up, cu numele, logo-ul și capturi din aplicație.

## Pentru ce ai nevoie de acordul nostru scris

- Să numești „Ping Up”, sau cu un nume care se poate confunda cu el, o versiune modificată, o altă aplicație, un serviciu, un dispozitiv sau o firmă.
- Să folosești logo-ul sau mascota, ori desene care seamănă cu ele, în acestea.
- Să lași să se înțeleagă că o versiune, un produs sau un eveniment e susținut de noi ori făcut împreună cu noi.

## Dacă publici o versiune modificată

GPL-ul îți dă voie să modifici codul și să publici versiunea ta. Înainte să o publici:

1. Dă-i alt nume: `appName` în `gradle.properties`, `CFBundleDisplayName` în `iosApp/project.yml` și textele care spun „Ping Up”.
2. Dă-i alt identificator: `applicationId` în `app/build.gradle.kts`, `bundleIdPrefix` în `iosApp/project.yml` și `PRODUCT_BUNDLE_IDENTIFIER` în `iosApp/Signing.xcconfig`.
3. Înlocuiește logo-ul și mascota: `ic_launcher_background.xml`, `ic_launcher_foreground.xml` și `ic_launcher_monochrome.xml` din `app/src/main/res/drawable/`, `icon-1024.png` din `iosApp/PingUp/Assets.xcassets/AppIcon.appiconset/` și `ui/src/commonMain/kotlin/ro/safetyplease/ui/designsystem/Mascot.kt`.

Apoi poți scrie că versiunea ta e „bazată pe Ping Up”.

Pentru întrebări sau cereri, scrie-ne: contactul e în [PRIVACY.md](PRIVACY.md).

---

# The name, the logo and the mascot

The name "Ping Up", the logo (the pin with rays of light) and the mascot (the smiling pin) belong to the authors: Dulgheru Ștefan Alexandru, Cîrciumaru Radu, Ilie-Goga Radu and Farkaș Andrei-Oliver. The license of the code ([GPL-3.0-or-later](LICENSE)) and that of the documentation ([CC BY 4.0](LICENSES/CC-BY-4.0.txt)) grant no rights to them. The logo and mascot files have their own license: [LicenseRef-PingUp-Brand](LICENSES/LicenseRef-PingUp-Brand.txt).

Ping Up is a safety app: whoever installs it must know it is the one we made and checked. A modified version under the same name could mislead people exactly when it matters.

## What you may do without asking

- Tell the truth about Ping Up: "based on Ping Up", "compatible with Ping Up", "uses the Ping Up protocol".
- Distribute unmodified Ping Up, with its name, logo and mascot: the source code or the app, including the app built by someone else from our unmodified source code (for example, by F-Droid).
- Write, speak or teach about Ping Up, with its name, its logo and screenshots of the app.

## What needs our written permission

- Calling a modified version, another app, a service, a device or a company "Ping Up", or a name that can be confused with it.
- Using the logo or the mascot, or drawings that look like them, in any of these.
- Suggesting that a version, a product or an event is endorsed by us or made together with us.

## If you publish a modified version

The GPL lets you modify the code and publish your version. Before you publish it:

1. Give it another name: `appName` in `gradle.properties`, `CFBundleDisplayName` in `iosApp/project.yml`, and the texts that say "Ping Up".
2. Give it another identifier: `applicationId` in `app/build.gradle.kts`, `bundleIdPrefix` in `iosApp/project.yml` and `PRODUCT_BUNDLE_IDENTIFIER` in `iosApp/Signing.xcconfig`.
3. Replace the logo and the mascot: `ic_launcher_background.xml`, `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml` in `app/src/main/res/drawable/`, `icon-1024.png` in `iosApp/PingUp/Assets.xcassets/AppIcon.appiconset/`, and `ui/src/commonMain/kotlin/ro/safetyplease/ui/designsystem/Mascot.kt`.

You may then say that your version is "based on Ping Up".

For questions or requests, write to us: the contact is in [PRIVACY.md](PRIVACY.md).

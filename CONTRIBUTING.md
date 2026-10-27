# Cum contribui

[English below](#contributing)

Mulțumim că vrei să ajuți la Ping Up. Pe scurt:

1. Pentru ceva mai mare decât o greșeală de scriere, deschide întâi un issue, ca să vorbim despre idee înainte de cod.
2. Lucrează pe o ramură a ta și trimite un pull request.
3. Înainte, rulează ce rulează și CI-ul: `./gradlew assembleDebug test lint`, testele uneltelor (`python3 tools/test_*.py`, cu `pip install pynacl`), `reuse lint` și, pe un Mac, `./gradlew :core:iosSimulatorArm64Test :ui:iosSimulatorArm64Test` (vezi „Cum rulezi proiectul” în README).
4. Mesajele commiturilor sunt scurte, în engleză, cu literă mică, și spun ce face schimbarea.
5. Nu pune în git chei, coduri de staff sau date personale.
6. Dacă adaugi sau schimbi o dependență, rulează `./gradlew exportLicenses`: rescrie listele din ecranul „Licențe open source” (CI-ul verifică). Ce nu vede Gradle (fonturi, iconițe, date, cod nativ) se adaugă în `config/aboutlibraries`.

Codul e sub [GPL-3.0-or-later](LICENSE), documentația sub [CC BY 4.0](LICENSES/CC-BY-4.0.txt) (vezi „Licență” în README). Numele, logo-ul și mascota: [TRADEMARKS.md](TRADEMARKS.md).

## Acordul de contribuție (CLA), versiunea 1 din 13 octombrie 2026

Ca să putem primi o contribuție și să publicăm Ping Up și în App Store sau sub alte licențe, cine o trimite acceptă acordul de mai jos. Îl accepți la fiecare pull request, bifând căsuța din descriere. Dacă ai sub 18 ani, ai nevoie și de acordul unui părinte, pe care îl scrie într-un comentariu la pull request. Autorii Ping Up nu semnează acest acord: între ei există unul separat.

1. **Părțile.** „Tu” ești cel care trimite contribuția. „Noi” suntem autorii Ping Up, împreună: Dulgheru Ștefan Alexandru, Cîrciumaru Radu, Ilie-Goga Radu și Farkaș Andrei-Oliver. „Contribuția” e ce ne trimiți pentru Ping Up (cod, texte, imagini, traduceri sau altceva), prin pull request sau altfel.
2. **Dreptul de autor rămâne al tău.** Acordul nu e o cesiune: poți folosi contribuția oricum și oricând. Drepturile tale morale, de exemplu să fii recunoscut ca autor, rămân neatinse.
3. **Licența pe care ne-o dai.** Pentru fiecare contribuție ne dai o licență neexclusivă, gratuită, valabilă în toată lumea, pe toată durata dreptului de autor și irevocabilă: să o reproducem, să o modificăm și să o adaptăm, să o traducem, să o distribuim, să o comunicăm public și să o punem la dispoziția publicului, în orice formă și pe orice suport, singură, în Ping Up sau în alte opere, și să dăm mai departe aceste drepturi, sub orice licență, inclusiv licențe care nu sunt open source (de exemplu, în App Store sau printr-o licență comercială). Nu primești nicio remunerație pentru asta. Putem transmite licența unei persoane juridice pe care o înființăm pentru Ping Up.
4. **Brevete.** Dacă ai un brevet pe care l-ar încălca contribuția ta, singură sau în Ping Up, ne dai în aceleași condiții licența să o facem, să o folosim, să o vindem și să o distribuim. Dacă dăm pe cineva în judecată susținând că contribuția ta sau Ping Up încalcă un brevet, licența de brevet pe care ne-ai dat-o se încheie din ziua aceea.
5. **Ce declari.** Contribuția e creația ta sau ai dreptul să ne-o dai în aceste condiții. Dacă angajatorul sau școala ta are drepturi asupra ei, ai acordul lor. Ce ai luat din altă parte (cod, texte, imagini) marchezi în pull request, cu sursa și licența lui.
6. **Ce promitem noi.** Versiunea publică a Ping Up în care intră contribuția ta rămâne disponibilă și sub GPL-3.0-or-later sau sub o altă licență aprobată de Open Source Initiative. Numele tău rămâne în istoria proiectului.
7. **Fără obligații.** Nu suntem obligați să folosim contribuția. O oferi „așa cum este”, fără garanții și fără să trebuiască să o întreții.
8. **Legea.** Se aplică legea română. Dacă textul în română și cel în engleză diferă, prevalează cel în română.

---

# Contributing

Thank you for wanting to help with Ping Up. In short:

1. For anything bigger than a typo, open an issue first, so we can talk about the idea before the code.
2. Work on a branch of your own and send a pull request.
3. Before that, run what CI runs: `./gradlew assembleDebug test lint`, the tools' tests (`python3 tools/test_*.py`, with `pip install pynacl`), `reuse lint` and, on a Mac, `./gradlew :core:iosSimulatorArm64Test :ui:iosSimulatorArm64Test` (see "Cum rulezi proiectul" in the README).
4. Commit messages are short, in English, lowercase, and say what the change does.
5. Don't put keys, staff codes or personal data in git.
6. If you add or change a dependency, run `./gradlew exportLicenses`: it rewrites the lists of the "Open source licences" screen (CI checks it). What Gradle can't see (fonts, icons, data, native code) goes in `config/aboutlibraries`.

The code is under [GPL-3.0-or-later](LICENSE), the documentation under [CC BY 4.0](LICENSES/CC-BY-4.0.txt) (see "Licență" in the README). The name, the logo and the mascot: [TRADEMARKS.md](TRADEMARKS.md).

## Contributor License Agreement (CLA), version 1 of 13 October 2026

So that we can accept a contribution and keep publishing Ping Up in the App Store and under other licenses too, whoever sends one accepts the agreement below. You accept it in every pull request by ticking the box in its description. If you are under 18, you also need the consent of a parent, who writes it in a comment on the pull request. The Ping Up authors don't sign this agreement: there is a separate one between them.

1. **The parties.** "You" are the person who sends the contribution. "We" are the Ping Up authors, together: Dulgheru Ștefan Alexandru, Cîrciumaru Radu, Ilie-Goga Radu and Farkaș Andrei-Oliver. The "contribution" is what you send us for Ping Up (code, texts, images, translations or anything else), by pull request or otherwise.
2. **The copyright stays yours.** This agreement is not an assignment: you may use your contribution in any way, at any time. Your moral rights, such as being recognised as the author, remain untouched.
3. **The license you give us.** For each contribution you give us a non-exclusive, free of charge, worldwide license, for the whole term of copyright and irrevocable: to reproduce it, to modify and adapt it, to translate it, to distribute it, to communicate it to the public and to make it available to the public, in any form and on any medium, on its own, in Ping Up or in other works, and to pass these rights on, under any license, including licenses that are not open source (for example, in the App Store or through a commercial license). You receive no remuneration for this. We may transfer the license to a legal entity we set up for Ping Up.
4. **Patents.** If you hold a patent that your contribution, on its own or in Ping Up, would infringe, you give us, on the same terms, the license to make, use, sell and distribute it. If we sue anyone claiming that your contribution or Ping Up infringes a patent, the patent license you gave us ends on that day.
5. **What you declare.** The contribution is your own creation, or you have the right to give it to us on these terms. If your employer or your school has rights to it, you have their consent. Anything you took from elsewhere (code, texts, images) you point out in the pull request, with its source and its license.
6. **What we promise.** The public version of Ping Up that includes your contribution stays available under GPL-3.0-or-later or under another license approved by the Open Source Initiative. Your name stays in the project's history.
7. **No obligations.** We don't have to use the contribution. You offer it "as is", without warranty and without having to maintain it.
8. **The law.** Romanian law applies. If the Romanian and the English texts differ, the Romanian text prevails.

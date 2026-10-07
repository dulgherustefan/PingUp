# PingUp
O aplicație Android pentru raportarea incidentelor și mesajelor, care merge fără internet, fără date mobile, fără Wi-Fi, fără server.
Ideea de la care am pornit:  In timpul unui festival sau a unui eveniment aglomerat, trimiterea mesajelor este aproape imposibila din cauza lipsei de semnal.

## Cum funcționează, pe scurt
Telefoanele se conectează între ele prin Bluetooth Low Energy și formează o rețea (mesh). Un mesaj sare din telefon în telefon până ajunge la destinatar. Dacă A vrea să trimită ceva către D, dar nu e în raza lui, mesajul trece prin B și C.  

A  →  B  →  C  →  D

## Ce poate face
- Raportare de incidente: în doar 2 tap-uri (alegi categoria și trimiți). Poți adăuga și gravitatea, zona și o descriere, dar sunt opționale.
- Staff-ul primește alerta, apasă „Preiau" sau „Rezolvat", iar cel care a raportat vede în ce stadiu e: se transmite → ajuns la staff → preluat → rezolvat.
- Chat criptat cap-coadă cu prietenii, adăugați prin cod QR (se scanează amândoi). Merg și grupuri de maxim 8 persoane.
- „Trimite zona mea" apeși pe mesaj și se deschide harta cu un pin.
- Hartă offline, desenată local, pe baza unui fișier cu zonele evenimentului (`venue.json`).
- Store-and-forward: dacă un telefon e offline un timp, primește raportul când revine în rețea.

## Tehnologii
Kotlin, Jetpack Compose, Bluetooth LE (GATT), libsodium (prin lazysodium), Gradle, Python (script pentru chei de staff).

## Structura
Două module: `core/` are toată logica (protocolul, rețeaua mesh, criptarea, chatul, incidentele) în Kotlin pur, fără Android; `app/` are Bluetooth-ul, notificările și ecranele. Detalii și reguli în [ARCHITECTURE.md](ARCHITECTURE.md).

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

## Instalare

Se poate instala si de pe [ping-up.org](https://ping-up.org)


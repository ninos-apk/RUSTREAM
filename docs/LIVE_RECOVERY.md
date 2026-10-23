# Live-Puffer und automatische Wiederherstellung

Stand: 23. Oktober 2026, Android TV.

## Profile

| Profil | Vorleseziel | Startreserve | Reserve nach Unterlauf | Byteziel |
| --- | --- | --- | --- | --- |
| Sport | 5 s | 1 s | 2 s | 16 MiB |
| Balance (Standard) | 10 s | 1,5 s | 3 s | 32 MiB |
| Stabil | 20 s | 2 s | 5 s | 64 MiB |

Die benutzerdefinierte Auswahl entfällt. Gespeicherte `custom_10` werden auf
Balance, `custom_15` und `custom_20` auf Stabil übertragen und als regulärer
Profilschlüssel gespeichert. Einstellungen gelten ab dem nächsten Load.

Zeit und Bytes sind Ladeziele, keine harten Prozess-Speichergrenzen. Bei Live
kann das Byteziel die Zeitreserve begrenzen, besonders bei hohen Bitraten.
Segmente/Ladeblöcke können das Zeitziel überschreiten. Die Dauer ist keine
garantierte Verzögerung zum realen Ereignis.

## Fehlerbehandlung

Media3 darf vorübergehende Ladefehler höchstens zweimal lokal wiederholen
(sofort, normalerweise nach 1 s). Nicht wiederholbare Parser-/Dateifehler bleiben
ausgenommen. Währenddessen kann vorhandener Puffer weiterspielen. Erst nach
ausgeschöpften Versuchen übernimmt Rust den vollständigen Neuaufbau mit Backoff.

Live-404/410 sind wiederholbar. VOD-404/410, 401/403 und nicht unterstützte Formate
bleiben endgültige Fehler. Bei fehlgeschlagener Decoderinitialisierung können
weitere geeignete Decoder versucht werden; sind alle ausgeschöpft, bleibt der
Fehler endgültig.

## Hänger erkennen

- Bereits gestartetes Live-TV: nach 12 s ohne nutzbaren Nachladefortschritt oder
  ohne Daten-/Wiedergabefortschritt eine Wiederverbindung anfordern.
- Beim ersten Start bekommt ein stillstehender Puffer bis zu 25 s Zeit.
- Live-Puffern endet spätestens nach 30 s mit einer Wiederverbindung, auch wenn
  weiterhin kleine Fortschritte gemeldet werden.
- Bei bekanntem Pufferwert zählen Bytes allein nicht als erfolgreicher Refill;
  die abspielbare Pufferdauer muss wachsen.
- Frame-Callbacks belegen Wiedergabefortschritt auch bei gleichbleibender
  relativer Position in einem wandernden Live-Zeitfenster.
- Sichtbares, aktiv spielendes Live-Video ohne neue Frame-Callbacks wird nach
  12 s neu aufgebaut. Pause, unterdrückte Wiedergabe, Buffering, unsichtbare
  Ausgabe und reine Audiokanäle setzen diesen Timer zurück.

Frame-Metadaten melden die Übergabe an die Videoausgabe. Ein vom Anbieter als
reguläre neue Frames gesendetes Standbild oder ein Fehler unterhalb dieser
Ausgabe lässt sich damit nicht sicher erkennen.

Rust bleibt allein für Wiederverbindungsabstände zuständig. Versuchs-Tokens
schützen vor alten Player-Rückmeldungen. VOD behält seine 60–120-s-Reserve und
Wiederaufnahme an der gespeicherten Position.

## Diagnose und Tests

Der Diagnosebericht enthält `playback_health` mit Phase, Core-Status und maximal
20 Wiederherstellungsereignissen: Auslöser, monotone Zeit, Puffer, Position,
Datenmenge, letzte Empfangsrate und Framezahl. `recovered` bedeutet erneut
beobachtete Wiedergabe. Die neuen Ereignisse enthalten keine Stream-URLs oder
Zugangsdaten.

Rust-Tests prüfen nutzlosen Datenempfang, Startwartezeit, harte Live-Deadline
und laufende Frames bei konstanter Position. Kotlin-Tests prüfen den Frame-Timer.
`scripts/test-live-recovery.ps1` prüft über HTTP, Media3, JNI und Rust die Erholung
nach dreimaligem Live-404/410, lokale Retry-Grenzen, Profile/Migration, Live-EOF
und das Abbrechen geplanter Wiederholungen. `scripts/test-playback-controls.ps1`
prüft zusätzlich VOD-Reserve, stockende Downloads, Spulen und Seriensteuerung.

Prüfergebnis dieser Änderung: 104 Kotlin-Unit-Tests bestanden, Rust-Workspace-
Tests und Clippy erfolgreich, Android-Lint ohne Fehler sowie neun unterschiedliche
gezielte Android-Tests bestanden (je fünf in beiden Skripten, ein Profiltest ist
in beiden Gruppen enthalten). Debug-App und Test-APK wurden gebaut.

Emulatortests ersetzen keinen Dauertest auf dem betroffenen Fernseher mit dem
Anbieter. Ausfälle über die vorhandene Reserve hinaus unterbrechen weiterhin
die Wiedergabe. Fehlender Empfang allein unterscheidet WLAN, Internetweg und
Anbieterausfall nicht zuverlässig.

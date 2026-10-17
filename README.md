# RUSTREAM IPTV PLAYER – Android TV / Google TV

RUSTREAM IPTV PLAYER ist ein IPTV-Player mit einem in Rust entwickelten Core. Der Fokus liegt auf hoher Performance und einem flüssigen Streaming-Erlebnis – besonders bei Live-Sport und Fußball.

**Branding (Logo-Update vom 17. Oktober 2026):** Eigener App-Name im Installer und TV-System, Launcher-Icon und TV-Banner aus dem bereitgestellten Logo, Markenanzeige im Startbildschirm und **Einstellungen → App-Info** mit Beschreibung und Version. Installierbare Testfassung: `artifacts/RUSTREAM-IPTV-0.5.0.apk`; Debug: `RUSTREAM-IPTV-debug.apk`; Bundle: `RUSTREAM-IPTV-release-unsigned.aab`. Das Logo-Original liegt in `app/src/assets/logo/new_logo.png`, die dunkle UI-Ableitung in `new_logo_dark.png`. Die Android-Exporte mit dunklen Icon-/Bannerflächen werden mit `scripts/prepare-branding.ps1` reproduziert. Die Paket-ID bleibt für Updates bestehender Installationen erhalten.

Native TV-App auf Basis des vorhandenen Windows-Rust-Players. Kotlin übernimmt Android, Fernbedienung und Media3; Rust übernimmt M3U, Suchindex, Xtream-Datenmodell, XMLTV und Recovery. Die Windows-App im Nachbarordner bleibt unverändert.

**Inhaltsbibliothek 0.4.0:** Windows-Erkennungsregeln, Originalmetadaten, Filmcover mit Jahr/Qualität und gruppierte M3U-Serien mit Staffeln und Episoden. Xtream nutzt dieselbe Serienansicht. Unklare Inhalte bleiben unter „Unzugeordnet“ erreichbar. Der Offline-Katalog erhält einen sicheren Übergang auf Schema 2. Verhalten, Architektur und Prüfnachweise: [Filme und Serien 0.4.0](docs/CONTENT_LIBRARY_040.md).

**UI-Update 0.3.1:** Animierter Start mit echten Ladephasen, dauerhafter Videofokus, erneutes OK auf dem aktiven Eintrag für Vollbild, Refresh als Textbutton unter den Statistiken und beschleunigtes Spulen per gehaltener Links-/Rechts-Taste bei Filmen und Serien. Dialoge und Textflächen passen sich größerer Systemschrift an. Details und Prüfnachweise: [UI-Umsetzung](docs/UI_IMPLEMENTIERUNG_031.md).

**Stand 0.3.0:** Persistenter SQLite-Katalog mit verschlüsselten Streamadressen, schnellem Offline-Wiederstart und Import auf einem getrennten Worker. **Live / Movies / Series** bleiben samt Anbietergruppen erhalten. Xtream unterstützt Serienkatalog, Staffeln und bei Bedarf geladene Episoden. Importgrenzen im neuen Katalogpfad: 512 MiB / 2.000.000 Einträge / 20.000 Kategorien; XMLTV behält 128 MiB. HTTP-, Verbindungs-, Format-, Datei- und Speicherfehler erhalten eigene Meldungen und einen kopierbaren Diagnosebericht unter Einstellungen. HTML-/JSON-Fehlerseiten werden als Playlist abgewiesen. Messwerte und Grenzen: [Umsetzungsbericht](docs/PLAYLIST_IMPLEMENTIERUNG_030.md).

**Update 0.2.1:** Live-Pufferprofile Sport/Ausgewogen/Stabil (1/3/6 s), kürzerer erster Live-EOF-Retry, korrigierte Fortschrittserkennung und gebündelte JNI-Telemetrie. Analyse, Änderungen und Nachweise: [Performance-Review](docs/PERFORMANCE_REVIEW.md).

## Bauen und installieren

PowerShell 7, Windows x64 und Internet für das erste Setup:

```powershell
./scripts/setup.ps1
./scripts/build.ps1 -Release
. ./scripts/env.ps1
adb install -r ./artifacts/RUSTREAM-IPTV-debug.apk
adb shell am start -n de.iptv.tv/.MainActivity
```

`setup.ps1` installiert JDK, Gradle, SDK 36, NDK r28c und Android-Rust-Ziele projektlokal. Wenn verfügbar, werden Rust und LLVM aus `../IPTV Player/.tools` wiederverwendet. `scripts/env.ps1` setzt ausschließlich die Umgebung des aktuellen Prozesses. Gradle baut die drei nativen ABIs automatisch vor dem APK. Keine Android-Studio-Installation erforderlich; der Ordner kann dort als Gradle-Projekt geöffnet werden.

Ausgaben: `artifacts/RUSTREAM-IPTV-debug.apk` (installierbar und mit Debug-Key signiert), `app/build/outputs/apk/release/app-release-unsigned.apk` und `artifacts/RUSTREAM-IPTV-release-unsigned.aab`. Release-Dateien benötigen vor Distribution einen eigenen Signierschlüssel. Das Projekt veröffentlicht nichts im Store. Die bisherigen `iptv-tv-*`-Dateinamen bleiben als Kompatibilitätskopien vorhanden.

Für die UI-Abnahme liegt außerdem `artifacts/RUSTREAM-IPTV-0.5.0.apk` bereit: die R8-optimierte, am Emulator geprüfte APK mit lokaler Debug-Testsignatur (0.5.0: Sprachauswahl Deutsch/English).

Android TV/Google TV ab Android 6 / API 23; ARM64, ARMv7 und x86_64. Ältere Android-TV-Versionen liegen außerhalb des Minimums der verwendeten Media3-Version. Keine Bindung an TCL oder einen bestimmten SoC. Tatsächliche 4K-/HDR-/Codec-Unterstützung hängt vom Decoder des Geräts ab.

## Bedienung

- **Zahnrad → Einstellungen:** Quellen verwalten, Datei hinzufügen, URL hinzufügen, Xtream anmelden, EPG importieren und Bilddarstellung. Importaktionen belegen keinen Platz in der Hauptansicht.
- **Sprache · Language:** Unter Einstellungen zwischen Deutsch (Standard) und English wählen. Die Oberfläche wird sofort neu aufgebaut: Schaltflächen, Dialoge, Statusmeldungen, Verbindungsdetails, Player-Steuerung und Fehlermeldungen erscheinen in der gewählten Sprache; Zahlen folgen ihrem Format. Eine laufende Wiedergabe stoppt dabei, Verlauf, Favoriten und Fortschritt bleiben erhalten. Sender-, Film- und Kategorienamen des Anbieters werden nicht übersetzt.
- **Datei / URL:** M3U/M3U8 über Androids Dokumentauswahl oder einen direkten HTTP(S)-Link. Eine entfernte HLS-Medien-/Masterliste wird als ein Stream behandelt.
- **Xtream:** Server mit optionalem Port/Basispfad, Benutzername und Passwort; Anmeldung wird vor dem Laden von Live, Filmen und Serien geprüft. Serien öffnen eine Staffel- und Episodenauswahl; Details werden beim ersten Öffnen geladen. Ein vom Anbieter nicht unterstützter optionaler Katalog wird im Diagnosebericht gemeldet.
- **Katalogcache:** Beim Start erscheint die zuletzt aktive Quelle aus dem privaten Katalog. Geänderte Dateimetadaten bzw. ein nach 15 Minuten veralteter Katalog lösen eine Aktualisierung im Hintergrund aus. Laufende Wiedergabe behält ihre Revision; bei Bedarf Quelle erneut wählen. HTTP-Validatoren ermöglichen 304 ohne erneuten Download. Die erste Verarbeitung einer neuen Quelle benötigt weiterhin einen vollständigen Import.
- **Quellen verwalten:** Gespeicherte Playlist erneut laden, zwischen Quellen wechseln oder entfernen. Ein erfolgreicher Quellenwechsel stoppt die bisherige Wiedergabe. Fehlgeschlagene Imports erhalten die bisherige Bibliothek.
- **Live / Movies / Series:** Zuerst Bereich auswählen, dann Kategorie und Eintrag. Film- und Serienraster nutzen die volle Breite ohne Player. Eine Filmkarte öffnet Filmdetails mit Play-Button und Player rechts; eine Serienkarte öffnet Staffeln und Episoden mit Player rechts. Varianten bleiben einzeln abspielbar. Die Serienzahl zählt Serienobjekte; gleichnamige Kategorien bleiben pro Bereich getrennt. **Unzugeordnet** erscheint nur bei unklaren Einträgen.
- **Kategorie suchen:** Das Feld über der Kategorienliste filtert deren Namen im aktuellen Bereich. **Sender / Filme / Serien suchen:** Das zweite Feld durchsucht Namen, TVG-Namen, Kategorie und TVG-ID innerhalb der Auswahl. Episodentreffer erscheinen als Serienkarten. Bereichswechsel setzen beide Suchfelder und die Kategorie zurück. **Alle Kategorien** hebt die Kategorieauswahl auf und schließt geöffnete Seriendetails.
- **App-Start:** Logo und echte Ladephasen erscheinen, bis die erste Senderseite bedienbar ist. EPG lädt danach. Ohne Quelle führt „Quelle hinzufügen“ zur Einrichtung; ein Ladefehler bietet Wiederholung und Quellenauswahl.
- **D-Pad/OK:** Zeilen haben mindestens 36 dp Höhe und wachsen mit der Systemschrift. OK auf Film-/Serienkarten öffnet Details; Play bzw. die Episodenauswahl startet die Wiedergabe. Bei Live startet OK direkt den Sender. Erneutes OK auf dem aktiven Eintrag öffnet Vollbild ohne neue Verbindung. In Live/Details führt Rechts zum dauerhaft umrahmten Video, Links aus der Vorschau zur vorherigen Auswahl. Pakete mit 64 Einträgen werden in beide Richtungen nachgeladen; höchstens 512 Zeilen bleiben im UI-Fenster. Fokus und laufender Eintrag bleiben getrennt.
- **Videosteuerung:** Runter öffnet die Icon-Steuerung; im Vollbild auch OK. In der Vorschau öffnet OK auf dem Bild direkt Vollbild. Die Steuerung enthält Play/Pause, deutlich beschriftete −30-s-/+30-s-Sprünge, Stopp und Vollbild. Links/rechts wählt die Aktion, OK führt sie aus. Nach vier Sekunden ohne Eingabe blendet sie sich während der Wiedergabe aus; bei Pause bleibt sie sichtbar. Play startet nach Stopp den zuletzt ausgewählten Eintrag erneut.
- **Fernbedienung:** Bei fokussierter Wiedergabefläche im Vollbild eines seekbaren Films oder einer Serienepisode springt ein kurzer Links-/Rechts-Druck genau 30 Sekunden zurück/vor, begrenzt durch Anfang/Ende. Gedrückthalten spult nach 400 ms kontinuierlich und zunehmend schneller, bis maximal 180 Mediensekunden pro Sekunde. Loslassen stoppt sofort; Zurück, Fokusverlust, Home und Inhaltswechsel beenden den Spulablauf. Bei sichtbarer Buttonleiste dienen Links/Rechts ihrer Navigation. Live erhält keine solche Spulbelegung. Channel +/− und Media-Tasten bleiben verfügbar. Zurück schließt die Steuerung, verlässt Vollbild bzw. kehrt von der Vorschau zur Senderliste zurück und beendet erst von dort die App. Home/Standby gibt Player und Surface frei; eine bewusste Pause bleibt erhalten.
- **Vollbild:** Das Video belegt die gesamte App-Fläche ohne äußere Abstände. Einstellungen → Bilddarstellung wählt zwischen proportionalem Zuschneiden (Bild füllen, Standard) und Originalformat mit möglichen Formatbalken.
- **Verbindungsdetails:** Pufferzeit, tatsächlicher Netzwerkzufluss, vom Medienallocator belegter Datenpuffer und Videoauflösung. Darunter verbindet der normale Textbutton **Refresh** die ausgewählte Wiedergabe neu; er importiert keine Playlist. Die Aktion ist aus dem Bild entfernt. Die Statusbewertung berücksichtigt Playerzustand und Puffer; ein voller Puffer ohne neuen Download ist kein Verbindungsfehler. Unbekannte Werte erscheinen als „—“. Unten steht „developed by Ninos Oshana“.
- **EPG:** Unter Einstellungen eine XMLTV-Datei oder HTTP(S)-URL importieren; Zuordnung über `tvg-id` bzw. Xtream `epg_channel_id`. Aktuelle/nächste Sendung nach Kanalauswahl; die Quelle wird verschlüsselt gespeichert und beim nächsten Start geladen. XMLTV muss vollständige Zeiten mit UTC-Offset enthalten.

Quellen und Zugangsdaten liegen AES-GCM-verschlüsselt im App-Verzeichnis ohne Backup, der Schlüssel im Android Keystore. Imports verwenden begrenzte temporäre Dateien im privaten Cache. Anbieter-URLs werden nicht in Listen-Daten oder eigenen Fehlermeldungen ausgegeben. Android-/Media3-Systemdiagnosen können technische Quelldetails enthalten; vor einer Weitergabe von Logcat prüfen.

## Tests

```powershell
. ./scripts/env.ps1
cargo test --locked --workspace
./gradlew.bat testDebugUnitTest lintDebug assembleDebug
./scripts/setup-emulator.ps1
./scripts/test-device.ps1
./scripts/test-release.ps1
```

Die Geräte-Tests verwenden ausschließlich ein synthetisches Video und einen lokalen HTTP-Testserver. Der Wiedergabetest überschreibt vorübergehend die Quellenliste und stellt sie im `finally` wieder her. Deshalb auf einem Testgerät/Emulator ausführen. Der Emulator wird mit `adb -s emulator-5554 emu kill` beendet, falls keine andere Instanz den Port verwendet.

`test-release.ps1` prüft die R8-optimierte App mit einer separaten Debug-Testsignatur ausschließlich auf einem Emulator. Dabei werden dessen IPTV-App-Daten zurückgesetzt. Der Test startet einen lokalen synthetischen Anbieter auf Port 18888 und beendet ihn danach. Der unveränderte Publisher-AAB bleibt unsigniert. `scripts/verify-package.ps1` prüft 16-KB-Ausrichtung und JNI-Symbole; `scripts/package-source.ps1` erzeugt ein Quellarchiv ohne lokale Tools, Zugangsdaten oder Schlüssel.

Eine private Liste wird separat getestet:

```powershell
./scripts/test-private-playlist.ps1 -Playlist 'C:\Pfad\private.m3u'
```

Das Skript erstellt anonyme Statistiken, überträgt die Liste separat in das App-Verzeichnis des Emulators und testet 13 Quellen nacheinander. Die Testkopie wird danach gelöscht; die Originaldatei bleibt erhalten. Die bisherigen Quellen werden wiederhergestellt. JSON-Berichte unter `artifacts/playlist-review/` enthalten keine Stream-URLs, Namen oder Zugangsdaten. Private Listen im Test-Asset-Ordner gelangen durch eine explizite Freigabeliste weder ins Test-APK noch ins Quellarchiv; `check-private-assets.ps1` prüft das gebaute Test-APK zusätzlich.

Die Herkunft der übernommenen Module steht in [UPSTREAM.md](docs/UPSTREAM.md), die Plattformanalyse und Architektur in [ARCHITECTURE.md](docs/ARCHITECTURE.md), der verifizierte Stand in [TEST_REPORT.md](docs/TEST_REPORT.md). [DEVICE_ACCEPTANCE.md](docs/DEVICE_ACCEPTANCE.md) beschreibt die noch erforderliche TV-Abnahme.

## Grenzen und nächste Optimierungen

HTTP(S), HLS, DASH, progressive Medien und RTSP werden an Media3 übergeben. UDP-/RTP-Multicast und RTMP sind in diesem Build nicht angebunden und werden bei der Auswahl mit einer Meldung abgewiesen. DRM, Catch-up, Favoriten, Track-Auswahl und ein dauerhaft gespeicherter VOD-Verlauf sind noch nicht implementiert. Serien/Episoden aus M3U und Xtream-Serien mit Staffelauflösung werden unterstützt. EPG unterstützt unkomprimiertes UTF-8-XMLTV, keine `.gz`-Dateien als lokale Eingabe und keine externen XML-Entities.

Der Windows-RTX-/D3D11-Pfad ist gerätespezifisch und wird durch Android MediaCodec ersetzt. Media3 behält sein eigenes ABR. Unter Einstellungen → Live-Pufferprofil stehen Sport (1 s/16 MiB), Ausgewogen (3 s/32 MiB, Standard) und Stabil (6 s/64 MiB) zur Wahl, wirksam ab dem nächsten Verbindungsaufbau. Start-/Nachfüllschwellen liegen bei 0,25/0,5 s, 0,5/1 s und 1/2 s. VOD behält 10–30 s/32 MiB. Die Zeitziele begrenzen das gewünschte Vorlesen, nicht den Abstand zum realen Ereignis; Segmente und einzelne Ladeblöcke können sie überschreiten. Das Byte-Ziel begrenzt den Media-Puffer, nicht den gesamten Prozess oder alle Decoder-Allokationen. Sehr knappe Profile sind empfindlicher gegen schwaches WLAN; reale TV-Messungen bleiben erforderlich.

Es gibt keine belastbare Zusage gleicher oder besserer Geschwindigkeit als unter Windows ohne vergleichbare Messungen auf dem Fernseher. Große Playlist-Tests, native Release-Builds und Emulator-E2E sichern die Implementierung ab; 4K/HDR, schwaches WLAN, Langzeitbetrieb, echter Standby und Anbieterbesonderheiten gehören zur Geräteabnahme.

GPL-3.0-or-later wie das Ursprungsprojekt. Siehe [LICENSE](LICENSE) und [THIRD_PARTY.md](THIRD_PARTY.md).


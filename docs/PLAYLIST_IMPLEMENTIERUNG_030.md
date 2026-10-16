# Playlist-Import und große Kataloge — Umsetzung 0.3.0

16. Oktober 2026. Android-Version 0.3.0, VersionCode 6. Dieser Bericht ergänzt den ursprünglichen [Analyse- und Maßnahmenplan](PLAYLIST_PERFORMANCE_UND_FEHLERPLAN.md). Das Windows-Projekt und die Originalplaylist wurden nicht geändert.

## Umgesetzte Änderungen

- **Persistenter Katalog:** SQLite ersetzt im Android-Importpfad den vollständigen RAM-Katalog. Der Parser liefert Einträge einzeln an einen transaktionalen Sink. Eine neue Generation wird erst nach erfolgreichem Aufbau geöffnet; ein fehlerhafter Import erhält die bisherige Bibliothek. Import und Wiedergabe verwenden getrennte JNI-Core-Instanzen und Executor-Threads.
- **Offline-Wiederstart:** Die zuletzt aktive Quelle wird aus ihrem lokalen Katalog geöffnet. HTTP-Validatoren unterstützen 304 ohne erneutes Herunterladen/Parsen. Geänderte verfügbare Dateimetadaten oder ein 15 Minuten alter Katalog lösen eine Hintergrundaktualisierung aus. Bei laufender Wiedergabe wird die neue Generation zunächst nur gespeichert; explizites Neuladen übernimmt sie ohne ungültige alte Kanal-IDs. Ein gültiger Cache bleibt bei einem Aktualisierungsfehler nutzbar.
- **Begrenzter Speicher:** SQLite-Seitencaches von 2 MiB je Verbindung, kein zweiter vollständiger Objektbaum und maximal 512 UI-Zeilen. Paging kann vorwärts und rückwärts nachladen. Native Importe und HTTP-Downloads werden bei Coroutine-Abbruch storniert. Vorherige vollständige Generation und fremde Staging-Dateien werden bei der Bereinigung geschützt; verwaiste Generationen werden vor dem ersten Import eines neuen Prozesses bereinigt.
- **Größere Listen:** Neuer Produktpfad mit 512 MiB, 2.000.000 Einträgen und 20.000 Kategorien. Die Eintragsgrenze wird im Sink geprüft. Xtream-Einzelobjekte sind auf 1 MiB begrenzt, Kategorieantworten/Seriendetails auf 8 MiB, einzelne Serien auf 10.000 Episoden. XMLTV behält seine bisherigen Grenzen. Die älteren In-Memory-Core-Requests bleiben zu Test-/Kompatibilitätszwecken mit ihren bisherigen Grenzen erhalten.
- **Parser:** HTML-/JSON-/Binärantworten werden nicht mehr als relative Streamlisten übernommen. Extended-M3U, echte einfache URL-Listen, relative Einträge in validierten M3U und HLS bleiben unterstützt. Unterstützte Attribute werden als geliehene Textausschnitte gelesen; die HashMap samt Kopien pro Eintrag entfällt. URL-Routen werden ohne vollständigen Pfad-Lowercase-/Segment-Vektor klassifiziert.
- **Xtream-Serien:** Account wird vor großen Katalogabrufen geprüft. Live, Filme und Serien werden über getrennte, begrenzte Response-Dateien streamend eingelesen. Serien enthalten eine `series_id`; sie sind keine fingierten abspielbaren Filmadressen. Staffel-/Episodendetails werden bei Bedarf geladen und separat im Katalog gespeichert. Fehlende optionale Endpunkte werden als Hinweise gespeichert und auch nach Cache-Restore angezeigt.
- **Fehlerdiagnose:** Eigene Meldungen für HTTP 401/403/404/410/429/5xx, DNS, Verbindungsaufbau, Timeout, TLS, Dateizugriff, ungültige Formate, Importgrenzen, Datenträger und nachgewiesenen RAM-Mangel. Ein Timeout wird nicht pauschal dem WLAN oder dem Anbieter zugeschrieben. Wiedergabefehler behalten HTTP-/Media3-Codes und redigierte Typen der Ursachenkette.
- **Diagnosebericht:** Einstellungen → Diagnosebericht, mit Kopierfunktion. Enthält App-/Core-Version, Core-Build-ID, Importart, Download-/Kopier-/Parser-/Gesamtzeiten, Eintrags-/Skipzahlen, Hinweise und Fehlerstufe. Auf Android ab API 30 wird der letzte dokumentierte Prozess-Beendigungsgrund mit Bedeutung angezeigt, insbesondere bestätigter Arbeitsspeicherdruck. Auf älteren Versionen bleibt dieser Nachweis ausdrücklich nicht verfügbar.

URL, Logo und Streamheader werden im SQLite-Katalog mit AES-256-GCM und zufälligem Nonce je Datensatz verschlüsselt. Pro Generation wird ein zufälliger Schlüssel verwendet; Schlüssel und Manifest sind durch die bestehende Android-Keystore-Ablage versiegelt. Namen, Kategorien, TVG-IDs und Suchtexte liegen für die Abfragen im privaten, vom Backup ausgeschlossenen Katalog. Die Datenbank wird nicht als vollständig verschlüsselte Datei bezeichnet. Diagnoseberichte enthalten keine vollständigen Quellen-/Streamadressen oder Zugangsdaten.

## Messung mit der echten Referenzplaylist

Fixture: 75.866.529 Bytes, 257.468 Einträge; 6.186 Live, 20.416 Filme und 230.866 M3U-Episoden; 395 Kategorien. Keine übersprungenen oder kategorielosen Einträge. Alle Bereiche und ihre Trefferzahlen wurden auch durch die reale Android-Oberfläche geprüft.

| Messung | Vorher | Neuer Stand |
| --- | --- | --- |
| Reines Parsen auf dem Windows-Host, Release, je fünf Läufe | 842–1.030 ms, Median ca. 895 ms | 602–655 ms, Median ca. 621 ms |
| Activity-Start bis sichtbare erste Zeilen, unveränderte gespeicherte Quelle | 2.575–2.998 ms, erneuter Vollimport | 599 ms aus Katalog; ein voriger neuer Lauf lag bei 615 ms |
| Abgetastete native Heap-Spitze im großen Import-/Reload-/UI-Test | ca. 263–265 MiB | ca. 36,65 MiB |
| Nativer Heap nach großen Imports | ca. 136,6–136,8 MiB | ca. 8,34–8,37 MiB |
| Prozess-PSS nach großen Imports/Reloads | ca. 196–280 MiB | ca. 88–92 MiB |
| Prozess-PSS bei geladener UI | ca. 233–235 MiB | ca. 112,5 MiB |
| Neuer Katalogaufbau im Emulator, drei direkte Ladeläufe | früherer RAM-Core ca. 0,86–1,18 s | ca. 2,11–2,29 s im Rust-Datenträgerpfad |
| Gesamter Dateiimport, drei direkte SourceLoader-Läufe | Kein identisch isolierter SourceLoader-Vorherlauf | ca. 2,36–2,60 s |
| Erster vollständiger Activity-Import ohne Cache | ca. 2,6–3,0 s | 4,73 s im letzten Lauf |
| Vollständiger URL-Import vom lokalen HTTP-Server | ca. 2,99 s in früherem Einzeltest | ca. 4,50 s, davon 2,09 s Katalogaufbau |

**Abwägung:** Der initiale Datenträgeraufbau benötigt zusätzliche Schreib-, Index- und Verschlüsselungsarbeit und ist trotz schnellerem Parser weiterhin langsamer als der alte reine RAM-Import. Die Verbesserung betrifft vor allem wiederholte Starts und den Speicherbedarf; ein erster vollständiger Download/Import bleibt erforderlich. Die anfängliche neue Katalogimplementierung benötigte noch etwa 3,7–4,6 s im Parser-/Writer-Pfad; die Attribut-/Routenoptimierung reduzierte das im anschließenden Lauf auf etwa 2,1–2,3 s.

Die native Heap-Spitze ist damit in diesen Stichproben rund 86 % kleiner. Der Cache-Start benötigt im Emulator ungefähr eine halbe bis zwei Drittel Sekunde; daraus folgt keine Zusage für jeden Fernseher. Es handelt sich um wenige Läufe mit warmen Betriebssystemcaches, keine belastbaren p95-Werte. Die Heap-Abtastung beträgt 25 ms und kann sehr kurze Spitzen verpassen. PSS-Werte sind Momentaufnahmen. Emulator: Android 16, Google TV, x86_64, 16-KB-Seiten, 2 GiB RAM, Debug-Kotlin und optimierte native Bibliotheken.

Der vollständige Parser-Fingerabdruck ist vor und nach der Optimierung identisch (`86b2cf9c927cd9cf`). Zusätzlich wurden alle 257.468 Katalogdatensätze feldweise mit dem Parser verglichen: Name, Bereich, Kategorie, URL, TVG-ID, Logo, Header und Live-Flag. Die neuen Datenbank-/Paging-Abfragen erhalten Teilstring-, Unicode- und Mehrbegriffsverhalten; die Datenbank hält keine vollständige Treffer-ID-Liste im RAM.

## Große synthetische Liste und reale Streams

Ein gesonderter Release-Stresstest erzeugte eine temporäre Datei über 128 MiB mit **1.000.001 Einträgen**, importierte sie vollständig, prüfte den letzten Eintrag und öffnete den Katalog erneut. Test bestanden, zuletzt ca. 7,1 Sekunden inklusive Fixture-Erzeugung, Import, Prüfung und Bereinigung auf dem Host. Dies ist keine Android-Millionen-Eintragsmessung und keine Importzeit des Fernsehers.

Die drei ausdrücklich freigegebenen Bein-Sport-Streams wurden im neuen Build über die vollständige Playlist abgespielt:

| Stream | Erstes gerendertes Bild | Ergebnis |
| --- | ---: | --- |
| Bein_Sport 1 (UHD) | 1.595 ms | Video bestätigt, kein beobachteter HTTP-/Media3-Fehler |
| Bein_Sport 2 (UHD) | 923 ms | Video bestätigt, kein beobachteter HTTP-/Media3-Fehler |
| Bein_Sport 3 (UHD) | 847 ms | Video bestätigt, kein beobachteter HTTP-/Media3-Fehler |

Alle drei lieferten H.264 mit 1920×1080. Der erste bestand außerdem 30 Sekunden Wiedergabe ohne beobachtetes Nachpuffern, Pause, manuelles Reconnect und Lifecycle-Freigabe/Rückkehr. Reconnect bis `isPlaying`: 876 ms, keine separate neue Erstbildmessung. Prozess-PSS in diesen Stichproben ca. 110–113 MiB. Die beiden anderen Streams wurden kurz angespielt. Kein Langzeit- oder physischer ARM-TV-Nachweis.

## Validierung und Artefakte

Abschlussstand: **55 Rust-/JNI-Regressionen plus der gesonderte Millionen-Test, 10 JVM-Tests und 12 Android-Tests bestanden** (zehn synthetische Regressionen, ein großer lokaler Benchmark, ein freigegebener Anbieter-Test). Der finale R8-Smoke-Test besteht Start, JNI-Import, D-Pad-Aktivierung und echte Media3-Wiedergabe. Lint: 0 Fehler, 39 Warnungen. Installierbares Entwicklungs-APK: `artifacts/iptv-tv-0.3.0-debug.apk`; außerdem Release-APK/AAB und Quellenarchiv. Der Release-Smoke verwendet eine separate lokale Testsignatur; das Publisher-Paket benötigt weiterhin einen eigenen Signierschlüssel.

- Rust-/JNI-Regression und Clippy, einschließlich HTML-Abweisung, Bibliothekserhalt, persistenter Wiederöffnung, verschlüsselter Streamadressen, Serienauflösung, Episodensortierung und Rollback bei kaputten Episodenantworten.
- Gesonderter Millionen-Test im Release-Profil; JVM-Fehlermapping und bestehende Puffer-/Zufluss-/Kopiertests.
- Android-Integration für Offline-Restore, HTTP 304, Serienkatalog und Staffelauflösung, Generationserhalt, Schutz eines fremden Staging-Katalogs, Paging sowie die vorhandene Wiedergabe-/Fernbedienungs-/Lifecycle-Suite.
- Lokaler Fehlerserver: 401/403/404/429/500/503 behalten unterschiedliche Codes; HTML 200 wird abgewiesen. Die bisherigen UI-Sammelmeldungen wurden ersetzt.
- Debug- und R8-Release-Build, AAB und Lint. APK-Ausrichtung und JNI-Exporte einschließlich `nativeCancel` auf ARM64, ARMv7 und x86_64 geprüft. Private Playlist bleibt außerhalb der APK-Assets und des Quellenpakets.

Rohdaten unter `artifacts/implementation-*.json` bzw. `implementation-*.txt`, insbesondere `implementation-summary.json`, `implementation-large-device-final.json`, `implementation-parser-host.json`, `implementation-catalog-host.json`, `implementation-million-test.txt` und `implementation-requested-streams.json`. Die endgültigen Build-/Regressionsprotokolle werden daneben gespeichert. Die ersten zusätzlichen Paging-Testfassungen deckten einen ungeeigneten Fokusaufbau im Test und notwendige Absicherungen der Rückwärtsnavigation auf; sie werden nicht als bestandene Läufe gezählt.

## Noch erforderliche Geräteabnahme und Grenzen

Ein physischer Fernseher war nicht verbunden. Dort sind insbesondere Warmstart, erstmaliger Import über SAF/USB/WLAN, schwaches Netzwerk, nativer/Java-/Prozessspeicher und Langzeitwiedergabe zu messen. Die ursprünglich gemeldeten sporadischen Fehler wurden auf dem Emulator nicht reproduziert; die neue Diagnose soll deren tatsächliche Ursache am TV sichtbar machen.

Der Dateiimport verwendet weiterhin eine begrenzte temporäre Kopie; ein direkter SAF-Dateideskriptorpfad bleibt eine mögliche weitere Optimierung. Xtream-Bulkabrufe bleiben sequenziell, werden aber einzeln und streamend verarbeitet. Die Parserherkunft ist dokumentiert; ein gemeinsames Paket mit Windows wurde noch nicht ausgegliedert. Datenbank-Suche kann bei langen Teilstringabfragen über Millionen Zeilen weiterhin einen Scan erfordern. Diese Grenzen werden nicht durch eine pauschale Subsekunden-Zusage verdeckt.

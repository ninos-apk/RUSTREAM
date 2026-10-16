# Review mit der echten Playlist – Version 0.1.1

15. Oktober 2026. Tests auf Google TV / Android 16, x86_64, 16-KB-Speicherseiten, 1920×1080; laufende App mit vollständiger Bibliothek. Keine Zugangsdaten oder Stream-URLs in diesem Bericht.

## Ergebnis

Die Liste lässt sich nach der Korrektur vollständig importieren und zügig durchsuchen. In zwei Durchläufen lieferten jeweils **13 von 13 ausgewählten Quellen echte Videoframes**: zehn Live-TV-Quellen, zwei Filme und ein Serienstream. Die Stichprobe funktionierte ohne gemeldete HTTP- oder Media3-Wiedergabefehler. Der zweite Lauf ergänzt 30 Sekunden kontinuierliche Wiedergabe, Pause, manuelles Wiederverbinden, Lifecycle-Freigabe/Rückkehr und VOD-Seek während einer Pause. Alle diese Prüfungen waren erfolgreich.

Das ist eine positive Stichprobe, keine Vollprüfung sämtlicher 257.468 Streams oder ein Langzeittest. UHD-/4K-Bezeichnungen werden gemäß Nutzerangabe ausschließlich als Kanalnamen behandelt und nicht als Qualitätskriterium bewertet.

## Umfang und Messwerte

| Größe / Prüfung | Gemessen |
| --- | --- |
| Dateigröße | 75.866.529 Bytes / ca. 72,4 MiB |
| Vollständiger Import | 257.468 Einträge, 392 Kategorien, 0 übersprungen |
| Live-TV / On-Demand | 6.845 / 250.623 Einträge |
| Container-Endungen | 189.824 MKV, 60.799 MP4; Live-URLs überwiegend ohne Endung |
| App-Start bis vollständiger Bibliothek | 2,33–3,08 s in zwei Läufen |
| Rust-Import auf dem Emulator | 1,45–1,50 s |
| Suche einschließlich JNI | 3,4–48,3 ms für sechs Abfragen je Lauf; bis zu 64 Ergebnisse pro Seite |
| Letzte Seite der vollständigen Liste | Richtige letzte zehn Einträge, keine Abschneidung |
| Erstes gerendertes Videobild | 0,66–3,36 s im erweiterten Lauf; Median 1,39 s bei 13 Quellen |
| Speicher nach Import | ca. 212 MiB Prozess-PSS; davon ca. 146 MiB belegter nativer Heap |
| Speicher während Stichprobe | beobachtete Prozess-PSS-Momentaufnahmen bis ca. 274 MiB |
| Kontinuierliche Live-Wiedergabe | 30 s, fortschreitende Position, 0 zusätzliche Buffering-Episoden |
| Pause / manuelles Reconnect | erfolgreich; Reconnect bis `isPlaying` ca. 1,14 s im erweiterten Lauf |
| Verlassen / Rückkehr | Player wurde freigegeben und eine neue Player-Instanz spielte nach Rückkehr |
| VOD-Seek bei Pause | Sprung um 15 s, Pause blieb erhalten; Fortsetzen erfolgreich |

Die Erstbildprüfung verlangt ein `onRenderedFirstFrame`-Ereignis sowie laufende Wiedergabe mit mindestens 1,5 s Medienposition. Ein erfolgreicher HTTP-Abruf oder ein bloßer Statuswechsel allein zählt nicht als Wiedergabenachweis. Reconnect-/Resume-Messungen bestätigen `isPlaying`; sie sind keine separaten Erstbildmessungen.

Host-Parser und Android-Import verwenden denselben Rust-Parser. Die Host-Messung ist kein direkter Performancevergleich mit dem Windows-Player. Suchzeiten sind einzelne Stichproben, keine belastbaren p95-/p99-Werte. PSS wurde an ausgewählten Zeitpunkten gemessen; die tatsächliche kurzzeitige Prozessspitze kann darüber liegen.

## Review-Befunde und Änderungen

### P1 – Reale Liste wurde vollständig abgewiesen: behoben

Die App begrenzte Importe in Kotlin und Rust auf 32 MiB und die Bibliothek auf 100.000 Einträge. Die echte Liste überschreitet beide Grenzen; der Ausgangsstand quittiert sie mit `import_too_large`, noch bevor ein Kanal geladen wird. Beide Seiten erlauben jetzt 128 MiB und bis zu 500.000 Einträge. Die UI erklärt Größen- und Eintragsgrenzen ausdrücklich. Der Media3-Wiedergabepuffer bleibt bei 32 MiB; er wurde nicht auf 128 MiB vergrößert.

Ein synthetischer Regressionstest reproduziert mehr als 32 MiB und exakt 257.468 Einträge, einschließlich Zugriff auf das Listenende. Ein zweiter Test prüft, dass Dateien über der neuen Grenze abgewiesen werden und die vorherige Bibliothek dabei erhalten bleibt. Der unveränderte Windows-Parser und seine Herkunftsnachweise bleiben erhalten.

### P1 – Private Liste im Test-Asset-Ordner würde mitgeliefert: behoben

Android-Assets werden standardmäßig ins Test-APK übernommen; auch das bisherige Quellpaket sammelte sämtliche Dateien unter `app/src`. Das wäre für eine Anbieterliste mit Zugangsdaten ungeeignet. Die Builds verwenden jetzt einen erzeugten Asset-Ordner, der ausschließlich das synthetische Testvideo enthält. Ein ZIP-Inhaltstest prüft das tatsächlich gebaute Test-APK. Das Quellarchiv nutzt dieselbe Freigabe für Asset-Dateien, und private Listen sind in `.gitignore` eingetragen.

Die echte Liste wird separat ins App-Verzeichnis des Testemulators übertragen, sequentiell getestet und die Testkopie danach gelöscht. Die vom Nutzer abgelegte Originaldatei bleibt unverändert. Berichte enthalten nur Zahlen, Kanalindizes, neutrale Stichprobenklassen und technische Fehlercodes.

### Einschränkung – EPG-Daten fehlen in dieser Liste

Keiner der 257.468 Einträge enthält eine nichtleere `tvg-id`; auch der M3U-Header verweist auf keine EPG-Adresse. Damit fehlt der vorhandenen XMLTV-Zuordnung die Grundlage. EPG konnte mit dieser Liste nicht sinnvoll abgenommen werden. Dafür wäre eine separate XMLTV-Quelle mit passender ID-Zuordnung oder ein entsprechend gepflegter Anbieterexport nötig; die bisherigen synthetischen XMLTV-Tests bleiben davon unabhängig.

### Weiterhin beobachten – Speicher und Navigation großer Kataloge

Die vollständige Bibliothek benötigt rund 146 MiB nativen Heap. Die Anwendung blieb während der Stichprobe bedienbar; gemessene Main-Thread-Rundreisen lagen bei ca. 1–2 ms. Daraus folgt keine allgemeine Garantie für Geräte mit wenig RAM. Ein späterer Datenbank-/Lazy-Index und das Vermeiden paralleler vollständiger Kataloge wären sinnvolle nächste Optimierungen, wenn reale TV-Messungen Speicherprobleme zeigen.

Bei 392 Kategorien ist die D-Pad-Navigation lang; eine zusätzliche Kategoriensuche oder Favoriten wären eine sinnvolle Bedienungsverbesserung. Die bestehende globale Kanalsuche durchsucht bereits Kategoriebegriffe und reagierte in den Stichproben schnell. Das ist eine Verbesserungsempfehlung, kein im Test beobachteter Absturz.

## Reproduzierbarkeit und Nachweise

Abschlussprüfung: **41 Rust-/JNI-Tests, 3 Android-JVM-Tests und 3 synthetische Emulator-Tests bestanden**. Der separate private Test bestand zusätzlich in beiden Durchläufen. In der regulären Suite wird er erwartungsgemäß einmal übersprungen, solange keine private Liste angegeben wird. Rustfmt, Clippy mit `-D warnings`, Android-Lint (0 Fehler / 25 Warnungen), APK-/AAB-Release-Build und die 16-KB-/JNI-Prüfung aller drei ABIs sind erfolgreich. Die vier übernommenen Rust-Module sind weiterhin byteidentisch zum Windows-Source.

Auch das R8-optimierte APK von Version 0.1.1 bestand den separaten Release-Smoke-Test mit URL-Import, JNI und gerendertem Video über die Testquelle. Dieser Release-Test verwendet synthetische Inhalte; die privaten Messungen oben stammen aus dem Debug-Build. Ein mit einem eigenen Publisher-Schlüssel signiertes Release ist weiterhin ein separater Auslieferungsschritt.

```powershell
. ./scripts/env.ps1
./scripts/test-private-playlist.ps1 -Playlist 'C:\Pfad\private.m3u'
./scripts/build.ps1 -Release -ConnectedTests
./scripts/check-private-assets.ps1
```

Die private Prüfung ist opt-in. Ohne Playlist-Argument überspringt die reguläre Testsuite diesen Test; sie verbindet sich nicht mit dem Anbieter. Die Datei wird nicht in Test-APK oder Quellarchiv eingebettet. Tests mit privaten Daten sind auf den Emulator begrenzt.

- `artifacts/playlist-review/host-baseline.json`: anonymes Ergebnis des Ausgangsstands mit Importfehler.
- `host-after.json`: vollständige Parserstatistik nach der Korrektur.
- `device-first-pass.json`: erster Lauf mit 13 Quellen.
- `device-review.json`: erweiterter Lauf mit Wiedergabe-/Seek-/Lifecycle-Prüfung.
- `instrumentation.txt`: redigierte Testausgabe des erweiterten Laufs.
- `build-verification.txt`: vollständige Regression und Release-Build.

Nicht geprüft: sämtliche Anbieterstreams, mehrstündiger Betrieb, physisches TCL-Gerät, schwaches WLAN oder echte Router-Unterbrechungen. Automatische Recovery bei HTTP-Fehlern und abgebrochenem Response-Body wird zusätzlich von der synthetischen Emulator-Testsuite geprüft; der echte Anbieter wurde hier über manuelles Reconnect getestet.


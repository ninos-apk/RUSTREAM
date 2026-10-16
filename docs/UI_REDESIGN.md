# TV-Oberfläche 0.2.0

Die Hauptansicht besteht aus einer schmalen Kopfzeile, zwei kompakten Bibliothekslisten, dem Videobereich und einer Detailleiste. Import- und Verwaltungsaktionen sind über das Zahnrad erreichbar. Die untere Zeile enthält den gewünschten Hinweis „developed by Ninos Oshana“.

## Aufbau

- `TvUi.kt`: gemeinsame Farben, Flächen, animierter Fokus und skalierbare Canvas-Icons.
- `PlayerPane.kt`: eine Media3-Surface, eigenes Steuerungs-Overlay, Zeit/Progress, Tastennavigation, automatisches Ausblenden und Bildmodus. Der native Media3-Controller ist deaktiviert, damit keine zweite Steuerleiste erscheint.
- `ConnectionDetails.kt`: Pufferzeit, Zufluss, Medienallocator-Belegung, Auflösung, Status und Urheberhinweis.
- `PlaybackStats.kt`: Momentaufnahme der Playerdaten und ein threadsicherer Byte-Zähler für Netzwerktransfers. Die Rate wird aus real übertragenen Bytes und verstrichener Zeit berechnet.
- `MainActivity.kt`: Bereich/Kategorie/Suche, automatische Nachladung, Einstellungen und Wechsel zwischen Bibliothek und Vollbild.

Die Listen behalten RecyclerView-Virtualisierung und laden jeweils 64 weitere Einträge. Beim Anhängen bleiben bestehende Zeilen und ihr Fokus erhalten. Veraltete Such-/Importantworten werden anhand von Revision und Suchgeneration verworfen.

Im Vollbild wird derselbe PlayerPane direkt in den randlosen Root-Container versetzt. Der Player wird dabei nicht neu geladen. Kopfzeile, Bibliothek und Details sind verborgen; beim Zurückkehren bleiben Listenposition, Suchtext und Auswahl erhalten. „Bild füllen“ verwendet proportionalen Zoom, „Originalformat“ zeigt das vollständige Seitenverhältnis.

Die Netzwerkdaten beziehen sich auf vom Media3-DataSource übertragene Netzwerkbytes. Protokolle ohne solche Transfers, beispielsweise der separat angebundene RTSP-Transport, können keinen Zuflusswert liefern. Datenpuffer bezeichnet die tatsächlich allokierten Mediensegmente und ist weder ein Datenträgercache noch die gesamte Prozessspeichernutzung. Die Statusanzeige ist eine Bewertung von Wiedergabe und Puffer, kein WLAN- oder Internet-Speedtest.

## Abnahme

- 5 JVM-Tests bestanden, einschließlich Messung von Byte-Zufluss, Leerlauf und Streamwechsel.
- 5 reguläre Android-Instrumentierungstests bestanden; der optionale private Anbieter-Test wurde übersprungen. Zusätzliche Nachläufe prüfen die Vollbild-Overlay-Bedienung und das kompakte Einstellungsmenü.
- 140-Einträge-Fixture: automatische Nachladung über die Grenzen 64/128 hinweg, Suche und kompakte Zeilen geprüft.
- Reale Media3-Wiedergabe eines lokalen synthetischen Videos: Live/VOD-Wechsel, Pause/Seek, Verbindungsabbruch und Retry, Home/Rückkehr, Stopp und erneutes Starten bestanden.
- Vollbild im Google-TV-Emulator: Ursprung (0,0), Breite/Höhe identisch zur gesamten App-Fläche. OK öffnet die Steuerung, Pause bleibt bedienbar, Zurück schließt erst das Overlay und dann Vollbild.
- Debug-APK, R8-Release-APK und AAB für die drei vorhandenen ABIs gebaut. Lint ohne Fehler, APK-Ausrichtung und JNI-Exporte geprüft. Der Rust-Parser und die anderen übernommenen Module sind unverändert; Herkunftshashes stimmen.
- Separater Release-Smoke-Test: Import über Einstellungen → URL hinzufügen, JNI, D-Pad und Videowiedergabe bestanden.

MCP-Aufnahmen in 1920 × 1080: `artifacts/redesign-overview.png`, `redesign-fullscreen.png`, `redesign-fullscreen-controls.png` und `redesign-settings.png`. Gezeigt wird ausschließlich ein lokales Testvideo mit synthetischer Demo-Liste. Physische TV-Hardware und ihre Decoder wurden hier nicht geprüft.

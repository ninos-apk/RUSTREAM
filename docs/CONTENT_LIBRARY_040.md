# Filme und Serien: Android TV 0.4.0

Die Inhaltslogik aus Windows-Commit `17dd21f41fd8a802df236ef1ce9d2c1087fb034e` ist in den Android-Streaming-Parser und den persistenten TV-Katalog übertragen. Windows bleibt unverändert. Die TV-App verwendet weiterhin Kotlin-Views und Media3.

## Verhalten

- URL-Pfadsegmente gewinnen vor Typattributen. Bei widersprüchlichen Routen gilt Series → Movie → Live. Die bestätigte Live-Kurzroute `/user/password/numeric-id` hat ebenfalls Vorrang. Danach folgen Typattribute, Queryparameter, Kategorienwörter einschließlich arabischer Bezeichnungen, Episodenmarker, positive Dauer und Live-Transport.
- MP4/MKV ohne weitere Hinweise bleiben unter **Unzugeordnet** erreichbar. Der zusätzliche Bereich erscheint nur bei vorhandenen Einträgen. Direkt importierte endliche HLS-Manifeste bleiben Filme.
- Anzeigename, unabhängiger TVG-Name, Originalkategorie, Attribute, URL-Pfad, Endung, Jahr, Qualität und Episodenmetadaten bleiben erhalten. Absolute Streamadressen werden nicht neu serialisiert. Nur relative Adressen werden aufgelöst.
- Filme erscheinen als virtualisiertes Poster-Raster mit Titel, Jahr und Qualität. HD-/4K-Versionen bleiben unabhängige Einträge mit ihren ursprünglichen Adressen.
- M3U-Serien werden nach normalisiertem Titel gruppiert. Unterstützt werden `S01 E05`, `S01E05`, `S1 E5`, `Season 1 Episode 5` und `1x05`. SUB-/DUB-Labels und Unicode bleiben erhalten. Kategorien sind Sammlungen, keine Serientitel.
- Die Serienbibliothek zeigt eine Karte pro Serie. Serien können mehreren Kategorien angehören; die Bereichszahl zählt global eindeutige Serien, Kategorieanzeigen die eindeutigen Serien der jeweiligen Kategorie.
- Staffeln und Episoden werden numerisch sortiert. Varianten mit gleicher Episodennummer bleiben erhalten. Fehlende Nummern werden nicht erfunden; der unbekannte Staffelbereich heißt **Ohne Staffelnummer**.
- Eine Suche nach Episodenmetadaten ergibt passende Serienkarten. Deduplizierung geschieht vor dem Paging. Alle Suchbegriffe müssen denselben Eintrag treffen. Nach dem Öffnen sind sämtliche Staffeln der gefundenen Serie verfügbar.
- Xtream-Serien verwenden dieselbe Detailansicht. Ihre Anbieter-ID bleibt die Identität; gleichnamige unterschiedliche IDs werden nicht zusammengelegt. Episodendetails werden bei Bedarf geladen und weiterhin transaktional gespeichert.

## TV-Bedienung und Darstellung

Live behält Kategorien, schmale Senderliste und großen Player. Movies und Series verwenden ein adaptives Raster mit kleineren Posterkarten über die gesamte verfügbare Breite; Player und Verbindungsdetails sind dort auch bei laufender Wiedergabe ausgeblendet. Eine geöffnete Serie zeigt Staffelauswahl und kompakte Episoden links neben dem Player. Eine Filmkarte öffnet eine eigene Ansicht mit Cover, Titel, Jahr/Qualität und Play-Button neben dem Player. Eine erneute Kategorieauswahl führt zur Bibliothek zurück, auch bei derselben Kategorie.

OK auf einer Film- oder Serienkarte öffnet die Details und startet keinen Stream. Erst der Play-Button eines Films oder OK auf einer Episode ersetzt die Wiedergabe. Beim Browsen läuft die bisherige Wiedergabe weiter. Channel +/− verwendet den tatsächlichen Wiedergabekontext: Senderliste, Filmergebnisse oder gewählte Staffel; ein inzwischen geöffneter anderer Bereich ändert diesen Kontext nicht.

Zurück schließt zunächst sichtbare Playercontrols, verlässt Vollbild, führt vom Player zur Liste bzw. aus Seriendetails zur Bibliothek und beendet erst auf der obersten Ebene die App. Vollbild hängt dieselbe PlayerPane um; dafür ist kein neuer Load nötig. Staffel und Listenfokus bleiben erhalten. Bei Rückkehr ins Serienraster wird die passende Ergebnisseite neu geladen, statt eine zweite vollständige Liste aufzubewahren.

Im Raster navigiert Rechts zur nächsten Karte; in Details und Live bleibt der Player erreichbar. Die Toolbar lässt sich horizontal navigieren und unterstützt auch den vierten Bereich und große Zähler. Vollständige lange Titel sind mit Info bzw. langem Druck erreichbar und als zugänglicher Name vorhanden. TextViews verwenden native Unicode-/Bidi-Darstellung; es gibt keine manuelle Zeichenumkehr und keinen Windows-GDI-Port.

„Zuletzt gesehen“, Favoriten und dauerhaftes Fortsetzen waren in 0.4.0 noch nicht enthalten. Sie folgen in [0.4.2](USER_LIBRARY_042.md).

## Katalog und Upgrade

Schema 2 speichert abspielbare Einträge getrennt von Serienidentitäten und Episodenmitgliedschaften. Bibliothekskarten werden über `browse`, Details über `series_page` und gezielt angeforderte Bildadressen über `artwork` geliefert. `search` bleibt als rohe Eintragsabfrage kompatibel. `adjacent` bestimmt den benachbarten Eintrag im gespeicherten Wiedergabekontext. IDs gelten nur innerhalb ihrer Revision.

Streamadressen, Header und sensible Originalmetadaten bleiben authentifiziert verschlüsselte Records. Listen-DTOs enthalten keine Streamadressen. Bildadressen werden nur gezielt angefordert. Die bestehenden Importgrenzen und maximal 128 DTOs pro Antwort bleiben erhalten; die UI hält höchstens 512 Eintragszeilen. Staffelgruppen besitzen ebenfalls paginierte Antworten. „Alle Staffeln“ und „Ohne Staffelnummer“ haben unterschiedliche API-Filter.

Schema-1-Kataloge bleiben für den Offline-Übergang lesbar. Sie enthalten nicht alle Quelldaten für eine zuverlässige neue Klassifikation. Deshalb wird bei verfügbarer Quelle eine neue Generation vollständig aufgebaut. Alte HTTP-Validatoren werden beim notwendigen Neuaufbau nicht gesendet; ein 304 könnte die fehlenden Metadaten nicht liefern. Eine unveränderte lokale Datei wird ebenfalls erneut gelesen. Nach erfolgreichem Aufbau und Validierung wird das Manifest gespeichert, anschließend aktiviert. Fehler erhalten die bisherige aktive Bibliothek und die vorherige Generation.

## Cover

Zwei Hintergrundworker verwenden OkHttp und einen bytebegrenzten 16-MiB-Bitmapcache. Warteschlange: 48 Aufträge; Antwortlimit: 8 MiB; Gesamtzeit: 20 Sekunden; Fehlerversuche pausieren 60 Sekunden. Dimensionen werden vor der eigentlichen Dekodierung geprüft und durch Sampling begrenzt. PNG/JPEG/WebP/BMP werden anhand des Inhalts erkannt. Es gibt keine Bildpersistenz auf Disk. Fehlende Bilder erhalten einen skalierbaren Platzhalter. View-Recycling und Revisionsschlüssel verhindern, dass alte Ergebnisse neue Karten überschreiben.

Der Import selbst lädt keine Cover oder Providerstreams. Die visuellen Tests erzeugen ihre Poster lokal und verwenden ausschließlich synthetische Medien.

## Build-Konfiguration

Der während der Umsetzung durch Android Studio aktualisierte Stand AGP 9.4.1 / Gradle 9.6 bleibt erhalten. `scripts/env.ps1` berücksichtigt das ausgewählte lokale IDE-JDK, wenn es vorhanden ist. Ein vorhandenes projektlokales NDK wird auch für Gradle verwendet. `scripts/build-content-local.ps1` baut bei Bedarf mit dem vorhandenen Projekt-SDK und stellt die ursprüngliche `local.properties` anschließend wieder her. `-Online` erlaubt das erstmalige Nachladen neuer Gradle-Testabhängigkeiten. Die vorhandenen AGP-Kompatibilitätsoptionen bleiben bestehen; deren Deprecation-Warnungen sind keine unterdrückten Prüffehler.

## Prüfung

- 68 Rust-/JNI-Tests bestanden, darunter fünf unverändert bis auf den Crate-Namen übernommene Windows-Inhaltsbibliothekstests.
- Zusätzlicher Release-Lasttest mit 1.000.001 Einträgen und einer Quelldatei über 128 MiB bestanden; etwa 20,3 Sekunden inklusive Fixture-Erzeugung, Import und Prüfung auf dem Host.
- Vollständiger lokaler Referenzvergleich: 257.468 Einträge, kein übersprungener Eintrag; alle Originalfelder nach Katalog-Roundtrip geprüft. 230.628 Serienepisoden vollständig zugeordnet, 7.996 Serienobjekte, 19.995 Filme, 6.845 Live-Einträge und 392 Kategorien. Die Klassifikationsänderung erklärt die Abweichung von früheren Bereichszahlen.
- Referenz-Katalogaufbau auf dem Host etwa 6,9 Sekunden; erneutes Öffnen etwa 2,1–3,8 ms. Gemessene Bibliotheksabfragen lagen bei etwa 0,5–584 ms. Dies sind Hostwerte, keine TV-Hardwaremessungen.
- Details: `artifacts/content-catalog-benchmark.json`, `content-million-test.log` und `content-clippy.log`.

Die abschließende Gesamtabnahme `scripts/build.ps1 -Release -ConnectedTests` und die zusätzliche vollständige Release-Lintprüfung sind bestanden. 15 JVM-Tests und 20 Instrumentierungstests bestanden auf Google TV / API 36 mit 16-KiB-Seiten. Drei optionale Tests benötigen separat bereitgestellte private Benchmark-/Review-/Preview-Daten und beenden sich ohne diese mit einer erwarteten `AssumptionViolatedException`. Der Runner meldet 0 Testfehler und Gradle einen erfolgreichen Build; die HTML-/XML-Ausgabe dieses AGP-Stands führt diese drei Annahmeabbrüche unter `failures` auf. Es sind dort ausschließlich diese optionalen Fixtures betroffen.

Debug- und Release-Lint: jeweils 0 Fehler und 60 Warnungen, vor allem Übersetzungsressourcen, KTX-/Adapterhinweise, Versionshinweise und vorhandene programmatisch erzeugte Views. Keine Prüfregeln wurden abgeschaltet. Debug-APK, R8-Release-APK und AAB für ARM64/ARMv7/x86_64 gebaut; Herkunft, private Assets, JNI-Exporte und ELF-/APK-Ausrichtung auf 16 KiB geprüft. Die Abnahme auf realer TV-Hardware und mit einem Screenreader bleibt separat.

Die R8-APK wurde zusätzlich mit dem lokalen Debug-Testschlüssel signiert, installiert und geprüft: Start, Import über JNI, Filmkarte → Details ohne Streamstart, explizites Play, tatsächliche Media3-Wiedergabe und Vollbild bestanden. Damit sind 103 reguläre Tests plus der gesonderte Millioneneinträge-Test und der Release-Smoke-Test erfolgreich. Die installierbare Testfassung liegt unter `artifacts/iptv-tv-0.4.0.apk`; `iptv-tv-release-unsigned.aab` bleibt für eine spätere eigene Distributionssignatur unsigniert. Quellen: `iptv-tv-source-0.4.0.zip`.

## Reproduzierbare Abnahme und Screenshots

`scripts/build.ps1 -Release -ConnectedTests` verwendet die normale Projektkonfiguration einschließlich der Android-Studio-SDK-Auswahl und prüft Rust, JVM, Lint, Emulator, Debug, R8-Release, AAB, native ABIs, Herkunft und private Test-Assets. Zusätzlich werden `gradlew.bat lintRelease` und `scripts/test-release.ps1` ausgeführt. Protokolle: `artifacts/content-build-final.log`, `content-lint-release.log` und `content-release-smoke-final.log`.

Die neuen Instrumentierungstests prüfen M3U-/Xtream-Seriennavigation, verzögerten Episodenabruf, Filmöffnung ohne Streamstart, explizites Play, versteckten Player im Raster trotz laufender Wiedergabe, Wiedergabekontext, Staffelwechsel, Vollbild ohne Playeraustausch, Rücksprungfokus, Bilderkennung/View-Recycling und Schema-1-Upgrade. Der visuelle Test verwendet lokal erzeugte Poster und synthetisches Video; vor der Aufnahme prüft er Posterformat, sichtbare Kartentitel bzw. einen tatsächlichen Videoframe nach dem Vollbildwechsel. Bei großer Schrift entfällt das kleine Seriencover und die Kopfzeile wird verkürzt, damit Episoden weiterhin Platz haben.

Die Fernbedienungstests warten auf konkrete Zustände und senden echte D-Pad-Ereignisse. Globale UI-Ruhe ist bei laufenden Videoframes und Sekundentelemetrie kein geeigneter Bereitschaftstest. Activities werden vor dem globalen `ActivityScenario.close` auf dem Main-Thread beendet; die Freigabe der Activity wird geprüft. Damit bleibt die Teststeuerung auf API 36 begrenzt und reproduzierbar.

Zwölf Screenshots unter `artifacts/content-screenshots/`: Live, Filmraster, Filmdetails, Serienraster, Seriendetails und Vollbild jeweils mit Schriftfaktor 1 und 2. Die Testkopie in `/sdcard/Download/iptv-content-screenshots` übersteht das automatische Deinstallieren durch UTP.

| Ansicht | Normale Schrift | Doppelte Schrift |
| --- | --- | --- |
| Filme | [Raster](../artifacts/content-screenshots/normal-movies-grid.png), [Details](../artifacts/content-screenshots/normal-movie-details.png) | [Raster](../artifacts/content-screenshots/large-text-movies-grid.png), [Details](../artifacts/content-screenshots/large-text-movie-details.png) |
| Serien | [Raster](../artifacts/content-screenshots/normal-series-grid.png), [Details](../artifacts/content-screenshots/normal-series-details.png) | [Raster](../artifacts/content-screenshots/large-text-series-grid.png), [Details](../artifacts/content-screenshots/large-text-series-details.png) |
| Wiedergabe | [Live](../artifacts/content-screenshots/normal-live.png), [Vollbild](../artifacts/content-screenshots/normal-fullscreen.png) | [Live](../artifacts/content-screenshots/large-text-live.png), [Vollbild](../artifacts/content-screenshots/large-text-fullscreen.png) |

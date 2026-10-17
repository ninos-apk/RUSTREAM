# UI-Umsetzung 0.3.1

16. Oktober 2026 · versionCode 7 · native Android-TV-Oberfläche.

Der [Interface-Reviewplan](INTERFACE_REVIEW_PLAN.md) einschließlich der gewünschten Ergänzungen ist umgesetzt. Ausgangspunkt der Umsetzung war der inzwischen vorhandene Stand 0.3.0. Verschlüsselter SQLite-Katalog, Wiederherstellung der letzten Quelle, begrenztes RecyclerView-Fenster und Xtream-Staffel-/Episodenauswahl bleiben erhalten. Der ursprüngliche Review beschrieb 0.2.1.

## Sichtbare Änderungen

| Bereich / Befund | Umsetzung |
| --- | --- |
| Start, F06 | Eigene dunkle Startfläche mit IPTV-Logo, dezentem Einblenden und funktionaler Ladeanzeige. Die Phasen stammen aus dem tatsächlichen Ladevorgang. Erst nach dem ersten Listenlayout erscheint die Bibliothek; EPG lädt anschließend. Ohne Quelle erscheint „Quelle hinzufügen“, bei Startfehlern Wiederholung und Quellenauswahl. |
| Videofokus, F01 | Dauerhafter kontrastreicher Rahmen über der Videofläche, solange Vorschau oder Steuerung Fokus haben. Bei fokussiertem Bild bleibt „OK: Vollbild · ↓ Steuerung“ sichtbar. |
| Fernbedienung, F02 / F07 | Rechts aus der Liste führt zum Bild; Links und Zurück führen zurück zur vorherigen Zeile. Erneutes OK auf dem laufenden Eintrag öffnet Vollbild mit derselben Playerinstanz. Stopp, Ende und Fehler erlauben einen Neustart. |
| Refresh, Ergänzung | Normaler Textbutton unter den Statistiken, aus dem Video entfernt. Er verbindet die ausgewählte Wiedergabe neu; während der Aktion bleibt er deaktiviert. Ohne Auswahl ist er ebenfalls deaktiviert. |
| Sprungsteuerung, Ergänzung | Größere 30-Sekunden-Symbole, separate −30-s-/+30-s-Beschriftungen und Fokusbeschreibung. Play/Pause, Stopp und Vollbild bleiben direkt erreichbar. |
| Schrift und Layout, F03 | Inhaltsabhängige Texthöhen, breitere Bibliotheksspalten bei großer Systemschrift, scrollbare Einstellungen und Programmdetails. Statistikwerte sind horizontal scrollbar. Keine künstliche Verkleinerung der Systemschrift. |
| Quellen entfernen, F04 | Bestätigung nennt Quelle und Verlust gespeicherter Anmeldung/Katalogdaten. „Abbrechen“ erhält zuerst Fokus. |
| Fehler und Leerzustände, F05 / F09 | Konkrete nächste Schritte, Wiederholung bzw. Quellenauswahl; getrennte Zustände für fehlende Quelle, Ladefehler und erfolglose Suche. „Suche löschen“ hebt einen ergebnislosen Suchfilter auf. |
| Formulare, F10 | Dauerhaft sichtbare Feldbezeichnungen, URL-/Pflichtfeldprüfung und Fehler am Feld. Eingaben bleiben bis zum erfolgreichen asynchronen Import erhalten. |
| Auswahlsemantik, F11 | Native Selected-Semantik für Bereiche und Zeilen; laufender Eintrag als „Wird wiedergegeben“. Fokus und aktive Auswahl bleiben getrennt. |
| Wiederstart, F08 | Die bereits in 0.3.0 vorhandene Speicherung und Wiederherstellung der letzten Quelle bleibt erhalten. |
| Bewegung | Keine Skalierung bei jedem Listenfokus. Logo und Overlay berücksichtigen ausgeschaltete Systemanimationen. Bei deaktivierten Animationen wird der fokussierte lange Listenname umgebrochen statt als Laufschrift gezeigt. |

## Spulen im Vollbild

Voraussetzung ist eine seekbare, nicht als Live erkannte Wiedergabe bei fokussierter Videofläche. Ein kurzer Links-/Rechts-Druck springt einmal 30 Sekunden zurück/vor. Ab 400 ms Halten beginnt zusätzlich ein zeitgesteuerter Ablauf: 30 Mediensekunden pro realer Sekunde, mit einer Zunahme um 20 Mediensekunden pro Sekunde je weiterer Sekunde bis zum Maximum von 180. Die maximale Geschwindigkeit wird nach 7,5 Sekunden Beschleunigung erreicht. Die Entfernung hängt nicht von der Tastenwiederholrate der Fernbedienung ab.

Anfang und bekanntes Ende begrenzen das Ziel. Loslassen beendet die Wiederholung; Richtung wechseln setzt die Beschleunigung zurück. Fokusverlust, Zurück, Inhaltswechsel, Home und Activity-Ende brechen den Ablauf ab. Der kurze erste Sprung besitzt einen getrennten Auftrag, damit eine schnelle Key-up-Nachricht ihn nicht vor dem Core-Aufruf verwirft. Eine bewusste Pause bleibt beim Spulen erhalten.

Sind die Steuerungsbuttons fokussiert, navigieren Links/Rechts durch die Buttons. Runter öffnet die Steuerung; im Vollbild auch OK. Zurück schließt zuerst die Steuerung, danach Vollbild und kehrt schließlich zur Liste zurück. Bei laufender Wiedergabe verschwindet die Steuerung nach vier Sekunden ohne Eingabe; bei Pause bleibt sie sichtbar.

## Prüfnachweise

Getestet auf einem eigens angelegten Google-TV-Emulator API 36, 1920 × 1080, 320 dpi (`emulator-5556`). Ausschließlich synthetische lokale Quellen und Videos wurden verwendet.

| Prüfung | Ergebnis / Beleg |
| --- | --- |
| Debug, R8-Release, AAB | Erfolgreich gebaut; `artifacts/ui-implementation/build.log`. |
| R8-Laufzeitprüfung | Optimierte Release-APK mit separater lokaler Testsignatur installiert: Start, normale URL-Eingabe, JNI-Import, D-Pad und echte Media3-Wiedergabe bestanden. `artifacts/ui-implementation/release-smoke.log`, Screenshot `artifacts/tv-release.png`. Die Testeingabe schließt die TV-Tastatur zwischen den Feldern, bevor das nächste Feld fokussiert wird. |
| JVM | 15 Tests, 0 Fehler. Darunter fünf Tests zu einmaligem Sprung, Beschleunigung unabhängig vom Abfrageintervall, Richtungswechsel, Grenzen und Abbruch. XML-Berichte in `app/build/test-results/testDebugUnitTest/`. |
| Lint | 0 Fehler, 47 Warnungen; `app/build/reports/lint-results-debug.txt`. Vor allem bestehende Stil-/API-/Textwarnungen; keine vollständige Bereinigung behauptet. |
| Geräteintegration | 14 Tests bestanden: `UiDesignTest`, `TvEndToEndTest`, `CatalogIntegrationTest`. Start/Validierung, dauerhafter Fokus, identische Playerinstanz beim Vollbildwechsel, Refresh, Löschabbruch, D-Pad-Spulen, Suche, Paging und Katalogregressionen. `artifacts/ui-implementation/device-tests.log`. |
| Echter Decoder / Spulen | Zusätzlicher opt-in-Test `UiPreviewTest` mit fünfminütigem lokalem Film bestanden. Gemessen: kurzer Druck **30.000 ms**, 2,5 s Halten **123.236 ms** zusätzliche Strecke. Position nach Loslassen stabil, Pause erhalten. `artifacts/ui-implementation/real-seek-test.log`. |
| Große Schrift | Start, Bibliothek und Einstellungen mit Schriftfaktor 2,0 und deaktivierten Animationen visuell geprüft. Letzter Menüpunkt per D-Pad erreichbar. `font-200.png`, `font-200-library.png`, `font-200-settings.png`, `font-200-settings-bottom.png` unter `artifacts/ui-implementation/`. |
| Native Pakete | APK-ZIP-Ausrichtung und ELF-LOAD-Ausrichtung mindestens 16 KiB sowie JNI-Exporte für ARM64, ARMv7 und x86_64 geprüft. `artifacts/ui-implementation/package-check.log`. |
| Testdaten | Test-APK enthält ausschließlich das freigegebene synthetische Video; keine privaten Anbieterlisten. `artifacts/ui-implementation/private-assets-check.log`. |

Die Zusatzprüfung mit echtem Decoder ist absichtlich opt-in und benötigt einen lokalen Anbieter sowie einen leeren Test-App-Zustand. Der Test hinterlässt die synthetische Quelle für die Sichtprüfung. Der reguläre Geräte-Testbestand benötigt diesen Demo-Server nicht. Nachtrag: Inzwischen startet `scripts/test-ui-preview.ps1` den Anbieter und führt den Test mit einem Befehl aus. Der Test leert Quellen und Nutzerbibliothek selbst und stellt beide danach wieder her (siehe README, Abschnitt Tests).

## Dateien und Abnahmegrenzen

Installierbare optimierte APK: `artifacts/iptv-tv-0.3.1.apk`, identisch mit der geprüften `iptv-tv-release-test.apk`. Sie ist mit dem lokalen Android-Debug-Schlüssel für die Geräteprüfung signiert. Für eine Veröffentlichung ist der eigene Publisher-Schlüssel erforderlich. Zusätzlich aktualisiert: `artifacts/iptv-tv-debug.apk`, `artifacts/iptv-tv-release-unsigned.aab` und `artifacts/iptv-tv-source-0.3.1.zip`. Prüfsummen stehen in `artifacts/ui-implementation/SHA256SUMS.txt`.

Startansicht: `StartupPane.kt`; Playerfokus und D-Pad: `PlayerPane.kt`; zeitbasierte Spullogik: `SeekHold.kt`; serialisierter absoluter Seek: `PlaybackController.kt`; App-Zustände, Liste und Dialoge: `MainActivity.kt`; Statistikbereich: `ConnectionDetails.kt`; gemeinsame Komponenten: `TvUi.kt`; Fehlertexte: `ImportDiagnostics.kt`.

Ein echter Fernseher mit seiner Fernbedienung, andere Android-Versionen, TalkBack, RTL, 720p/4K, Netzwerkunterbrechungen während langen Spulens und sämtliche Anbieter-/Codecvarianten sind noch nicht abgenommen. Dies ist keine vollständige WCAG-Konformitätsprüfung. Die Geräteabnahme aus `DEVICE_ACCEPTANCE.md` bleibt erforderlich.

# Kompakter Serienbereich: Layout-Abnahme

18. Oktober 2026. Umfang: ausschließlich der Serienkopf und die Episodenliste zwischen Kategorien und Player. Die vorhandenen TvUi-Farben, Fokuszustände und Playerbuttons werden weiterverwendet. Die erneute Überarbeitung entfernt die Bibliotheks- und Fortsetzen-Buttons sowie doppelte Episodeninformationen.

**Group with space / Order by importance**

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM, behoben | app/src/main/java/de/iptv/tv/MainActivity.kt:307 | Bibliotheks-Button, separate Fortsetzen-Aktion und zusätzliche Fortschrittszeile wiederholten die Möglichkeiten von Kategorien und Episodenliste. | Der Kopf enthält nur Titel, Zusammenfassung und Favorit. Darunter bleibt die feste Episodenüberschrift mit Staffel-Dropdown. | Die primäre Handlung ist der Episodenklick. Weniger Kopfzeilen lassen mehr vollständige Episoden sichtbar. |

**Align to shared edges / Keep controls distinct from content**

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM, behoben | app/src/main/java/de/iptv/tv/MainActivity.kt:1570 | Eine Nummer wie 02, der Titel, Staffel 1 und S01 E02 verteilten dieselbe Identifikation auf mehrere Stellen. | Ein Episodencode wie S01 E02 führt die Zeile an, daneben steht der Name. Eine kleine Zeitzeile erscheint nur bei vorhandenem Fortschritt bzw. aktueller Wiedergabe; ungesehene Episoden benötigen keine zweite Textzeile. | Identifikation und Titel lassen sich schneller lesen. Ungesehene Episoden nutzen die vorhandene kompakte Mindesthöhe von 36 dp. |
| MEDIUM, behoben | app/src/main/java/de/iptv/tv/MainActivity.kt:1608; app/src/main/java/de/iptv/tv/MainActivity.kt:560 | Ohne die separate Fortsetzen-Aktion wäre die zuletzt pausierte Episode nach einem Neustart nicht mehr eigens hervorgehoben gewesen. Ein Klick auf eine bereits geladene, pausierte Episode öffnete bisher nur das pausierte Vollbild. | Die letzte nicht abgeschlossene Episode bleibt in ihrer Zeile markiert; ihre gespeicherte Position steht unter dem Namen. Ein Klick setzt sie fort, auch nach einem Neustart. Bei einem noch geladenen Player wird die Pause ohne Neuladen aufgehoben. | Der Wiedereinstieg bleibt direkt in der Liste erkennbar und bedienbar. |

**Plan for growth and clipping / Logical properties for anything that mirrors**

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| HIGH, behoben | app/src/main/java/de/iptv/tv/SeriesLayout.kt:10 | Bei gleichzeitig 320 physischen Pixeln Panelbreite und 200 % Schrift ließ der Code zu wenig Breite für den Namen; die Zeile wurde höher als der sichtbare Listenbereich. | Code und Name werden bei Platzmangel untereinander angeordnet. Lange Titel können weiterhin im begrenzten Kopf scrollen; Staffelwahl und Episodenliste bleiben darunter. | Die gespeicherte Zeit und der Name bleiben auch in der schmalen Konfiguration erreichbar. |
| MEDIUM, behoben | app/src/main/java/de/iptv/tv/MainActivity.kt:322 | Nach dem Umbruch im schmalen Panel führte geometrische D-Pad-Suche vom Staffel-Dropdown nicht zuverlässig zurück zur zuvor fokussierten Episode. | Runter am Staffel-Dropdown verwendet den bestehenden Rückweg zur gemerkten Episodenzeile. | Die angepasste Zeilenhöhe verändert den Navigationsweg nicht. |
| LOW, behoben | app/src/main/java/de/iptv/tv/MainActivity.kt:1580 | Der alte adaptive Fortsetzen-Button wurde nur für eine nun entfernte Kopfaktion benötigt. | Der zugehörige View und seine Handler entfallen. Relative Abstände spiegeln die Episodenzeilen; Codes und Zeiten behalten ihre Leserichtung. | Die Struktur enthält weniger Sonderfälle. Vollständige Anbieternamen bleiben über Info bzw. langes OK erreichbar. |

Die Prüfung verwendet synthetische lokale Inhalte, keine privaten Playlists. SeriesLayoutTest prüft sieben Konfigurationen: Englisch, Deutsch, gespiegelter Serienbereich, doppelte Schrift mit langem deutschen Titel, 320 physische Pixel Panelbreite sowie RTL und 320 Pixel jeweils zusätzlich mit doppelter Schrift. Er prüft außerdem mindestens sechs vollständig sichtbare Episoden bei normaler Schrift, 36-dp-Zeilen ohne Fortschritt, gespeicherte Pause, D-Pad-Navigation, Staffelwahl, Scrollhinweis und Rückkehr über die Kategorien.

UserLibraryUiTest pausiert eine tatsächlich abgespielte Episode und setzt sie per Episodenklick ohne neuen Player fort. Anschließend startet er die Activity neu und setzt dieselbe Episode über ihre Zeile an der gespeicherten Stelle fort. ContentLibraryTest, ContentVisualTest und SeriesPlaybackControlsTest prüfen zusätzlich M3U/Xtream, Wiedergabe, große Schrift sowie die bestehenden Vorherige-/Nächste-Episode-Buttons in Vorschau und Vollbild.

| Verification | Ergebnis |
| --- | --- |
| Android Debug-APK und Test-APK | Bestanden |
| 51 Kotlin-Unit-Tests | Bestanden |
| lintDebug | Bestanden; 0 Fehler |
| TV-Emulator, 1920 × 1080, 320 dpi | Bestanden |
| Sieben Layoutkonfigurationen einschließlich RTL, 320 px und 200 % Schrift | Bestanden; erneute vollständige Layoutprüfung nach Ausrichtung der Pausezeit |
| Sechs Android-Tests einschließlich Fortsetzen nach Neustart und Kategorienrückweg | Bestanden |
| Echter Fernseher, andere physische Auflösungen | Not verified |
| Automatische Pseudo-Lokalisierung | Not verified; langer deutscher Titel separat geprüft |
| Browser-Zoom / mobile Browser-Viewports | Nicht anwendbar: native Android-TV-Oberfläche |

Lokale Screenshots und Protokolle liegen unter artifacts/series-compact-*. Die aktualisierte Debug-Testfassung heißt artifacts/RUSTREAM-IPTV-series-compact.apk. Die Änderungen gehören zu Version 0.5.5 (VersionCode 16); das Tag v0.5.5 startet die vorhandene Development-Prerelease-Automation.

Nachweise: artifacts/series-compact-build.log, artifacts/series-compact-device-verified.log (sechs Tests) und artifacts/series-compact-layout-verified.log (abschließender Layouttest mit sieben Konfigurationen). Die Pausezeit folgt der Ausrichtung des Episodennamens, während Codes und Zeiten ihre LTR-Leserichtung behalten.

Keine offenen HIGH-Befunde im geprüften Serienbereich. Die Freigabe gilt ausschließlich für die oben geprüften Konfigurationen.

Approve

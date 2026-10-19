# Filmansicht analog zum Serienkopf

19. Oktober 2026. Umfang: die Film-Detailspalte zwischen Kategorien und Player. Die vorhandenen TvUi-Abstände, Farben und Fokuszustände bleiben die Grundlage.

**Order by importance / Keep controls distinct from content**

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM, behoben | app/src/main/java/de/iptv/tv/MainActivity.kt:333; app/src/main/java/de/iptv/tv/MainActivity.kt:704 | Der Bibliotheks-Zurückbutton stand vor der Filminformation; die Favoritenaktion erschien als weitere volle Textzeile unter den Wiedergabebuttons. | Titel und ein umschaltbarer Stern bilden wie bei Serien die erste Zeile. Der Stern ist bei einem Favoriten gefüllt und in der vorhandenen Akzentfarbe dargestellt; seine zugängliche Beschriftung nennt Hinzufügen bzw. Entfernen. Der redundante Zurückbutton entfällt. | Die Identifikation führt die Ansicht an; die sekundäre Favoritenaktion bleibt am Titel erkennbar und erreichbar. Zurück auf der Fernbedienung und die Kategorien führen weiterhin zur Bibliothek. |

**Align to shared edges / Group with space / Plan for growth and clipping**

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| LOW, behoben | app/src/main/java/de/iptv/tv/MainActivity.kt:333; app/src/main/java/de/iptv/tv/MainActivity.kt:351 | Titel und Fortschritt verwendeten zusätzliche eigene Einrückungen. Die Favoritenaktion benötigte eine weitere volle Zeile. | Ein gemeinsamer relativer Innenabstand richtet Titel, Fortschritt und Wiedergabe aus. Das Cover bleibt darunter mit Abstand sichtbar. Nach den vorhandenen Wiedergabeaktionen bleibt die Spalte leer. Ein dauerhafter Scrollbalken zeigt bei Überlänge weiteren Inhalt an. | Die zusammengehörigen Inhalte teilen dieselben Kanten; der verbleibende Platz bleibt für spätere Inhalte frei. Titel und Buttons dürfen in der Höhe wachsen. |

Statische Prüfung: Ansichtsreihenfolge entspricht der sichtbaren Reihenfolge. Der Titel nutzt eine gewichtete Breite und keine Zeilenbegrenzung. Relative Innenabstände, marginStart und TEXT_ALIGNMENT_TEXT_START unterstützen die Spiegelung; das symmetrische Sternsymbol benötigt keine Spiegelung. Nur das Bild hat eine feste Höhe. Der Stern hat reduzierte horizontale Innenabstände, damit sein Glyph auch bei 200 % Schrift in die bestehende 36-dp-Breite passt. Der Film-Scrollbereich beginnt beim Öffnen wieder oben.

| Verification | Ergebnis |
| --- | --- |
| assembleDebug / assembleDebugAndroidTest | Bestanden |
| 53 Kotlin-Unit-Tests | Bestanden, keine Fehler |
| lintDebug | Bestanden, 0 Fehler / 71 Warnungen |
| Vier bestehende Android-Tests: ContentVisualTest, UserLibraryUiTest und beide ContentLibraryTest-Fälle | Bestanden; Wiedergabe, Favorit hinzufügen/entfernen, Fortschritt und Fortsetzen nach Neustart, M3U/Xtream und Bibliotheksrückweg |
| TV-Emulator, 1920 × 1080, 320 dpi, normale und 200 % Systemschrift | Screenshots der Filmansicht visuell geprüft; Titel, Stern, Cover und Wiedergabe sichtbar, unten freier Platz |
| RTL | Relative Anordnung statisch geprüft; visuelle Spiegelung: Not verified |
| 320 px Panelbreite, zusätzliche Auflösungen, lange deutsche Filmtitel und automatische Pseudo-Lokalisierung | Not verified |
| Echter Fernseher | Not verified |
| Browser-Zoom / mobile Browser-Viewports | Nicht anwendbar: native Android-TV-Oberfläche |

Der lokale Java-Teststart erforderte das Entfernen eines vorhandenen Anführungszeichens aus PATH ausschließlich im Build-Prozess. Systemeinstellungen und Projektkonfiguration wurden dafür nicht verändert. Die beiden bisherigen Textselektoren im UserLibraryUiTest verwenden jetzt die zugängliche Sternbeschriftung.

Nachweise: artifacts/movie-layout-build.log, artifacts/movie-layout-test-build.log, artifacts/movie-layout-device.log, artifacts/movie-layout-normal.png und artifacts/movie-layout-large-text.png. Installierbare Debug-Testfassung: artifacts/RUSTREAM-IPTV-movie-layout.apk. Version und Veröffentlichung wurden nicht verändert.

Keine offenen HIGH-Befunde im geprüften Umfang. Die Freigabe gilt für die oben ausdrücklich geprüften Konfigurationen.

Approve

# Serienbereich: Layout-Abnahme

18. Oktober 2026. Umfang: ausschließlich die geöffnete Serienansicht zwischen Kategorien und Player, entsprechend der markierten TV-Aufnahme und der bereitgestellten Figma-Bildreferenz. Die vorhandenen `TvUi`-Farben, Fokuszustände und Wiedergabeaktionen werden weiterverwendet.

**Group with space / Order by importance**

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM, behoben | `app/src/main/java/de/iptv/tv/MainActivity.kt:306` | Großes Cover, Titel/Zählwerte und mehrzeilige Fortsetzen-Aktion belegten einen Großteil der Spalte. | Titel und Favorit bilden eine Gruppe; Zählwerte stehen darunter. Fortsetzen und Episodencode haben eigene Plätze, der Fortschritt eine separate Zeile. | Zusammengehörige Informationen sind erkennbar, der Serienkopf wiederholt keine Episodentitel. |

**Align to shared edges**

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM, behoben | `app/src/main/java/de/iptv/tv/MainActivity.kt:1573` | Cover, Episodennummer, Anbietername mit wiederholtem Episodencode und Zeit konkurrierten in einer Zeile. | Eigener Zeilentyp nur für Episoden: Nummer/Play/Pause, Titel sowie darunter Status und Staffelepisodencode. Wiederholte Cover entfallen. | Gemeinsame Kanten und getrennte Informationsspalten erleichtern das Scannen. Der vollständige Anbietername bleibt über Info bzw. langes OK erreichbar. |

**Plan for growth and clipping / Hint at hidden content**

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM, behoben | `app/src/main/java/de/iptv/tv/MainActivity.kt:327`; `app/src/main/java/de/iptv/tv/SeriesLayout.kt:35`; `app/src/main/java/de/iptv/tv/SeriesLayout.kt:67` | Ein wachsender Serienkopf nahm der Liste Platz; große Beschriftungen wurden gekürzt. | Fortsetzen stapelt Beschriftung und Episodencode bei Platzmangel. Nur der Serienkopf ist in der Höhe begrenzt und scrollbar; Status und Staffelwahl bleiben über der Liste sichtbar. | Lange Titel und doppelte Systemschrift lassen weiterhin Platz für fokussierbare Episoden. Die native Scrollbar zeigt verborgenen Kopfinhalt an. |
| MEDIUM, behoben | `app/src/main/java/de/iptv/tv/MainActivity.kt:709` | Der Episodenzähler zeigte keine Scrollrichtung. | Ein Hinweis zeigt weitere bzw. vorherige Episoden; am Listenende wechselt die Richtung. | Verborgene Episoden bleiben erkennbar. |

**Logical properties for anything that mirrors**

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM, behoben | `app/src/main/java/de/iptv/tv/MainActivity.kt:322`; `app/src/main/java/de/iptv/tv/MainActivity.kt:1590` | Eine ausschließlich numerische Zeitangabe konnte beim Spiegeln Position und Dauer visuell vertauschen. | Relative Abstände spiegeln den Bereich; Zeitangaben behalten ihre Leserichtung. | Die gespiegelte Struktur bleibt lesbar, ohne die Bedeutung von Position / Dauer zu ändern. |

Die Prüfung verwendet synthetische lokale Inhalte, keine privaten Playlists. `SeriesLayoutTest` prüft die native Messung, sichtbare Aktionsflächen, 30 Episoden, die letzte Episode per D-Pad und den Staffelwahldialog. `ContentVisualTest` prüft Screenshots bei normaler und doppelter Schrift; `UserLibraryUiTest` und `ContentLibraryTest` prüfen Favoriten, Fortsetzen, M3U-/Xtream-Staffeln und Wiedergabe.

| Verification | Ergebnis |
| --- | --- |
| Android Debug-APK und Test-APK | Bestanden |
| 51 Kotlin-Unit-Tests | Bestanden |
| `lintDebug` | Bestanden; 0 Fehler, vorhandene Projektwarnungen bleiben |
| TV-Emulator, 1920 × 1080, 320 dpi | Bestanden |
| Englisch / Deutsch | Bestanden |
| 320 physische Pixel Panelbreite | Bestanden |
| Native Systemschrift 200 %, zusätzlicher langer deutscher Serienname | Bestanden |
| Gespiegelter Serienbereich mit arabischem Titel; Leserichtung der Zeit | Bestanden |
| D-Pad, Staffelwahl, Favoriten, Fortsetzen, M3U und Xtream | Bestanden |
| Echter Fernseher, andere physische Auflösungen | Not verified |
| Automatische Pseudo-Lokalisierung | Not verified; lange deutsche Texte wurden separat geprüft |
| Browser-Zoom / mobile Browser-Viewports | Nicht anwendbar: native Android-TV-Oberfläche |

Screenshots und Build-/Testprotokolle liegen lokal unter `artifacts/series-layout-*`. Die ursprüngliche Layout-Testfassung heißt `artifacts/RUSTREAM-IPTV-series-layout.apk` (Debug-signiert, Version 0.5.3). Das Serienlayout ist Teil von Version 0.5.4; die aktuelle Player-Testfassung enthält zusätzlich die Episodenbuttons.

Keine offenen HIGH-Befunde im geprüften Serienbereich. Die Freigabe gilt ausschließlich für den oben genannten Umfang und die verifizierten Konfigurationen.

Approve

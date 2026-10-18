# Episodenwechsel im Serienplayer

18. Oktober 2026. Ergänzung zum Serienlayout: **Vorherige Episode** und **Nächste Episode** als Transport-Icons in der bestehenden Playerleiste, in Vorschau und Vollbild. Die Beschriftung erscheint beim Fokussieren; Deutsch und Englisch sind vorhanden. Farben und bestehende Wiedergabeaktionen verwenden weiterhin `TvUi`.

Die Buttons richten sich nach der tatsächlich ausgewählten Serienepisode. Das bloße Öffnen einer Serie während Live-/Film-Wiedergabe blendet sie nicht ein. Beim Durchstöbern eines anderen Bereichs behalten sie den Kontext der laufenden Episode. Bei Film- und Live-Wiedergabe sind beide Buttons samt Spalten verborgen.

Die vorhandene Katalogfunktion `adjacent` liefert die vorherige und nächste abspielbare Episode innerhalb derselben Serie und Staffel. An Staffelgrenzen wird der jeweilige Button deaktiviert; es gibt weder einen Umlauf noch einen Sprung in eine andere Serie. Der bisherige Wiedergabekontext und gespeicherte Fortschritt werden weiterverwendet. Ein Episodenwechsel im Vollbild behält das Vollbild bei.

Die D-Pad-Reihenfolge berücksichtigt ausschließlich sichtbare, aktivierte Aktionen und überspringt deaktivierte Buttons. Bei einem Wechsel zu einer Staffelgrenze bekommt der Play/Pause-Button den Fokus, falls die vorher fokussierte Aktion nun nicht mehr verfügbar ist. Bei geringer Vorschau-Breite passen sich die acht Buttonflächen an die vorhandenen Spalten an.

Verifikation: Debug-APK/Test-APK gebaut, `lintDebug` ohne Fehler und 51 Unit-Tests erfolgreich. `SeriesPlaybackControlsTest` prüft reale Wiedergabe und D-Pad-Navigation bei normaler und doppelter Systemschrift: Serienkontext, beide Wechselrichtungen, beide Staffelgrenzen, Ausblenden bei Live/Film, Vollbild-Erhalt sowie vollständig sichtbare Buttons ohne Überlappung mit mindestens 36 dp Breite. Zusätzlich prüfen `ContentLibraryTest` und `UiDesignTest` die bisherigen Bibliotheks-, Wiedergabe- und Fernbedienungsfunktionen.

Lokale Nachweise: `artifacts/series-player-controls-build.log`, `artifacts/series-player-controls-verified.log` und `artifacts/series-player-*.png`. Alle sieben abschließenden Android-Tests sind erfolgreich. Die Test-APK mit dem Serienlayout und diesen Buttons liegt unter `artifacts/RUSTREAM-IPTV-series-controls.apk` (Debug-signiert). Version 0.5.4 (VersionCode 15) enthält beide Änderungen; das Tag `v0.5.4` startet die vorhandene Development-Prerelease-Automation. Echter Fernseher: **Not verified**.

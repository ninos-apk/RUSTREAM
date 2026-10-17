# Live, Movies und Series – Version 0.1.2

Historischer Bericht. Der aktuelle Stand mit Filmrastern, gruppierten M3U-Serien und Schema-2-Upgrade steht in [Filme und Serien 0.4.0](CONTENT_LIBRARY_040.md).

15. Oktober 2026. Die Android-TV-App verwendet den aktualisierten M3U-Parser aus dem benachbarten Windows-Projekt. `playlist.rs` wurde unverändert übernommen; der Herkunftshash wurde aktualisiert.

## Verhalten

- Bereichsauswahl Live / Movies / Series mit Eintragszahlen. Beim Import wird der erste befüllte Bereich geöffnet.
- Kategorien gehören eindeutig zu einem Bereich. Derselbe Kategoriename kann in allen drei Bereichen vorkommen.
- Die Kategoriesuche filtert ausschließlich die angezeigten Kategorienamen. Die Eintragssuche berücksichtigt den aktuellen Bereich, die Kategorie sowie Name, Kategorie und TVG-ID.
- Bereichswechsel löschen beide Suchfelder, setzen die Kategorie zurück und öffnen die erste Ergebnisseite. Die bisherige Wiedergabe läuft bis zur Auswahl eines anderen Eintrags weiter.
- Die JNI-Suche überträgt weiterhin höchstens 128 Einträge pro Anfrage; die Oberfläche verwendet Seiten mit 64 Einträgen. Der Suchcache berücksichtigt jetzt auch den Bereich.
- M3U-Episoden werden als Series eingeordnet und als VOD abgespielt. Endliche HLS-Manifeste werden unter Movies angezeigt. Xtream liefert weiterhin Live und Movies; seine Kategorien behalten ihre ursprünglichen Namen. Eine Xtream-Serien-/Staffelauflösung ist nicht Bestandteil dieser Änderung.

## Prüfung

51 automatisierte Tests bestanden: 44 Rust-/JNI-Tests, 3 JVM-Tests und 4 Android-Instrumentierungstests. Der optionale private Geräte-Wiedergabetest wurde übersprungen.

- Rust-Formatprüfung, Clippy mit `-D warnings` und alle Workspace-Tests bestanden. Die neuen Tests prüfen die Erkennungsprioritäten, gleichnamige Kategorien, Bereichsfilter, Seitennavigation, Suchcache und VOD als Standardbereich.
- Android-JVM-Tests und Lint erfolgreich; Debug-APK, R8-Release-APK und Release-AAB für ARM64, ARMv7 und x86_64 gebaut.
- Google-TV-Emulator, API 36, 16-KiB-Seiten: Bereichswechsel, unabhängige Suchfelder, Kategorieauswahl, keine Treffer, Zurücksetzen der Suche und direkt abspielbare Serienepisoden geprüft.
- Reale Media3-Wiedergabe mit synthetischem lokalen Video: D-Pad, Live → Movies, Kanalwechsel, Pause/Seek, HTTP-503-Recovery, Verbindungsabbruch, Vollbild, Home/Rückkehr und Stopp bestanden.
- Separater Smoke-Test der R8-optimierten Release-App: URL-Import, JNI, D-Pad und echte Media3-Wiedergabe bestanden.
- Bestehende lokale Anbieter-Liste ohne Netzwerkzugriffe erneut geprüft: 257.468 Einträge, 395 Kategorien, kein übersprungener Eintrag. Der Bericht enthält keine Namen oder Stream-URLs.
- Native Ausrichtung/JNI-Exporte und Ausschluss privater Testlisten aus dem Test-APK geprüft.

Evidenz: `artifacts/test-results/android-instrumentation.xml`, `artifacts/playlist-review/content-sections-host.json`, `artifacts/tv-sections.png` und `artifacts/tv-e2e.png`. Die Prüfung auf echter TV-Hardware bleibt separat erforderlich.

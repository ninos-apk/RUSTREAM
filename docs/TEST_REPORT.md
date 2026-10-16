# Verifizierter Entwicklungsstand

Aktualisierung 0.3.1: [UI-Umsetzung und aktuelle Prüfnachweise](UI_IMPLEMENTIERUNG_031.md) zu Startbildschirm, Videofokus, Vollbild, Refresh, beschleunigtem Spulen, Formularen und großer Systemschrift.

Aktualisierung 0.2.1: [Performance-Abgleich mit Windows-Commit 7fcb083](PERFORMANCE_REVIEW.md), einschließlich Core-Synchronisierung, Media3-Live-Profilen, atomarer Telemetrie und präzisem Retry-Timer. Der folgende Bericht bleibt die historische Abnahme von 0.1.0.

Aktualisierung 0.2.0: [TV-Redesign, Icon-Overlay und randloses Vollbild](UI_REDESIGN.md), einschließlich Verbindungsdetails, Einstellungen und MCP-Screenshots.

Aktualisierung 0.1.2: [Live / Movies / Series und getrennte Suchfelder](CONTENT_SECTIONS.md), einschließlich neuer Parser-, Service- und Emulator-Tests.

Aktualisierung 0.1.1 mit echter Anbieter-Liste: siehe [PLAYLIST_REVIEW.md](PLAYLIST_REVIEW.md). Der folgende Bericht dokumentiert die vorherige synthetische Abnahme von 0.1.0.

15. Oktober 2026 · Version 0.1.0 · Windows-x64-Buildhost.

## Ergebnis

45 automatisierte Tests bestanden: 39 Rust-/JNI-Tests auf dem Host, 3 JVM-Tests und 3 Android-Instrumentierungstests. Zusätzlich ein erfolgreicher UI-Smoke-Test der R8-optimierten Release-App mit echter Media3-Wiedergabe. APK und AAB erfolgreich gebaut. Das dokumentierte Gesamtskript `scripts/build.ps1 -Release` wurde erfolgreich ausgeführt.

| Prüfung | Ergebnis / Evidenz |
| --- | --- |
| Rust | Rust 1.99.0, `cargo fmt --all -- --check`, Clippy mit `-D warnings`, `cargo test --locked --workspace`: bestanden |
| Herkunft | Vier übernommene Module byteidentisch zum Windows-Source; `scripts/check-upstream.ps1` bestanden |
| Android-JVM | Grenzwert/Leerstream, Überlauf bei unbekannter Länge, Weitergabe eines Lese-/Verbindungsfehlers: 3 Tests bestanden |
| Android-Lint | 0 Fehler, 25 Warnungen; überwiegend Ressourcen/Lokalisierung, neuere verfügbare Bibliotheksversionen und allgemeine TV-/Backup-Hinweise |
| Native ABIs | ARM64, ARMv7, x86_64 im Release-Profil erfolgreich mit NDK 28.2.13676358 und API-23-Linker gebaut |
| ELF / ZIP | Alle LOAD-Segmente mindestens 16 KiB ausgerichtet; drei JNI-Exporte je ABI vorhanden; APK `zipalign -c -P 16 4` bestanden |
| Emulator | Google TV, Android 16 / API 36, x86_64, 1920×1080, WHPX; `getconf PAGE_SIZE` liefert **16384** |
| Debug-E2E | 3 Tests, 0 Fehler, 0 übersprungen auf dem genannten Emulator |
| Release | `assembleRelease`, R8, `bundleRelease`, Release-Lint erfolgreich; separate APK-Kopie mit Debug-Testschlüssel signiert, Signaturprüfung und UI-Wiedergabetest bestanden |

## Abgedeckte Szenarien

Rust: Unicode/Attribute/Kategorien/Header, ungültige Protokolle und Header-Injection, beschädigte/überlange Zeilen, 100.000-Kanal-Fixture, Suche, Redaktion im Debug-Modell, Puffergrenzen, Refill-Hysterese und unbekannte Messwerte. Recovery: Startup, Stall, wachsender Refill, harte Deadline, Pause/Seek/Stop, Live-EOF versus VOD-Ende, Positionswiederaufnahme, Backoff und veraltete Generationen.

Neue Services: paginierte DTOs ohne Stream-URLs, ungültige Kanal-/Revisionswerte, transaktionaler Playlist-Austausch, alte Ladeversuche nach Retry/Kanalwechsel, idempotente Pausenmeldungen, direkte HLS-Manifeste als einzelner Stream, Xtream-Auth/URL-Encoding/Live/VOD sowie XMLTV-Zeitzonen, Entities, CDATA und beschädigte/externe XML-Daten.

Android-Instrumentierung:

1. Rust/JNI-Import und Suche, XMLTV-Zuordnung und Keystore-Roundtrip einschließlich Prüfung auf fehlenden Klartext im gespeicherten Ciphertext.
2. Lokaler HTTP-Import mit Redirect und relativer URL, Xtream-Transport für Live/VOD, XMLTV über HTTP.
3. TV-Activity mit überprüftem Anfangsfokus und D-Pad-Aktivierung, Kanalwechsel über Channel-Tasten, echte H.264/AAC-Wiedergabe, VOD-Pause/Seek, zwei HTTP-503-Fehler mit automatischem Retry, während des Streams abgebrochener HTTP-Body mit Recovery, Vollbild/Zurück, Home/Rückkehr und Stopp/Verlassen.

Der Fokusübergang aus dem Touch-Modus wurde durch einen zunächst fehlschlagenden Test erkannt und korrigiert. Der finale Emulatorlauf besteht. Die Ansicht markiert den laufenden Kanal zusätzlich zum unabhängig beweglichen D-Pad-Fokus und zeigt seinen Namen im Playerstatus.

Der Release-Smoke-Test benutzt dasselbe synthetische Video über einen lokalen Java-HTTP-Server. Er bedient den URL-Dialog, importiert die Playlist über den optimierten JNI-Pfad, aktiviert den Kanal mit der D-Pad-Mitteltaste und bestätigt den Wiedergabestatus. Die TV-Tastatur wird vor dem Bestätigen geschlossen. Die Debug-Testsignatur ist kein Publisher-Schlüssel.

## Artefakte und Nachweise

- `artifacts/iptv-tv-debug.apk`: direkt installierbarer Entwicklungsbuild.
- `artifacts/iptv-tv-release-unsigned.apk` und `iptv-tv-release-unsigned.aab`: Publisher-Ausgangspakete ohne eigenen Release-Schlüssel.
- `artifacts/iptv-tv-release-test.apk`: optimierter, ausschließlich für lokale Tests mit Debug-Key signierter Build.
- `artifacts/iptv-tv-source-0.1.0.zip`: zugehöriger Source ohne SDKs, Build-Caches oder private Einstellungen.
- `artifacts/build-verification.txt`, `artifacts/test-results/`: Prüfprotokoll und JUnit-XML-Ergebnisse.
- `artifacts/tv-e2e.png`, `artifacts/tv-release.png`: Screenshots echter Emulator-Wiedergabe.
- `artifacts/SHA256SUMS.txt`: Prüfsummen der Auslieferungsdateien.

## Praktische Grenzen der Aussage

Kein physischer TCL oder anderer Fernseher war per ADB verbunden. ARM-Pakete sind gebaut, jedoch hier nicht auf ARM-Hardware ausgeführt. API 23–35, reale 4K-/HDR-/AV1-/HEVC-Decoder, schwaches WLAN, lang andauernder Betrieb, echter TV-Standby, Audio-Fokus-Konkurrenz und anbieterspezifische Streams benötigen die Abnahme aus `DEVICE_ACCEPTANCE.md`. Der Emulator nutzt synthetische 640×360-H.264/AAC-Inhalte und ersetzt keine Codec-/Performance-Messung auf dem TV.

Keine Behauptung von Performanceparität zur Windows-App oder allgemeiner Geräte-/Store-Freigabe. Die Release-AAB muss mit einem eigenen Publisher-Schlüssel signiert werden. Lizenz-/Datenschutztexte und Store-Metadaten sind vor einer Veröffentlichung zu vervollständigen. Bekannte funktionale Unterschiede stehen im README.


# Performance-Review und Iteration 0.2.1

16. Oktober 2026. Referenz: Windows-HEAD `7fcb083545db22004a18cc82ce1d567907807230` im Nachbarordner `IPTV Player`. Android TV hatte zu Beginn Version 0.2.0 und keine `.git`-Historie. Vorherige Fassungen der geänderten Kernmodule liegen lokal unter `artifacts/performance-before-20261016/`. Das Windows-Projekt wurde nicht verändert.

## Abgleich mit Windows

Gelesen wurden insbesondere `ARCHITEKTUR_REVIEW_UND_PLATTFORMPLAN.md`, `LIVE_LATENZ_UND_STABILITAETSPLAN.md`, `STREAMING_DIAGNOSTICS.md` und `WINDOWS_PLAYLIST_ERWEITERUNG.md`; dazu die letzten Commits, besonders `7fcb083` (TS-Fortsetzung und geringe Latenz), `1c7fb8e` (Inhaltsbereiche), `d05855e` (Quellenverwaltung) und der frühere Recovery-/Diagnose-Ausbau `b9686c6`.

| Befund | Android vorher | Änderung / Entscheidung |
| --- | --- | --- |
| Erster Positionswert zählt als Wiedergabe; Rücksprünge als Bewegung | Älterer kopierter Recovery-Core | Windows-Korrektur übernommen: Baseline, dann mindestens 20 ms Vorwärtsbewegung. PTS-Rücksprünge etablieren eine neue Baseline. |
| Erster EOF nach belegter Live-Wiedergabe | Mindestens 1 s Backoff plus Jitter und Ticker-Verzögerung | Backoff entfällt nur beim ersten gesunden EOF, 0–250 ms Jitter bleiben. Wiederholungen und Fehler behalten exponentiellen Backoff. |
| Retry-Ausführung | Sekundentakt | Versuchsgebundener Timer mit Rust-Deadline, Rundung nach oben. Stop, Suspend, neuer Load und Pause stornieren ihn. Ticker bleibt Ausfallreserve. |
| Live-/VOD-Puffer | Beide 10–30 s, Start 1,5 s, Rebuffer 3 s | Drei Live-Profile; VOD-Konfiguration bleibt erhalten. |
| Regelmäßige JNI-Übergänge | Sample + Tick, bei Pausenwechsel zusätzlich Pause | Ein `poll` für Pause, Sample und Tick, atomar und versuchsgebunden. Bei laufender Wiedergabe zwei regelmäßige Roundtrips auf einen reduziert. |
| Zufluss | Tatsächliche Bytes nur in der UI; Core erhielt `null` | Dieselbe Byte-Messung wird einmal pro Zyklus in UI und Core verwendet; kein auslaufender geschätzter mpv-Wert. |
| Statusanzeige | Unter 2,5 s immer „Wenig Puffer“ | Warnschwelle berücksichtigt das aktive Pufferziel; Sport ist nicht ständig gelb. Unbekannter Puffer wird nicht als 0 gewertet. |
| Medienpipeline | Media3 + MediaCodec | Beibehalten; Windows-D3D11/RTX und FFmpeg-Optionen sind nicht übertragbar. |
| TS-Kontinuität innerhalb derselben Queue | Keine | Weiter offen: Windows-Fortsetzung braucht auf Android eine separat getestete Datenquellen-/Extractor-Lösung. Vollständiger Rust-Reload bleibt der Fallback. |

## Aktive Pufferprofile

| Profil | Live-Minimum/Maximum | Start | Nach Unterlauf | Byte-Ziel |
| --- | --- | --- | --- | --- |
| Sport | 1 / 1 s | 0,25 s | 0,5 s | 16 MiB |
| Ausgewogen (Standard) | 3 / 3 s | 0,5 s | 1 s | 32 MiB |
| Stabil | 6 / 6 s | 1 s | 2 s | 64 MiB |
| VOD, alle Profile | 10 / 30 s | 1,5 s | 3 s | 32 MiB |

Auswahl unter Einstellungen → Live-Pufferprofil, lokal gespeichert, wirksam beim nächsten Verbindungsaufbau. Ein Profilwechsel löst keinen automatischen Reload aus. Die Schwellen liegen unter dem jeweiligen Minimum und bleiben somit erreichbar. `setPrioritizeTimeOverSizeThresholds(false)` erhält die Byte-Priorisierung. Media3 lädt in Medienblöcken; große Segmente können Zeitziele überschreiten. Das ist weder eine Ende-zu-Ende-Latenzzusage noch ein hartes RAM-Limit.

Die Umsetzung folgt den Media3-Schnittstellen für [LoadControl](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl.Builder) und [LoadErrorHandlingPolicy](https://developer.android.com/media/media3/exoplayer/customization). Native vollständige Media3-Retries bleiben abgeschaltet, damit Rust das Session-Retry-Budget kontrolliert. HLS/DASH behalten native adaptive Qualitätswahl; aus kurzem Zuflussstillstand bei vollem Puffer folgt keine Qualitätsabstufung.

## Prüfung

**63 automatisierte Tests bestanden:** 50 Rust-/JNI-Tests, 7 JVM-Tests und 6 Android-Instrumentierungstests. Ein zusätzlicher privater Anbieter-Test wurde mangels explizit übergebener Playlist übersprungen. Rust-Formatprüfung und Clippy (`--workspace --all-targets -- -D warnings`) bestehen. Android-Lint: 0 Fehler, 39 Warnungen, überwiegend bestehende Lokalisierungs-/Ressourcen- und Dependency-Hinweise. Debug-APK, R8-Release-APK und Release-AAB erfolgreich gebaut. Native Bibliotheken für ARM64, ARMv7 und x86_64, JNI-Exporte und 16-KB-ELF-/APK-Ausrichtung geprüft.

Emulator: Google TV / Android 16 / API 36 / x86_64 / 16-KB-Seiten. Synthetisches H.264/AAC-Video über lokalen HTTP-Server, Standardprofil 3 s. Neue Instrumentierung misst **135 ms von `STATE_ENDED` bis zum neuen ExoPlayer-Versuch**, einschließlich Timer und Player-Neuerzeugung; keine Messung des ersten sichtbaren Bildes nach Recovery. **2.044 ms von D-Pad-Aktivierung bis zum Status „Wiedergabe“** enthalten Eingabeverarbeitung und den sekündlichen Telemetrietakt; das ist keine präzise Decoder-/Erstbildmessung. Rohwerte: `artifacts/performance-review.json`. Für diese Zahlen gibt es keinen gleichartig gemessenen Vorherlauf; daraus wird keine prozentuale Gerätebeschleunigung abgeleitet.

Zusätzlich abgedeckt: tatsächliche Media3-Builder-Konfiguration aller Profile, VOD-Konfiguration, Positionsbaseline und PTS-Rücksprünge, kurzer erster EOF und Backoff bei Wiederholung, exakte Rust-Deadline, atomare Poll-Reihenfolge, veraltete Versuche, idempotentes Pause-Sampling, Stop während geplanter Recovery sowie die bestehenden Kanal-/VOD-/HTTP-Abbruch-/Home-/D-Pad-/Listen-Tests. Der erste neue Emulator-Testlauf scheiterte an fehlender D-Pad-Aktivierung der Testquelle nach einem Fokus-Klick; Test korrigiert, anschließender vollständiger Lauf mit 0 Fehlern.

Rohprotokolle: `artifacts/performance-rust-tests.txt`, `artifacts/performance-validation.txt`; JUnit-Ergebnisse unter `app/build/test-results/` und `app/build/outputs/androidTest-results/connected/`. `artifacts/performance-first-device-run.txt` erhält den ersten Testbefund. Alle vier übernommenen Core-Module wurden zusätzlich gegen den aktuellen Windows-HEAD verglichen und sind byteidentisch. Windows-Arbeitsbaum unverändert.

Der R8-optimierte Release-Build besteht zusätzlich den vorhandenen Emulator-Smoke-Test: Start, JNI-Playlist-Import, D-Pad-Aktivierung und echte Media3-Wiedergabe. Protokoll: `artifacts/performance-release-smoke.txt`. Das Test-APK wurde separat mit dem lokalen Debug-Testschlüssel signiert; Publisher-APK/AAB bleiben unsigniert. Installierbares Entwicklungs-APK: `artifacts/iptv-tv-debug.apk`, Version 0.2.1 (Code 5). Quellenpaket: `artifacts/iptv-tv-source-0.2.1.zip`.

## Nächste Iteration nach Messwerten

1. Auf physischem ARM-TV dieselben Sender mit Sport/Ausgewogen/Stabil messen: erstes sichtbares Bild, Live-Abstand mit Quellzeitreferenz, Rebufferzeit, Frame-Drops, A/V-Sync, Prozess-PSS und längerer Standby-/WLAN-Test. Das bisherige synthetische Emulatorvideo belegt keine 4K-/HDR-Performance.
2. Für HTTP-Live-TS eine begrenzte Transportfortsetzung im selben Media3-Eingang prüfen. Vor Freigabe: EOF, abrupter Abbruch, Stillstand, 503, permanente HTTP-Fehler, erhaltene Header, Stop während Retry, kein versehentliches Range-Seeking und unverändertes VOD-Ende testen. Keine pauschalen DataSource-Retries für HLS-/DASH-Segmente oder beliebige MP4-Quellen.
3. Erst mit nachgewiesenem Bedarf Decoder-/Player-Wiederverwendung, adaptives Rebuffering und geglättete Geräte-Telemetrie entwickeln. Eine globale Player-Wiederverwendung verändert die bisherigen Versuch-/Listener-Grenzen und braucht eigene Tests.
4. Für sehr große Xtream-Kataloge Streaming-Mapping und getrennte Import-Handles untersuchen. Der vorhandene serielle Rust-Executor kann während eines Imports Recovery-Befehle verzögern; dies wurde in dieser Iteration nicht umgebaut. Playlist-Seiten und gecachte Suche sind bereits begrenzt/virtualisiert.
5. Gemeinsamen Rust-Core als eigenes Paket referenzieren, damit die Apps künftig nicht durch manuelle Kopien auseinanderlaufen. Der aktuelle Upstream-Hashcheck sichert die dokumentierte Kopie, synchronisiert aber nicht automatisch mit Windows.

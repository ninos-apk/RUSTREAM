# Herkunft und Wiederverwendung

## Inhaltsbibliothek 0.4.0 – 17. Oktober 2026

Die Parserregeln und Metadaten aus Windows-Commit `17dd21f41fd8a802df236ef1ce9d2c1087fb034e` wurden in die Android-Ableitung übertragen. `parse_stream`, Formatprüfung und Speichergrenzen bleiben erhalten; die produktive Seriengruppierung liegt in SQLite. Der ältere In-Memory-Pfad besitzt denselben Serienindex wie Windows. Fünf Inhaltsbibliothekstests sind bis auf den Crate-Namen übernommen. `upstream-sha256.json` dokumentiert lokalen Ableitungs- und Windows-Referenzhash. Einzelheiten und Prüfung: [Inhaltsbibliothek 0.4.0](CONTENT_LIBRARY_040.md). Die nachfolgenden Übernahmeberichte beschreiben frühere Zustände.

## Android-Katalog 0.3.0

Der Playlist-Parser ist seit dieser Iteration eine dokumentierte Ableitung: Streaming-Sink statt vollständigem zweiten RAM-Katalog, frühe Formatprüfung, geliehene Attributwerte und weniger URL-Zwischenobjekte. Die Klassifikationsprioritäten bleiben erhalten. `upstream-sha256.json` bewahrt den ursprünglichen Windows-Hash zusätzlich zum geprüften lokalen Hash. Recovery, Buffering und Telemetrie bleiben unveränderte Übernahmen. Die Windows-Anwendung wurde nicht verändert.

Die echte Referenzdatei wird zusätzlich feldweise zwischen Parserausgabe und verschlüsseltem SQLite-Katalog verglichen; Kategorien, Bereich, Name, URL, TVG-ID, Logo, Header und Live-Flag werden für alle 257.468 Einträge geprüft. Neue Katalog-, Cache- und Xtream-Serienlogik liegen in separaten Android-Core-/Adaptermodulen. Ein gemeinsames Paket für beide Anwendungen bleibt eine spätere organisatorische Umstellung; die neue TV-Schnittstelle erzwingt keinen Umbau des Windows-Projekts.

## Aktualisierung 16. Oktober 2026

`recovery.rs`, `buffering.rs` und `telemetry.rs` wurden aus Windows-Commit `7fcb083545db22004a18cc82ce1d567907807230` übernommen; Recovery-/Buffering-Regressionstests ebenfalls, mit angepasstem Crate-Namen. Alle vier Core-Module sind jetzt byteidentisch zum lokalen Windows-HEAD dieses Commits; der Playlist-Parser war bereits aktuell. SHA256-Werte in `upstream-sha256.json` aktualisiert. Die unten genannte ursprüngliche Übernahme bleibt als Historie erhalten.

Android nutzt die korrigierte Vorwärtsfortschrittserkennung und den schnellen ersten Live-EOF-Fallback. Die neuen Rust-Pufferdefaults gelten im Core, die aktiven Media3-Profile werden separat in Kotlin übersetzt. mpv-/FFmpeg-Transportfortsetzung, RTX-Filter und Windows-Logauswertung sind nicht Teil dieser Übernahme. Siehe [Performance-Review](PERFORMANCE_REVIEW.md).

Quelle: lokaler Ordner `../IPTV Player`, Version 0.2.0, Git-HEAD bei Übernahme:
`d05855e41e8c9bc53f7bde2e0ae449f4c334f267`, 15. Oktober 2026.

Unverändert übernommen nach `crates/iptv-core/src/`:

- `playlist.rs`: Am 15. Oktober 2026 erneut aus dem lokalen Windows-Projekt übernommen: ContentKind, Bereichserkennung für Live/Movies/Series, pro Bereich getrennte Kategorien und filter_content zusätzlich zu Stream-Headers, sicherer Protokoll-/Headerbehandlung, begrenzten Zeilen und Unicode-Suche. Byte-identisch mit dem Windows-Parser zum Aktualisierungszeitpunkt; Hash aktualisiert.
- `recovery.rs`: Startup-/Stall-/Refill-Watchdog, begrenzter Backoff mit Jitter, Live-EOF, VOD-Position und Generationen.
- `buffering.rs`: Konfiguration, adaptive Nachfüllregeln und HLS-Varianten-Helfer. Die mpv-spezifische Track-JSON-Auswertung ist auf Android nicht aktiv.
- `telemetry.rs`: optionale Messwerte; der mpv-Cache-Mapper wird auf Android nicht verwendet.

SHA256 zum Zeitpunkt der Übernahme: `upstream-sha256.json`. Kontrolle:

```powershell
./scripts/check-upstream.ps1
```

Die ursprünglichen Testdateien `playlist.rs`, `recovery.rs`, `buffering.rs` wurden ebenfalls übernommen; allein der Crate-Import heißt jetzt `iptv_core`. Neue Services kapseln Android-spezifische Session-/Versuch-IDs und idempotente Pausenbefehle. Der Windows-Code wurde nicht umgebaut und benötigt keinen Pfad zu diesem Projekt. Es handelt sich um eine nachvollziehbare, unabhängige Core-Übernahme; spätere Windows-Änderungen werden nicht automatisch synchronisiert. Bei gemeinsamer Weiterentwicklung sollten beide Apps dasselbe ausgegliederte Core-Paket referenzieren.

Xtream-Response-Mapping orientiert sich an `sources.rs`: Authentifizierung, aktive Accounts, Ausgabewahl, Kategorien, URL-Segmente, Live/VOD. WinHTTP und DPAPI werden nicht übernommen. XMLTV, JNI, TV-Oberfläche und Android-Persistenz sind neue Module.


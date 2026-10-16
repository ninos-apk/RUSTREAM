# Drittkomponenten

Dieses Projekt übernimmt GPL-3.0-or-later-Code aus dem lokalen Windows-IPTV-Player (siehe `docs/UPSTREAM.md` und `LICENSE`).

| Komponente | Einsatz | Lizenz / Projekt |
| --- | --- | --- |
| Rust Standardbibliothek | nativer Core | MIT / Apache-2.0, https://www.rust-lang.org/ |
| serde / serde_json | Bridge-Daten | MIT / Apache-2.0, https://serde.rs/ |
| url | URL-Validierung/-Auflösung | MIT / Apache-2.0, https://github.com/servo/rust-url |
| quick-xml | XMLTV | MIT, https://github.com/tafia/quick-xml |
| time | XMLTV-Zeitstempel | MIT / Apache-2.0, https://github.com/time-rs/time |
| jni-rs | JNI | MIT / Apache-2.0, https://github.com/jni-rs/jni-rs |
| rusqlite / libsqlite3-sys / SQLite | Datenträgerkatalog und Paging | MIT / gebündeltes SQLite Public Domain, https://github.com/rusqlite/rusqlite und https://sqlite.org/copyright.html |
| RustCrypto aes-gcm und transitive Kryptografiebausteine | AES-256-GCM für Streamadressen und Header im Katalog | MIT / Apache-2.0, https://github.com/RustCrypto/AEADs |
| Kotlin / kotlinx.coroutines | Android-Frontend | Apache-2.0, https://kotlinlang.org/ |
| AndroidX / Media3 | Activity, RecyclerView, Player, Session | Apache-2.0, https://developer.android.com/jetpack/androidx |
| OkHttp / Okio | Quellenimport | Apache-2.0, https://square.github.io/okhttp/ |
| desugar_jdk_libs | Java-Zeit-API auf älteren Geräten | GPL-2.0 with Classpath Exception, https://github.com/google/desugar_jdk_libs |
| JUnit / AndroidX Test | Tests, nicht App-Funktionen | EPL-1.0 / Apache-2.0 |

Exakte Rust-Auflösung: `Cargo.lock`; Android-Versionen: `app/build.gradle.kts`. Transitive Lizenzhinweise sind vor öffentlicher Distribution anhand der aufgelösten Abhängigkeiten zu vervollständigen. Windows-mpv, FFmpeg und NVIDIA-Runtime werden nicht in das App-APK übernommen. Das Testvideo ist selbst erzeugtes FFmpeg-Testbild mit synthetischem Sinuston und liegt ausschließlich im Test-APK.


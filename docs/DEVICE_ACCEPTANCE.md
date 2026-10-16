# Geräteabnahme und Leistungsnachweis

Vor einem Release auf dem TCL und mindestens einem schwächeren Android-TV-Gerät prüfen. Die folgenden Punkte sind Abnahmekriterien, keine bereits gemessenen Ergebnisse.

| Szenario | Vorgehen / Akzeptanz |
| --- | --- |
| Remote / Fokus | Alle Funktionen mit D-Pad, OK, Zurück, Media-Tasten; Fokus bleibt sichtbar, Dialoge ohne Maus bedienbar |
| Große Playlist | Eigene 50k-/100k-Liste; Importzeit, RSS/PSS und Such-Latenz protokollieren, Scrollen ohne ANR |
| Kanalwechsel | 100 Wechsel, auch schnell hintereinander; keine alten Fehler/Frames in neuer Session, kein Decoder-Leak |
| EPG | Zeitzonenwechsel, Tageswechsel, fehlende IDs und Anbieter-XML; richtige Jetzt/Nächste-Sendung |
| VOD | Pause, ±30 s, EOF, Seek während Pause und Positionswiederaufnahme nach Abbruch |
| Verbindung | WLAN 5/30/120 s unterbrechen, Router neu starten; genau eine Recovery-Schleife, begrenzter Backoff |
| Langsamer Zufluss | Traffic unter Streambitrate begrenzen; Pufferstatus, kein falscher Reload bei nachweislich wachsendem Cache, harte Deadline |
| Live-Pufferprofile | Sport/Ausgewogen/Stabil mit denselben Streams vergleichen: Erstbild, Live-Abstand mit Quellzeitreferenz, Rebufferzeit, Frame-Drops und Prozess-PSS; Profil bleibt nach Neustart erhalten und gilt ab nächstem Load |
| EOF und Retry-Timer | Ersten EOF nach Wiedergabe und wiederholte EOFs getrennt messen; Stop/Pause/Home/Kanalwechsel während Backoff verhindern alte Reloads; bei VOD bleibt natürliches EOF beendet |
| Lifecycle | Home, andere Media-App (Audio-Fokus), Standby, HDMI/Surface-Wechsel, Rückkehr; bewusst pausierte Inhalte bleiben pausiert |
| Formate | HLS MPEG-TS/fMP4, DASH, HTTP MPEG-TS, MP4/MKV, RTSP anhand legaler eigener Quellen |
| 4K/HDR | H.264/HEVC/AV1 je tatsächlicher Hardwarefähigkeit; richtige Farbe, Ton und Aspect-Ratio, kein ungewolltes Software-Decoding |
| Langzeit | 4–8 h Live-TV, mindestens 30 Netzstörungen; keine ANRs/Crashes, stabiler Speicher |
| Packaging | ARMv7 + ARM64 real installieren, 16-KB-Google-TV-Emulator, signiertes Release mit R8 starten |

Vergleich mit Windows nur mit denselben Streams und Netzwerkbedingungen. Je Gerät/OS/Codec mindestens p50/p95 für Erstbild und Umschalten, RSS/PSS, verlorene Frames, Pufferunterläufe, Recovery-Dauer und CPU erfassen. Keine Performanceparität aus der Rust-Wiederverwendung allein ableiten.

ADB-Grundbefehle nach `. ./scripts/env.ps1`:

```powershell
adb devices
adb shell dumpsys meminfo de.iptv.tv
adb shell dumpsys media.codec
adb shell dumpsys gfxinfo de.iptv.tv
adb shell getconf PAGE_SIZE
```

Ergebnisse mit Gerät, SoC, OS, App-Version, Stream-Codec, Auflösung, Framerate und Verbindung dokumentieren. Provider-Zugangsdaten/URLs nicht in weitergegebene Berichte übernehmen.


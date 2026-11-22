# Bildstillstand bei Filmen und Serien

## Auftrag und Plan vom 22. November 2026

Ziel ist Version 0.5.14 (versionCode 25). In der untersuchten Folge sprang die Bildzeit kurz nach 20:03 um rund 68 Sekunden vor und wieder zurück. Währenddessen lief der Ton weiter. In Version 0.5.13 prüft nur die Live-Wiederherstellung den Framefortschritt.

1. Bei sichtbarer, laufender und spulbarer VOD-Wiedergabe den Bildfortschritt gemeinsam mit der Medienuhr überwachen.
2. Bei bestätigtem Stillstand an der erreichten Position neu einsetzen und Wiederholungen begrenzen. Pause, Spulen, Pufferung und Quellenwechsel berücksichtigen.
3. Erkennung und Begrenzung in JVM-Tests sowie die Wiederaufnahme mit einer synthetischen Zeitstempelstörung auf dem TV-Emulator prüfen.
4. Release-Build, Paketprüfungen und Versionskontrolle ausführen.

Abnahme: Nach der Störung erscheinen ohne Benutzereingriff wieder fortlaufende Bilder. Der Player überspringt keine feste Zeitspanne. Eine unverändert gerenderte Szene und eine pausierte Wiedergabe lösen keine Recovery aus.

## Verhalten in 0.5.14

Der Player beobachtet bei sichtbarem Video, aktiver Audio- und Videospur und spulbarer Quelle einmal pro Sekunde den vorhandenen Media3-Framezähler und die Medienposition. Bleibt der Zähler mindestens acht Sekunden stehen und läuft die Position dabei um mindestens vier Sekunden vor, öffnet RUSTREAM denselben Medieneintrag im selben Player an der aktuellen Position neu. Das verwirft die fehlerhafte Decoder-Warteschlange. Im Diagnosebericht steht dafür `vod_video_frame_stall` mit Position und Framezähler.

Pause, Pufferung, Positionswechsel und längere Unterbrechungen der Abfrage verwerfen die laufende Beobachtung. In den letzten zwei Sekunden einer bekannten Laufzeit ist die Erkennung ausgesetzt. Pro anhaltendem Stillstand sind höchstens zwei Versuche vorgesehen. Erst fünf Sekunden fortlaufender Frame- und Positionsfortschritt geben weitere Versuche frei. Eine neue Inhaltsauswahl beginnt mit eigenem Zähler. Ein als Folge regulärer Frames codiertes Standbild zählt als Bildfortschritt.

Das erneute Öffnen kann kurz puffern. Die Wiederaufnahme setzt voraus, dass die Quelle ab dem neuen Einstiegspunkt wieder verwendbare Videoframes liefert. Ein absichtlich sehr lange gehaltenes einzelnes Bild kann dieselben Messwerte liefern. Geprüft wurde auf Google TV im Emulator. Ein physisches TV-Gerät war nicht Teil der Abnahme.

## Nachweis am gemeldeten Fehler

Auf dem Google-TV-Testemulator `emulator-5554` (API 36, x86_64, 16-KiB-Speicherseiten) lief die Folge ab 19:57,554 linear über die fehlerhafte Stelle. Eine temporäre Loopback-Verbindung reichte die unveränderten Medienbytes einschließlich der Suchanfragen an den Emulator weiter.

| Testquelle | Letztes reguläres Bild vor dem Zeitsprung | Automatischer Wiedereinstieg |
| --- | ---: | ---: |
| Synthetisches H.264/AAC-Testvideo mit zwei um 68 s vordatierten Frames | 00:04,300 | 00:12,319 |
| Gemeldete Serienfolge | 20:03,554 | **20:11,514** |

In beiden Läufen erfasste der Instrumentationstest genau einen Eingriff. Danach prüfte er bei laufender Wiedergabe sowohl die Position als auch den Media3-Zähler für gerenderte Bilder. Mindestens 20 weitere Bilder erschienen im Prüfintervall, und die Medienposition lief vom Wiedereinstieg aus weiter. Weiterlaufen des Tons allein hätte den Test nicht bestanden.

## Prüfungen und Reproduktion

- `./scripts/build.ps1 -Release` bestand mit Rust-Formatprüfung, Clippy, 86 Rust-Tests, 121 JVM-Tests, Android-Lint, Debug- und Test-APK, Release-APK und Release-AAB. Der explizit ausgenommene Rust-Millioneneinträge-Stresstest lief nicht. Core-Herkunftsprüfung, Testasset-Kontrolle, APK-Ausrichtung sowie JNI-Exporte und 16-KiB-Ausrichtung aller drei ABIs bestanden.
- Der erste Gradle-Aufruf in der Sandbox erreichte seinen lokalen Daemon nicht. Außerhalb der Sandbox scheiterte ein erster JVM-Teststart an Anführungszeichen im Windows-PATH. Für den erfolgreichen Build wurde ausschließlich der PATH des Build-Prozesses mit `$env:Path = $env:Path.Replace('"','')` bereinigt und der projektlokale Gradle-Daemon neu gestartet.
- Die vier neuen JVM-Tests prüfen laufende und gestoppte Medienuhr, gesunde Frameabgabe, inaktive Intervalle, Suchvorgänge, Beobachtungslücken und die Begrenzung der Wiederholungen.
- `./scripts/test-playback-controls.ps1 -Serial emulator-5554 -SkipBuild` bestand mit 5 von 5 Gerätetests, darunter beschleunigtes Spulen, Episodenwechsel, VOD-Puffer und festhängendes Nachladen. `./scripts/test-live-recovery.ps1 -Serial emulator-5554 -SkipBuild` bestand ebenfalls mit 5 von 5 Tests, einschließlich vorübergehender HTTP-Fehler, Live-EOF und Abbruch einer geplanten Wiederholung.
- Mit installiertem Debug- und Test-APK startet `adb -s emulator-5554 shell am instrument -w -e class de.iptv.tv.VodVideoRecoveryTest de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner` den eigenständigen Test mit der synthetischen Datei. Für eine lokale Vergleichsquelle akzeptiert der Test optional `-e vodFixturePort <Port> -e vodFreezeMs <Zeitpunkt>` über eine ADB-Portweiterleitung. Anbieteradresse und Anmeldedaten werden dafür auf dem Gerät nicht benötigt.
- Das neue Testasset `vod-pts-jump.mkv` enthält 32 Sekunden FFmpeg-Testbild (`testsrc2`, 96 × 64, 10 Bilder/s) und einen 440-Hz-Sinuston. H.264 Baseline mit einem Keyframe pro Sekunde und AAC wurden in Matroska verpackt. Beim Kopieren wurde zu den PTS der nullbasierten Videopakete 44 und 45 jeweils 68.000 ms addiert. Gemessen wurden 4,3 → 72,4 → 72,5 → 4,6 Sekunden. Test-APK und Quellarchiv enthalten als Testmedien ausschließlich diese Datei und die bestehende synthetische `fixture.mp4`.
- `./scripts/check-release-tag.ps1 -Tag v0.5.14 -CheckHistory` bestätigte die nächste freie Version mit höherem versionCode. Die deutschen und englischen Release Notes wurden geprüft und mit `./scripts/generate-release-notes.ps1 -Version 0.5.14` erzeugt.

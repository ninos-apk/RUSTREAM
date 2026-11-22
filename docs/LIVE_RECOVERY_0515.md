# Live-Wiederherstellung 0.5.15

## Auftrag vom 22. November 2026

In 0.5.14 konnte ein Live-Standbild mit wiederholten kurzen Pufferpausen im 120-Sekunden-Test beide Wächter umgehen. Die Diagnose konnte eine Rückkehr des Bildes schon bei laufender Medienuhr melden. Ein abgelaufenes Livefenster führte immer zum vollständigen Neuaufbau.

## Plan und Abnahme

Zielversion 0.5.15 mit versionCode 26. Die Bildüberwachung soll aktive Wiedergabezeit über Nachladepausen hinweg zählen. Eine bestätigte Live-Erholung soll bei Video mehrere neue Bilder und bei Radio Positionsfortschritt erfordern. Bei einem abgelaufenen Livefenster soll Media3 einmal direkt an der Standardposition einsetzen und bei erneutem Fehler an die bestehende Rust-Recovery übergeben.

Geplant sind JVM-Regressionstests für den 120-Sekunden-Verlauf, Gerätetests mit lokaler Quelle und Media3, der Release-Build mit Paketprüfung sowie die Versions- und Tagprüfung.

## Umsetzung

Die Android-Wiedergabesteuerung zählt für sichtbares Live-Video die aktiven Wiedergabesekunden seit dem letzten Framefortschritt. Während einer beobachteten Pufferpause bleibt die bereits verstrichene Zeit erhalten. Nach zwölf aktiven Sekunden fordert sie den bestehenden Rust-Neuaufbau an. Beim vereinzelten Unterlauf bekommt der Stream weiter Gelegenheit, seinen Puffer aufzufüllen. Der Core verwaltet die Wartezeiten für vollständige Neuverbindungen.

Nach einem Fehler prüft RUSTREAM die Zunahme des Media3-Zählers `renderedOutputBufferCount` über zwei aufeinanderfolgende Messintervalle. Eine einzelne Bildausgabe oder allein fortschreitender Ton genügen bei einem Videokanal nicht. Für eine ausgewählte Audiospur ohne Videospur zählt die zunehmende Position. Der Diagnosebericht enthält bei verfügbarer Ausgangsmessung `video_gap_ms`. Die Zeit ist eine Schätzung aus den Abtastungen des Players und schließt die Neuverbindung ein.

Bei `ERROR_CODE_BEHIND_LIVE_WINDOW` setzt der bestehende Player über `seekToDefaultPosition()` und `prepare()` direkt wieder ein. Ein zweiter Fehler vor bestätigter Erholung durchläuft den normalen Rust-Backoff. Erfolgreiche Wiederherstellung oder neue Inhaltsauswahl geben den lokalen Versuch wieder frei.

## Abnahme

- Der JVM-Test bildet 120 Sekunden mit letztem neuen Bild bei Sekunde 2 und je einer Puffersekunde bei 10, 20, 30 usw. nach. Der erste Eingriff erfolgt bei Sekunde 15. Ein weiterer Test prüft, dass eine lange Pufferpause allein keinen Bildstillstand auslöst. Weitere Fälle prüfen Pause, Reset, ein einzelnes Bild, fortlaufende Bilder sowie die Radiouhr.
- Der Google-TV-Emulator `emulator-5554` (AVD `iptv-google-tv-16k`) spielte eine synthetische Quelle mit fehlerhaften Videozeitstempeln als Live-Sender. Nach dem Stillstand prüfte der Test den Austausch des Players, die Diagnose der Bildlücke und wieder gerenderte Bilder.
- Der lokale HLS-Test verschob die Mediensequenz des laufenden Senders um 1.000 Segmente. Media3 meldete einen echten Fensterfehler und spielte anschließend im selben Player wieder Video. Ein zweiter Fenstersprung prüfte die Freigabe für einen neuen Vorfall. Ein unmittelbar danach auf dem Media3-Wiedergabethread eingespeister gleichartiger Folgefehler löste den vollständigen Neuaufbau mit bestätigter Bildausgabe aus. Die HLS-Testdaten bestehen aus einem lokal erzeugten zweisekündigen MPEG-TS-Testbild mit Sinuston. Test-APK und Quellarchiv wurden auf die drei vorgesehenen synthetischen Testmedien geprüft.
- `./scripts/build.ps1 -Release` bestand mit Rust-Formatprüfung, Clippy, 86 Rust-Tests, 124 JVM-Tests, Android-Lint, Debug- und Test-APK sowie Release-APK und Release-AAB. Die Core-Herkunft, APK-Ausrichtung, JNI-Exporte und 16-KiB-Ausrichtung aller drei ABIs wurden geprüft. Der standardmäßig ausgenommene Rust-Millioneneinträge-Stresstest lief nicht.
- `./scripts/test-live-recovery.ps1 -Serial emulator-5554 -SkipBuild` bestand mit sieben von sieben Tests. Die Wiederherstellung nach HTTP 404/410 prüft nun auch den Renderer. Die anderen Regressionen decken unter anderem Backoff, Live-EOF und Abbruch geplanter Wiederholungen ab.
- `./scripts/test-playback-controls.ps1 -Serial emulator-5554 -SkipBuild` bestand mit fünf von fünf Tests. Der separate Aufruf von `de.iptv.tv.VodVideoRecoveryTest` über `adb -s emulator-5554 shell am instrument` bestand ebenfalls.
- Beide neuen Gerätetests bestanden auch im separaten Messlauf mit der finalen Test-APK. Beim stehenden Live-Sender betrug die beobachtete Bildlücke einschließlich Neuverbindung 16.081 ms. Beim HLS-Fenstersprung lagen 3.201 ms zwischen lokalem Reparaturversuch und bestätigter Bildrückkehr. Die Werte beziehen sich auf unterschiedliche Störungen und sind Einzelmessungen mit der synthetischen Quelle.
- `./scripts/check-release-tag.ps1 -Tag v0.5.15 -CheckHistory` bestätigte Version und aufsteigenden versionCode. Die deutschen und englischen Release Notes wurden inhaltlich geprüft und mit `./scripts/generate-release-notes.ps1 -Version 0.5.15` erzeugt.

Die Geräteprüfung erfolgte mit lokalen Quellen. Für DASH wurde der gemeinsame Media3-Fehlerpfad implementiert. Ein eigener DASH-Stream, ein physischer Fernseher und ein Dauertest beim Anbieter waren nicht Teil dieser Abnahme. Regulär weitergelieferte Frames mit identischem Bildinhalt lassen sich anhand der Framezähler nicht als Störung erkennen.

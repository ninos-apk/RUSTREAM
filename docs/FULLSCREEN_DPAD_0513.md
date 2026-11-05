# Hoch/Runter im Vollbild (0.5.13)

Datum: 5. November 2026. Zielversion: **0.5.13**, `versionCode` **24**.

## Auftrag und Ausgangslage

Fernbedienungen ohne Channel-Tasten sollen Live-Sender direkt mit Hoch/Runter
wechseln können, ausschließlich im Vollbild. Dieselbe Bedienung ist für Serien
erwünscht. Bestehende Kanaltasten, Vorschau, Listen, Filme und Playeraktionen
bleiben erhalten. Ausgangspunkt: `main`, Version 0.5.12 (23), Tag `v0.5.12`.
Die bereits unversionierte `AGENTS.md` bleibt unverändert außerhalb dieses Commits.

## Plan und Abnahmekriterien

1. Player-Eingaben auf fokussiertes Vollbildvideo und den Wiedergabekontext
   Live/Serie begrenzen. Hoch entspricht Channel + (nächster Eintrag), Runter
   Channel − (vorheriger Eintrag). Vorhandene Katalognavigation wiederverwenden.
2. Wiederholte Key-down-Ereignisse beim Halten unterdrücken; OK öffnet weiter
   die Leiste. Pfeile innerhalb der Leiste bedienen weiterhin deren Fokus.
3. Regressionstests für Live, Serien, Grenzen, Vorschau, Filme, Fokus,
   Wiederholungen und bestehende Channel-Tasten ergänzen.
4. Bedienungsdokumentation, README und geprüfte deutsche/englische Release Notes
   aktualisieren. Vollständigen Release-Build und gezielte Emulatortests ausführen.
5. Diff, Versionskonsistenz und Release Notes prüfen; gezielt lokal committen
   und bei erfolgreichen erforderlichen Prüfungen annotiert taggen. Kein Push.

## Umsetzung

- `PlayerPane.handleKey` fängt Hoch/Runter nur bei Vollbild, Videofokus,
  ausgewähltem Inhalt und aktivierter Live-/Seriennavigation ab. Pro Druck wird
  einmal umgeschaltet; Key-down-Wiederholungen werden konsumiert. Laufendes
  Spulen wird vor einem Wechsel beendet.
- `MainActivity.updateEpisodeNavigation` setzt die Freigabe aus `playContext`,
  unabhängig vom durchstöberten Bereich. Die neuen Aktionen verwenden
  `navigatePlayback`, einschließlich vorhandener Auswahl-/Revision-Prüfungen,
  Such-/Kategorie-/Favoritenkontext und Serien-/Staffelgrenzen.
- OK öffnet die Leiste, Hoch aus der Leiste schließt sie ohne Wechsel. Deren
  Links-/Rechts-Navigation, Zurück und bisherige Channel-/Medientasten bleiben
  erhalten. Filme behalten Hoch/Runter zum Öffnen der Leiste.
- Keine Änderungen an Quellenformat, Nutzerdaten, Rust/JNI, Abhängigkeiten oder
  Player-Lebenszyklus. Keine neuen UI-Texte nötig; vorhandene native Namen und
  deutsche/englische Beschriftungen bleiben bestehen.
- `FullscreenDpadTest` ergänzt echte Fernbedienungsereignisse für Live und Serie,
  beide Richtungen, erste/letzte Position, Staffelgrenze, Wiederholungen, Channel
  +/−, Vorschau, Liste, Leiste, Fokus und Filme. `UiDesignTest` öffnet die Leiste
  im Live-Vollbild jetzt über OK. `test-fullscreen-dpad.ps1` bündelt diese und
  bestehende Fokus-, Serien-, Spul- und Bibliotheksregressionen (neun Tests).
- Der vorhandene `NavigationStateTest` erreicht die fünfte Fixture-Kategorie
  jetzt mit fünf D-Pad-Schritten ab „Alle Kategorien“. Verlauf/Favoriten stehen
  davor; der Screenshot/UI-Baum zeigte nur Live 01–04 im sichtbaren Ausschnitt.
  Seine bisherige Annahme, Live 05 sei nach einem Bereichswechsel bereits
  sichtbar, ließ den ersten Gesamtlauf (8/9 bestanden) und einen isolierten
  Kontrolllauf scheitern. Ein erster Versuch mit fester Adapterposition half
  wegen der zusätzlichen Nutzerbibliothekszeilen nicht. Die App-Navigation
  wurde dafür nicht verändert.
- Im zweiten Gesamtlauf (7/9 bestanden) fehlte zusätzlich dem neuen Test eine
  stabile Startvorbedingung für die Vorschau. Er wartet nun auf die laufende
  Wiedergabe und bestätigt den per Rechts erreichten Videofokus, bevor Runter
  geprüft wird. Bei einem Fehler protokolliert er Fokus und Player-UI-Zustand
  und erstellt einen Screenshot. Die früheren Läufe bleiben lokal unter
  `artifacts/fullscreen-dpad/instrumentation-first-attempt.txt` und
  `instrumentation-second-attempt.txt` nachvollziehbar.
- Der Release-Prüflauf deckte außerdem einen vorbestehenden, veralteten
  Herkunftsnachweis auf: `recovery.rs` und `telemetry.rs` wurden bereits in
  `b9d8cac` (0.5.9) geändert, ohne ihre Manifesthashes nachzuführen. Der damalige
  vollständige Diff wurde geprüft; `git diff b9d8cac HEAD --` für beide Dateien
  sowie ihr Arbeitsbaum-Diff waren leer. `upstream-sha256.json` dokumentiert
  nun diesen vorhandenen Android-Stand; die alten Referenzhashes bleiben
  erhalten. Näheres: [Herkunftsnachweis](UPSTREAM.md). Kein Rust-Code geändert.

## Accessibility: Tastenbedienung und Fokus

Der Skill `better-accessibility` wurde auf die geänderte native Android-Eingabe
angewandt; Web-ARIA ist hier nicht zutreffend. Der Auftrag verbessert die direkte
Bedienung ohne Sondertasten. Aus der bisherigen Möglichkeit, über die Liste zu
wechseln, wird kein fehlender Tastaturpfad und kein WCAG-Verstoß abgeleitet.

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| LOW (Empfehlung) | `app/src/main/java/de/iptv/tv/PlayerPane.kt:333`, `app/src/main/java/de/iptv/tv/MainActivity.kt:1108` | Direktes Zappen erforderte Channel-Tasten; Hoch/Runter öffneten die Leiste. | Hoch/Runter wechseln auf dem Live-/Serien-Vollbildvideo; OK und Leistenfokus bleiben erreichbar. | Tastenbedienung im Sinne von WCAG 2.1.1; erleichtert die konkrete Aufgabe auf einfachen D-Pad-Fernbedienungen. |

## Prüfnachweise und Grenzen

Erfolgreich ausgeführt:

- `./scripts/build.ps1 -Release`: Rust-Formatprüfung und Clippy erfolgreich;
  86 Rust-Tests bestanden. Der bestehende opt-in-Stresstest
  `million_entries_over_128_mib_remain_paged_and_reopenable` wurde wie im
  Standardlauf vorgesehen ignoriert (nicht ausgeführt).
- 117 JVM-Unit-Tests, Android-Lint, Debug-APK, Test-APK, R8-Release-APK und
  Release-AAB erfolgreich. Auch Core-Manifest, private Testassets,
  APK-Ausrichtung, JNI-Exports und ELF-LOAD-Ausrichtung mindestens 16 KiB für
  alle drei ABIs bestanden. Log: `artifacts/fullscreen-dpad-build-verified.log`.
- Zuvor verhinderte ein geerbtes Anführungszeichen im Windows-PATH den Start
  der JVM-Testprozesse (`ClassNotFoundException: Files\\Tailscale...`). Nur im
  Build-Prozess wurde `$env:PATH = $env:PATH.Replace('"', '')` gesetzt, dann der
  projektlokale Gradle-Daemon mit `./gradlew.bat --stop` neu gestartet und der
  vollständige Build wiederholt. Keine System-/Projektkonfiguration geändert.
- APK-Metadaten per `aapt dump badging` geprüft: `de.iptv.tv`, Version 0.5.13,
  Code 24. Die durch den Build kopierte `artifacts/RUSTREAM-IPTV-debug.apk` ist
  SHA-256-identisch mit der gebauten Debug-APK.

`./scripts/test-fullscreen-dpad.ps1 -Serial emulator-5554 -SkipBuild`:
**9/9 Tests bestanden**, keine übersprungenen Tests, 96,192 Sekunden im
abschließenden Gesamtlauf. Log: `artifacts/fullscreen-dpad/instrumentation.txt`.
Testgerät: vorhandener Google-TV-Testemulator, API 36, x86_64, 16-KiB-Seiten;
ohne Datenreset gestartet. Tests verwenden ausschließlich synthetische Quellen
und stellen Quellenliste sowie ihre temporären Sprach-/Schriftänderungen wieder her.
Der lokale Screenshot `artifacts/fullscreen-dpad-controls.png` wurde visuell
geprüft: sichtbarer Fokus, lesbare Aktionsbeschriftung und vollständig sichtbare
Playerleiste. Native Namen und Button-Rollen wurden im Code geprüft.

Screenreader-Sprachausgabe/TalkBack und echter Fire TV Stick: **Not verified**.
Keine Aussage über einen vollständigen Accessibility-Audit der gesamten App.

## Abschlussprüfung

Version 0.5.13 / Code 24 mit `check-release-tag.ps1 -Tag v0.5.13 -CheckHistory`
gegen alle bisherigen Versionstags geprüft. Deutsche und englische Release
Notes inhaltlich geprüft und mit `generate-release-notes.ps1 -Version 0.5.13`
erzeugt. Abschließendes `git diff --check` erfolgreich; Änderungen auf Auftrag,
Tests, Version und dokumentierte Prüfnachweise begrenzt. Keine privaten Quellen,
Zugangsdaten, generierten APKs oder Toolchains im Commit. Die vorher vorhandene
unversionierte `AGENTS.md` bleibt erhalten. Lokaler Commit und annotierter Tag
`v0.5.13` werden nach diesen Prüfungen erstellt; kein Push und keine Veröffentlichung.

**Approve** für die geprüften geänderten D-Pad-/Fokuspfade; keine offenen HIGH-Funde
in diesem Umfang. Die ausdrücklich nicht verifizierten Bereiche bleiben offen.

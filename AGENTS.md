# Projektanweisungen für Coding-Agenten

## Rolle und Geltungsbereich

Du arbeitest als **Senior Android-TV-Engineer und Entwickler** an RUSTREAM IPTV PLAYER für Android TV und Google TV. Übernimm die technische Verantwortung von der Planung bis zum lokal geprüften Commit mit passendem Versionstag. Kommuniziere mit dem Nutzer auf Deutsch, klar und knapp.

Diese Vorgaben gelten für das gesamte Repository. Konkrete Anweisungen des Nutzers haben Vorrang. Führe autorisierte Arbeiten selbstständig aus; frage nur nach, wenn eine wesentliche Entscheidung nicht aus Projekt und Auftrag hervorgeht.

Der folgende Ablauf gilt für künftige Änderungsaufträge. Die erstmalige Erstellung dieser `AGENTS.md` umfasst ausschließlich diese Datei; sie löst weder Versionsanhebung noch Build, Commit oder Tag aus.

## Verbindlicher Ablauf für jeden Änderungsauftrag

1. **Prüfen und planen:** Lies betroffene Dateien und Dokumentation. Prüfe `git status`, Branch, App-Version und vorhandene Tags. Kündige einen kurzen Plan mit Umfang, betroffenen Bereichen und vorgesehenen Prüfungen an.
2. **Plan dokumentieren:** Lege vor der Umsetzung einen aufgabenbezogenen Bericht unter `docs/` an oder ergänze einen passenden bestehenden Bericht. Halte Auftrag, Ausgangslage, Umsetzungsschritte, Zielversion und Abnahmekriterien fest.
3. **Umsetzen:** Implementiere die angefragte Änderung vollständig im vorhandenen Stil. Begrenze den Umfang auf den Auftrag. Berücksichtige Fehlerfälle, Abbruch, Lebenszyklus und bestehende Nutzerdaten.
4. **Version und Dokumentation aktualisieren:** Vergib für jeden abgeschlossenen Änderungsauftrag eine neue App-Version. Aktualisiere Versionsdaten, relevante Bedienungs-/Technikdokumentation, den README-Eintrag und zweisprachige Release Notes. Dokumentiere Entscheidungen und Einschränkungen im Aufgabenbericht.
5. **Bauen und testen:** Führe den vorhandenen Projekt-Build und passende Tests tatsächlich aus. Behebe durch die Änderung verursachte Fehler und wiederhole betroffene Prüfungen. Dokumentiere Befehle und tatsächliche Ergebnisse; kennzeichne nicht ausgeführte Prüfungen ausdrücklich.
6. **Abschluss prüfen:** Prüfe Diff, unerwartete Dateien, Geheimnisse, Versionskonsistenz und Release Notes. Ergänze den Bericht um endgültige Umsetzung und Prüfnachweise, bevor du committest.
7. **Lokal committen:** Stage gezielt die zum Auftrag gehörenden Dateien und erstelle einen aussagekräftigen Commit, beispielsweise `feat(tv): ...`, `fix(player): ...` oder `docs(workflow): ...`. Plan, Umsetzung, Versionsanhebung und Prüfdokumentation gehören zum abgeschlossenen Auftrag.
8. **Lokal taggen:** Erstelle nach erfolgreichen erforderlichen Prüfungen einen annotierten Tag `v<versionName>` auf dem fertigen Commit. Kontrolliere anschließend Tag-Ziel, Commit und Arbeitsverzeichnis.
9. **Bescheid geben:** Nenne Ergebnis, Build-/Teststatus, neue Version mit `versionCode`, Commit, Tag und verbleibende Grenzen. Sage ausdrücklich, dass nichts gepusht wurde und der Nutzer manuell pushen kann.

Reine Verständnisfragen und Statusabfragen ohne Änderungsauftrag beantwortest du direkt; sie erzeugen keine künstlichen Änderungen, Commits oder Versionen. Bei blockiertem Build oder fehlgeschlagenen erforderlichen Tests dokumentierst und meldest du den konkreten Grund. Erstelle dann keinen Fertigstellungs-/Release-Tag und behaupte keinen erfolgreichen Abschluss.

## Projekt und Architektur

- `app/`: Kotlin, native Android Views, Fernbedienungsbedienung und Media3; Paket-ID `de.iptv.tv` beibehalten.
- `crates/iptv-core/`: Rust-Core für M3U, Suche, Xtream, XMLTV und Recovery.
- `crates/iptv-jni/`: JNI-Verbindung zwischen Android und Rust; Änderungen an beiden Seiten kompatibel halten.
- `app/src/test/`: JVM-Unit-Tests; `app/src/androidTest/`: Geräte- und UI-Tests.
- `scripts/`: bestehende Setup-, Build-, Prüf-, Emulator- und Release-Werkzeuge bevorzugen.
- `docs/`: Architektur, Aufgabenberichte, Prüfnachweise und Release Notes.
- `artifacts/`: lokale generierte Ausgaben; keine APKs, Toolchains oder Build-Caches committen.

Die Windows-App im Nachbarordner `../IPTV Player` bleibt ohne ausdrücklichen Auftrag unverändert. Bestehende Skripte dürfen ihre eingerichtete Toolchain wie vorgesehen wiederverwenden. Android unterstützt mindestens API 23 und die ABIs `arm64-v8a`, `armeabi-v7a` und `x86_64`. Prüfe aktuelle SDK-, NDK- und Dependency-Werte im Build; aktualisiere sie nur im erforderlichen Auftragsumfang.

## Qualitätsmaßstab für Android TV

- Alle Aktionen müssen mit D-Pad, OK und Zurück erreichbar sein. Prüfe sichtbaren Fokus, Fokusreihenfolge, Fokuswiederherstellung und das Vermeiden von Fokusfallen.
- Berücksichtige TV-Leseabstand, lange Texte, große Systemschrift, RTL und deutsche sowie englische UI-Texte. Ergänze beide Ressourcenvarianten konsistent.
- Bewahre Wiedergabezustand, Favoriten, Verlauf, gespeicherte Quellen und Einstellungen bei Updates. Vermeide unnötige Player-Neustarts und Arbeit auf dem UI-Thread.
- Beachte die bestehenden Regeln für Live-TV, Filme und Serien. Prüfe bei Player-Änderungen insbesondere Pufferung, Recovery, Seeking und Lebenszyklus.
- Nutze Timeouts, begrenzte Datenmengen und abbrechbare Hintergrundarbeit. Gib hilfreiche Fehler aus, ohne Zugangsdaten oder private Streamadressen offenzulegen.
- Ergänze gezielte Tests für neue Logik und behobene Fehler, wenn sie eine sinnvolle Regression absichern. Reine Dokumentationsänderungen benötigen keine neuen Implementierungstests; der vereinbarte Build-/Prüflauf bleibt bestehen.

## Build und Prüfnachweise

Arbeite vom Repository-Stamm aus mit PowerShell und der vorhandenen projektlokalen Umgebung:

```powershell
./scripts/build.ps1 -Release
```

Das Skript führt Rust-Formatprüfung, Clippy, Rust-Workspace-Tests, JVM-Unit-Tests, Android-Lint, Debug-APK, Test-APK, Release-APK und Release-AAB aus. Es prüft außerdem den aufgezeichneten Core-Stand, private Testassets sowie APK-/JNI-/16-KiB-Ausrichtung. Prüfe den aktuellen Skriptinhalt, falls sich der Ablauf ändert. Release-Ausgaben sind lokal unsigniert; das bedeutet keine Veröffentlichung.

Bei Änderungen an UI, Fokus, Navigation, Wiedergabe oder Geräteintegration zusätzlich passende Instrumentationstests bzw. vorhandene `scripts/test-*.ps1` auf einem verfügbaren TV-Testemulator oder freigegebenen Testgerät ausführen. Wenn alle verbundenen Geräte ausdrücklich als Testgeräte vorgesehen sind, ist auch Folgendes möglich:

```powershell
./scripts/build.ps1 -Release -ConnectedTests
```

Wähle bei gezielten Gerätetests die Seriennummer ausdrücklich. Lösche keine App-Daten und setze keine Geräte ohne entsprechende Autorisierung zurück. Ist ein notwendiger Gerätetest nicht möglich, dokumentiere und melde den fehlenden Nachweis; stelle ihn niemals als bestanden dar.

Der Aufgabenbericht enthält mindestens Datum, Ziel, Plan, tatsächliche Änderungen, Version, ausgeführte Prüfungen mit Ergebnissen und offene Grenzen. Generierte Logs bleiben unter `artifacts/`; fasse relevante Ergebnisse im versionierten Bericht zusammen. Nach reinen Berichtsänderungen genügt eine erneute Dokumentations-/Diff-Prüfung, sofern sich der bereits geprüfte Build-Inhalt nicht geändert hat.

## Versionen, Release Notes und Tags

- Maßgeblich sind `versionName` und `versionCode` in `app/build.gradle.kts`.
- Wähle ohne andere Vorgabe die nächste freie Patch-Version; erhöhe `versionCode` gegenüber allen bisherigen Versionstags. Bei ausdrücklich angefragter größerer Version nutze diese und halte beide Werte aufsteigend.
- Erstelle `docs/releases/<versionName>.json` nach vorhandenem Schema mit passender `version`, deutschen und englischen Einträgen und `reviewed: true`. Setze den Prüfstatus erst nach eigener inhaltlicher Prüfung beider Sprachen. Beschreibe nur tatsächliche Änderungen; benenne Prozess-/Dokumentationsänderungen offen.
- Prüfe Version und Notizen vor dem Commit lokal, beispielsweise mit der zuvor ermittelten Zielversion:

```powershell
$releaseVersion = '0.5.13' # Beispiel: jeweils durch die neue Zielversion ersetzen
./scripts/check-release-tag.ps1 -Tag "v$releaseVersion" -CheckHistory
./scripts/generate-release-notes.ps1 -Version $releaseVersion
git diff --check
```

- Erstelle nach dem Commit einen annotierten Tag exakt im Format `v<versionName>`. Überschreibe oder verschiebe keine bestehenden Tags.
- Prüfe, dass der Tag auf den Abschlusscommit zeigt und dessen versionierte Build-Datei zur Tag-Version passt. Prüfe den abschließenden Arbeitsstand; fremde, bereits vorher vorhandene Änderungen müssen erhalten bleiben und dürfen nicht mitcommittet werden.
- Die GitHub-Release-Automation verlangt einen über `origin/main` erreichbaren Tag-Commit. Beim späteren manuellen Push muss der Nutzer deshalb zuerst den Commit auf `main` verfügbar machen und danach den zugehörigen Tag pushen. Die lokale Vorbereitung erfolgt ohne Push; `-RequireMain` kann vor diesem manuellen Push für den neuen lokalen Commit noch nicht erfolgreich sein.

## Git und Übergabe

**Niemals automatisch pushen.** Der Nutzer pusht Commits und Tags manuell. Starte auch keine Veröffentlichung, keinen GitHub-Release und keinen Remote-Workflow als Ersatz. Die Erstellung des lokalen Commits und des lokalen annotierten Tags ist durch diesen Projektauftrag autorisiert und erfordert keine erneute Bestätigung.

Übernimm oder verwerfe keine fremden Änderungen. Verwende kein pauschales `git add .`, kein `reset --hard`, keinen Force-Push und kein ungefragtes Umschreiben bestehender Commits. Committe keine Provider-Playlists, Zugangsdaten, Tokens, privaten URLs oder Signierschlüssel. Verwende synthetische Testdaten; das Test-APK darf nur vorgesehene sichere Assets enthalten.

Die Abschlussmeldung nennt kompakt:

- Was umgesetzt und dokumentiert wurde.
- Welche Builds und Tests bestanden haben und welche Nachweise fehlen.
- App-Version, `versionCode`, kurzen Commit-Hash und genauen Tag-Namen.
- **Nicht gepusht; bereit für deinen manuellen Push.**

Wenn der Nutzer Push-Befehle wünscht, gib nur passende explizite Befehle für den tatsächlichen Branch und genau diesen Tag an. Führe sie nicht selbst aus.

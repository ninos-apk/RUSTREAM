# Filmdetails und Bewertungen

Aktueller Layout- und Bilderstand: [Filmdetails: Layout und Bilder](MOVIE_DETAILS_LAYOUT_AND_IMAGES.md). Die untenstehenden ursprünglichen Testergebnisse dokumentieren den Erstlauf; aktuelle Zahlen stehen im verlinkten Bericht.

Umsetzung vom 19. Oktober 2026 für die native Android-TV-App. Grundlage: der bereitgestellte Umsetzungsplan vom selben Tag. Vorhandene lokale Änderungen am Filmkopf, am Emulatorstarter und an der Nutzerbibliothek wurden beibehalten. Kein Versionswechsel und keine Veröffentlichung.

## Bedienung

- Eine Filmkarte öffnet weiterhin den bisherigen Filmkopf mit Wiedergabe rechts. Direkt unter der Wiedergabeaktion steht **Filmdetails**. Das Menü einer Filmkarte (langes OK oder Menü) bietet Abspielen und darunter Filmdetails ebenfalls direkt an.
- Filmdetails öffnet eine eigene, bildschirmfüllende, scrollbar navigierbare Ansicht. Öffnen startet keine Wiedergabe. Poster, Titel, Jahr/Laufzeit und Favorit bleiben während des Ladens nutzbar; darunter stehen Abspielen, Bewertungen, vollständige Beschreibung, verfügbare Filmangaben und Besetzung. Große Ansichten haben einen horizontalen Kopf, enge Ansichten einen vertikalen.
- Zurück schließt die Detailansicht und fokussiert ihre aufrufende Aktion. Beim Rückweg in das Grid werden die geladenen Zeilen, Kategorie, Suchfilter, Layoutposition und Filmfokus wiederverwendet. Bei geänderten Favoritenlisten wird die Liste neu abgefragt. Wiedergabe aus Filmdetails nutzt den vorhandenen Resume-/Player-Aufruf; Zurück aus dem Player führt wieder zu Filmdetails.
- Film- und Serienkarten zeigen die Hauptbewertung direkt unter dem Poster. Dieser kompakte Kartenaufbau vermeidet Überschneidungen mit dem Favoritenstern und kann mit großer Systemschrift wachsen. Die Bewertung gehört zum zugänglichen Kartenzustand und erhält keinen eigenen D-Pad-Stopp.
- **Einstellungen → TMDB** nimmt einen eigenen *API Read Access Token* entgegen. Leer speichern deaktiviert die API. Der Token wird mit dem vorhandenen Android-Keystore-Verfahren verschlüsselt und vom Backup ausgeschlossen gespeichert. Er wird weder in die APK eingebaut noch in der Navigation übergeben.
- **TMDB-Zuordnung ändern** zeigt Suchkandidaten, nimmt eine explizite TMDB-ID entgegen oder kehrt zur automatischen Zuordnung zurück. Bei Serien ist die Aktion im Kartenmenü erreichbar und gilt für die ganze Serie. Korrekturen sind an Quelle, stabile Katalogkennung und Medienart gebunden; sie überstehen einen Reimport.

## Datenweg und Grenzen

`Metadata.kt` enthält Bewertungszustände, konservative Titel-/Jahrzuordnung und den gemeinsamen Cache. `MetadataService.kt` übernimmt Anbieter-/TMDB-Adapter und Transport. `MetadataStore.kt` verwendet AES-GCM/Keystore und AtomicFile wie die bestehenden Quellenspeicher. `MovieDetailsView.kt` baut die neue native Ansicht aus bestehenden `TvUi`-Bausteinen. Der Rust-Core erhält ausschließlich einen privaten Adapteraufruf `metadata`; Browse-/Entry-Daten und Routen enthalten weiterhin keine Streamadressen.

Xtream-Listen übernehmen ihre Metadaten bei der bestehenden Verarbeitung in den verschlüsselten Katalog. Numerische Strings und Zahlen werden normalisiert. Das löst keine weitere Netzwerkanfrage beim Import aus. Bereits vorhandene Kataloggenerationen erhalten diese zusätzlichen Felder erst mit dem nächsten regulären Refresh/Reimport. Film-Einzelangaben kommen beim Detailaufruf aus `get_vod_info`; Server, Port, Basispfad und kodierte Accountsegmente werden über einen URL-Parser behandelt. Eine `/series/`-Episodenadresse wird niemals als Serienkennung verwendet.

Die sichtbaren Karten plus höchstens eine angrenzende Reihe bilden die Warteschlange. Zwei Arbeitsläufe bedienen maximal 48 Karten pro sichtbarem Ausschnitt. Scrollen und Ansichtswechsel verwerfen wartende Arbeit; HTTP-Aufrufe sind abbrechbar. Es gibt keinen Metadatenlauf über den gesamten importierten Katalog. Der HTTP-Adapter begrenzt aktive Aufrufe auf zwei, Antworten auf 2 MiB, Verbindungs-/Lese-/Gesamtzeit auf 5/8/15 Sekunden und folgt keinen Redirects. Bei 429 wird `Retry-After` als Sekundenwert oder HTTP-Datum gelesen, pro Origin pausiert und höchstens einmal nach kurzer Pause erneut versucht. Lange Pausen führen zu einem nutzbaren Fehlerzustand; Abspielen/Favoriten bleiben unabhängig.

Film und Serie verwenden getrennte TMDB-Namensräume (`movie`/`tv`). Sprachabhängige Dokumente und sprachunabhängige Bewertungen werden getrennt gespeichert. Aktualisierte TMDB-Ratings versorgen auch andere lokale Varianten und Sprachen derselben Zuordnung. Detail- und Gridanfragen teilen vorhandene Daten und feste Request-Locks. Film-Credits werden erst beim Detailaufruf ergänzt.

Bewertungen bleiben ungerundet gespeichert. Die Anzeige verwendet eine Nachkommastelle, Quelle und Ursprungsskala. TMDB hat Vorrang; Anbieterwerte bleiben separat. Xtreams `rating` wird als Zehner-, `rating_5based` als Fünferskala behandelt. Nicht dokumentierte M3U-Bewertungsfelder werden nicht als verlässliche Anbieterbewertung ausgegeben. Keine Mittelung und keine erfundenen IMDb-/Rotten-Tomatoes-Werte. Null Stimmen bedeutet „Noch nicht bewertet“; fehlende/ungültige Werte bedeuten „Keine Bewertung verfügbar“. Ein Nullwert mit nachgewiesenen Stimmen bleibt eine echte Bewertung.

Automatische Zuordnung verlangt einen eindeutigen passenden Titel, gegebenenfalls auch das Jahr. Anbieter-TMDB-IDs werden ebenfalls darauf geprüft. Mehrdeutige oder widersprüchliche Kandidaten bleiben offen. Ohne Token gibt es keine TMDB-Netzwerkaufrufe. Ein neuer M3U-Titel kann erst nach erfolgreicher Suche eine Bewertung haben.

Cache: höchstens 1.000 Dokumente und etwa 16 MiB Textdaten im Speicher; manuelle Korrekturen werden davon unabhängig erhalten. Ratings und Anbieterangaben gelten 24 Stunden, erfolglose/mehrdeutige Zuordnungen sechs Stunden, Bildkonfiguration sieben Tage. Filmtext/Credits werden in dieser Umsetzung gemeinsam mit dem 24-Stunden-Dokument aktualisiert; die getrennte siebentägige Text-TTL aus dem Plan ist kein notwendiger erster Grenzwert. Fehlgeschlagene Aktualisierung erhält vorhandene Daten und kann erneut versucht werden. Ohne deutsche Beschreibung gibt es einen gekennzeichneten englischen Ersatz.

Filmangaben: Originaltitel, Veröffentlichung, Laufzeit, Genres, Regie, Drehbuch, Produktionsländer/-firmen, Originalsprache, DE-Altersfreigabe (Kinofassung bevorzugt), positive Budget-/Umsatzwerte in USD. Ausländische Freigaben werden nicht als deutsche ausgegeben. Vorhandene YouTube-Trailer öffnen nach Betätigung die passende externe App. Anbieter-Besetzungsnamen dienen ohne Credits als Ersatz; TMDB-Credits verbinden Personenkennung, Namen, Rolle, Reihenfolge und Foto. Porträts nutzen direkte Anbieteradressen oder öffentliche TMDB-Bildpfade mit `w185` ohne authentifizierte Bildkonfiguration. Fehlen Bildpfade, wird die öffentliche Besetzungsseite zur bekannten Film-ID geprüft. Bilder werden erst bei Sichtbarkeit gebunden und erhalten bei fehlendem Bild einen Platzhalter. Weitere Besetzungsmitglieder sind in Zwölferschritten erreichbar.

Favoriten verwenden weiterhin ausschließlich `UserLibrary` und die stabilen lokalen Schlüssel. `setFavorite(..., desired)` ist idempotent. Während des Schreibens wird eine weitere Auslösung desselben Favoriten unterdrückt. Bei Speicherfehler wird der Zustand zurückgenommen und eine sichtbare Wiederholungsmöglichkeit genannt. Das betrifft beide Ansichten und ist unabhängig von Metadatenzuordnungen.

## Reproduzierbare Prüfung

Auf einem **Testemulator** ausführen. Die Tests verwenden lokale HTTP-Antworten, generierte Poster und das vorhandene synthetische Video; keine privaten Anbieter oder echten TMDB-Zugangsdaten. Tests verändern vorübergehend Quellen, Bibliothek, Sprache und Schriftgröße. Die neuen Tests sichern Quellen und Bibliothek zusätzlich verschlüsselt auf dem Emulator, um beim nächsten Lauf auch nach einem Prozessabbruch wiederherstellen zu können.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
. ./scripts/env.ps1
cargo test --locked --workspace
./scripts/test-movie-metadata.ps1
# Bei bereits aktuellen APKs:
./scripts/test-movie-metadata.ps1 -SkipBuild
```

Der lokale PATH enthielt ein störendes Anführungszeichen. Das Testskript entfernt es nur aus seinem Prozess. Gradle/ADB und die lokalen HTTP-Testserver benötigen die üblichen lokalen Socket-Verbindungen; eine Netzwerk-Sandbox kann dafür eine Ausführung außerhalb der Sandbox erfordern.

| Prüfung | Nachweis / Status |
| --- | --- |
| Kotlin-Unit-Tests | 79 Tests, 0 Fehler; davon 26 neue Metadatentests. `artifacts/movie-metadata-build.log`, `app/build/test-results/testDebugUnitTest/` |
| Rust | 82 bestandene Workspace-Tests, 1 bereits als ignoriert markierter Test; einschließlich neuer verschlüsselter Anbieter-Metadatenprüfung. `cargo fmt --all -- --check` und `cargo clippy --locked --workspace --all-targets -- -D warnings` erfolgreich. `artifacts/movie-metadata-rust-tests.log`, `artifacts/movie-metadata-clippy.log` |
| Android Build und Lint | `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`; erfolgreich, 0 Fehler / 71 bestehende Warnungen |
| Neue Android-Tests | Gridabruf ohne Detailklick, Serienrating, kein Creditsabruf im Grid, Detailbutton-Reihenfolge, Favoriten-Doppelauslösung/Persistenz, Rückfokus, vollständige Besetzung, echte Wiedergabe aus Details und Rückkehr dorthin, 100/200 % Schrift; zusätzlich 320-dp-/RTL-Langtextprüfung. Gesamtlauf mit acht Tests: `artifacts/movie-metadata-final-device.log` |
| Regression | ContentLibraryTest (M3U und Xtream), UserLibraryUiTest (Favoriten, Wiedergabe, Fortsetzen nach Neustart), NavigationStateTest (zwei Fälle), ContentVisualTest; abschließender Lauf im selben Log |
| Bilder | `artifacts/movie-metadata-screenshots/`; normale/große Film- und Seriengrids, Details, Besetzung, enge RTL-Ansicht |

Installierbare Debug-Testfassung: `artifacts/RUSTREAM-IPTV-filmdetails-debug.apk`, SHA-256 daneben als `.apk.sha256`. Die APK entspricht dem bestandenen Gesamtlauf. Screenshot-Wiederholung der engen RTL-Ansicht nach vollständiger Dialoganimation: `artifacts/movie-metadata-narrow-device.log`. Keine Veröffentlichung; Versionskennung bleibt 0.5.6.

Die Unit-Tests decken zusätzlich Wertebereiche, NaN, fehlende Werte, echte Nullbewertung, Quellenpriorität, Ursprungsskalen, Titel-/Jahrwidersprüche, Mehrdeutigkeit, Medienart-/Sprachtrennung, manuelle Korrekturen, Cachebegrenzung, gemeinsame Ratingaktualisierung, gebündelte Aufrufe, Parallelitätslimit, Sprachersatz, ungültiges JSON, falsche Ergebnis-ID, Timeout, Antwortlimit, Redirects und kurze/lange 429-Pausen ab.

Die Emulatorprüfung fand einen Fehler beim Abbrechen einer gerade zugestellten HTTP-Antwort: `Response.close()` konnte auf dem Android-Hauptthread Netzwerkdaten verwerfen. Die Bereinigung läuft jetzt auf dem Netzwerkexecutor; Antwortverarbeitung erfolgt auf `Dispatchers.IO`. Der Testserver toleriert erwartete Socket-Abbrüche beim schnellen Ansichtswechsel.

**Nicht verifiziert:** echte TMDB-Anfragen und reale Personenfotos mit einem persönlichen Token, konkrete private Anbieterantworten, ein physisches TV-Gerät, TalkBack-Sprachausgabe, reale 257.468-Einträge-Metadatenlast. Der begrenzte Abrufpfad wird mit synthetischen Listen geprüft; ein vollständiger Massenscan wird nicht gestartet. TMDB-Attribution steht in der Detailansicht. Vor einer öffentlichen Distribution sind die zum Nutzungsmodell passende TMDB-Freigabe und die offizielle Logo-Attribution zu prüfen.

## UI-Prüfung mit better-interface

Umfang: der neue Ablauf Grid → Filmaktionen → Filmdetails → Favorit/Play/Besetzung → Zurück. Native Kotlin-Views mit bestehenden `TvUi`-Tokens und Androids Textgrößen; keine neue UI-Bibliothek. Projektkonventionen: README, `docs/MOVIE_LAYOUT.md`, `docs/SERIES_LAYOUT.md`, `TvUi.kt`; keine AGENTS.md gefunden.

| Domäne | Inspizierte Evidenz | Ergebnis |
| --- | --- | --- |
| Accessibility | Native Buttons, zugängliche Kartenzustände, persistente Fokusziele, sichtbare `TvUi`-Ringe, D-Pad-/Vergrößerungstests | Geprüfter Umfang; TalkBack-Ausgabe nicht verifiziert |
| Layout | Relative Abstände, flexible Texthöhen, scrollbar eigenständige Route, kompakte Ratingzeile, gemessene Posterreserve, 320 dp und RTL | D-Pad- und Langtexttests bestanden; Screenshots siehe oben |
| Writing | `values/metadata.xml`, `values-en/metadata.xml`: fehlend/unbewertet/laden/Fehler/Zuordnung getrennt, Fehler mit nächstem Schritt | Statisch geprüft |
| Typography | sp-Werte, vollständige Detailtexte mit etwa 70 Zeichen Zeilenmaß und 1,5-fachem Zeilenabstand, deutsche Dezimalanzeige, 100/200-%-Tests | Statisch und im Emulator geprüft |
| Colors | Bestehende deckende Flächen, Weiß/Muted/Accent aus `TvUi`, Bewertungen zusätzlich als Text | Berechnet: Weiß/Background 19,05:1, Muted/Background 6,91:1, Weiß/Control 14,62:1, Weiß/Focused 11,44:1; Accent-Fokusring/Focused 7,06:1. Sichtprüfung der deckenden Flächen |
| UI | Bestehende Oberflächen und Fokusformen, keine zusätzliche Bewegung, sichtbare Scrollleiste, Porträtplatzhalter | Statisch und im Emulator geprüft |

| Severity | Domäne | Ort | Vorher | Nachher | Warum |
| --- | --- | --- | --- | --- | --- |
| HIGH, behoben | Layout | `MainActivity.kt`, `PosterCardLayout` und Kartenbewertung | Der zusätzliche Bewertungsblock schob im realen 200-%-Regressionstest 44 px des ersten Filmtitels aus dem sichtbaren Grid. | Text wird zuerst vollständig gemessen; nur die Posterfläche schrumpft um den fehlenden Platz. `FIT_CENTER` erhält das Seitenverhältnis des Bildes. | Titel und Bewertung bleiben erreichbar; WCAG 1.4.4. Der bestehende Test prüft weiterhin die vollständige Sichtbarkeit des Titels und jetzt das tatsächlich dargestellte Bildverhältnis. |
| MEDIUM, behoben | Typography | `MovieDetailsView.kt`, `readingText` | Beschreibung konnte auf großen Fernsehern über die gesamte Breite laufen. | Etwa 70 Zeichen Zeilenmaß, 1,5-facher Zeilenabstand, vollständiger Text im Scrollbereich. | Lange Absätze bleiben lesbar und skalieren mit der Systemschrift. |

Asynchrones Laden ersetzt die Aktionsbuttons nicht und kann den Fokus nicht auf einen neu gebauten Button versetzen. Vollständige Texte und Besetzung haben einen Scrollbalken bzw. eine beschriftete Weitere-Aktion. Die Ratingfläche liegt unter dem Poster und kollidiert nicht mit dem Favoritenstern. Besetzungszeilen zeigen Namen und Rolle mit separat sichtbarem Fokus; echte Personenfotos bleiben mangels Token ausdrücklich unverifiziert.

**Approve** für den hier geprüften nativen Ablauf, die geprüften Schriftgrößen und die 320-dp-/RTL-Konfiguration. Keine offenen HIGH-Befunde in diesem Umfang. TalkBack-Ausgabe und physische TV-Geräte sind nicht freigeprüft; dies ist keine globale WCAG-Konformitätsaussage.

## Primärquellen

- [TMDB Movie Details](https://developer.themoviedb.org/reference/movie-details) und [TV Series Details](https://developer.themoviedb.org/reference/tv-series-details): getrennte Medienendpunkte, Sprache und Append-to-response.
- [Movie Credits](https://developer.themoviedb.org/reference/movie-credits), [Image Basics](https://developer.themoviedb.org/docs/image-basics): Personenangaben und Bildkonfiguration.
- [Search Movie](https://developer.themoviedb.org/reference/search-movie), [Movie Release Dates](https://developer.themoviedb.org/reference/movie-release-dates): Suche, Jahr und regionale Freigaben.
- [Rate Limiting](https://developer.themoviedb.org/docs/rate-limiting), [FAQ](https://developer.themoviedb.org/docs/faq): 429 und Attribution/Nutzung.

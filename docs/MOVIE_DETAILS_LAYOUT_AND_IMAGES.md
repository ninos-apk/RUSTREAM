# Filmdetails: Layout und Bilder

Aktualisierung vom 19. Oktober 2026. Verwendete Skills: `better-layout`, `better-accessibility` und abschließend `better-interface` mit seinen übrigen Fachskills. Native Android-TV-Ansicht; die Web-Beispiele der Skills wurden auf Android-Views, AccessibilityNodeInfo und D-Pad übertragen.

## Änderungen

- Abspielen steht im Filmkopf neben einem kompakten Favoritenbutton. Ein gezeichnetes Play-Symbol ergänzt die sichtbare Beschriftung.
- Favoritenbutton: 44 × 44 dp, weißer Sternumriss oder gefüllter gelber Stern (`#FFD54F`). Der native Button meldet Aktionsname, ausgewählten Zustand und „In Favoriten“ / „Nicht in Favoriten“. Der Formwechsel ergänzt die Farbe. Der vorhandene sichtbare Fokusrahmen bleibt erhalten.
- Besetzung: responsive Karten mit Foto über Name und Rolle. Ein bis sechs Spalten anhand der tatsächlich verfügbaren Breite und Schriftvergrößerung; keine abgeschnittenen Namen. Weitere Einträge bleiben in Zwölferschritten erreichbar.

## Warum die Personenfotos fehlten

Die bisherige Ansicht setzte eine authentifizierte TMDB-Bildkonfiguration voraus. Ohne Token wurde keine Fotoadresse erzeugt. Außerdem verwarf die Anbieternormalisierung strukturierte Personen und ihre direkten Bildadressen. Die bisherigen UI-Fixtures enthielten überhaupt keine Porträtpfade und konnten diesen Fehler deshalb nicht erkennen.

Die neue Auflösung verwendet:

1. Strukturierte Anbieterbesetzung (`cast`, `actors` oder `credits.cast`) mit direkten Bildadressen oder `profile_path`.
2. Vorhandene TMDB-Credits mit öffentlichen Bildpfaden, ohne zusätzlichen Konfigurationsabruf.
3. Wenn nur Namen vorhanden sind: beim Öffnen der Filmdetails die öffentliche TMDB-Besetzungsseite zur bekannten Anbieter-/manuellen Film-ID. Kein persönlicher API-Token und kein fremder eingebauter Schlüssel. Titel/Jahr oder mindestens zwei übereinstimmende Anbieternamen müssen die Zuordnung bestätigen. Crew wird ausgeschlossen.

Der konkrete untersuchte Anbieter liefert `get_vod_info.info.cast` als kommagetrennte Namen und zusätzlich `tmdb_id`, aber keine einzelnen Personenbildadressen. Deshalb ist die dritte Stufe für diese Quelle nötig. Ohne Film-ID oder bei ungeklärter Zuordnung bleiben Anbieternamen und Platzhalter erhalten; es gibt keine geratene Bildsuche anhand eines Namens.

Öffentliche Besetzungsseiten werden ausschließlich auf der Detailroute angefragt, nicht für sämtliche Filmkarten. HTML-Parsing läuft auf einem IO-Dispatcher. Es gelten die vorhandenen Grenzen: zwei gleichzeitige Metadatenanfragen, maximal 2 MiB, 5/8/15 Sekunden Verbindungs-/Lese-/Gesamtzeit je HTTP-Aufruf. Bis zu drei Redirects derselben Origin erlauben die kanonische TMDB-Titeladresse; Schema, Host und Port müssen gleich bleiben. Authentifizierte API-Aufrufe folgen weiterhin keinen Redirects. Öffentliche Ergebnisse und Fehlschläge werden sechs Stunden zwischengespeichert; fertige lokale Details bis zu 24 Stunden. Änderungen am HTML oder eine nicht verfügbare Seite ergeben einen Ersatz mit Anbieternamen. Es gibt keine Umgehung von HTTP 403 oder anderen Zugriffssperren. Neue Cache-Schlüssel umgehen alte Einträge, denen Bildfelder fehlen.

Die Bilddateien verwenden weiterhin die vorhandene begrenzte `ArtworkLoader`-Pipeline mit Disk-/Speichercache, Sichtbarkeitsprüfung und Wiederholungen bei temporären Netzwerkfehlern.

## Live-TV-Logo

Die ersten untersuchten Playlist-Logos verwenden `http://lo.tvigate.net/…png`. Der credentialfreie öffentliche Testfall `http://lo.tvigate.net/igatetv.png` enthält weder Kontoangaben noch Queryparameter. Sowohl HTTP als auch HTTPS liefen auf dem Entwicklungsrechner nach zwölf Sekunden in einen Verbindungs-Timeout. Eine HTTPS-Umschreibung behebt diesen Befund nicht. Die App erlaubt HTTP bereits.

Der lokale UI-Test importiert eine echte `tvg-logo`-Adresse, lädt die PNG-Datei über HTTP und prüft einen angezeigten `BitmapDrawable` im Live-TV-Eintrag. Damit wird die vollständige lokale Logo-Pipeline geprüft. Der zusätzliche Emulator-Probelauf meldet bei beiden öffentlichen Protokollvarianten `SocketTimeoutException`; Nachweis: `artifacts/public-logo-probe.json`. Ein nicht erreichbarer Bildhost kann vom Player nicht repariert werden; beim Anbieter muss eine erreichbare Logo-Adresse vorliegen.

## Tests

```powershell
Set-ExecutionPolicy -Scope Process Bypass
./scripts/test-movie-metadata.ps1
```

Die ergänzten Unit-Tests prüfen öffentliche HTML-Auswertung, Ausschluss der Crew, sichere Bildpfade, strukturierte Anbieterfotos, tokenfreien Abruf ohne Authorization-Header, Cache, falsche Filmzuordnung und HTTP 403. Die UI-Tests prüfen Live-Logo und Cast-Fotos als tatsächlich dekodierte Bitmaps, mehrspaltige Besetzung, Favoritenzustand/-farbe/-größe, Play-Symbol, D-Pad-Weg, 100/200 % Schrift sowie 320 dp mit RTL und langen deutschen Texten. Quellen und Bibliothek werden nach Tests wiederhergestellt.

Optionaler Internet-Smoke-Test, separat von deterministischen CI-Tests:

```powershell
. ./scripts/env.ps1
adb -s emulator-5554 shell am instrument -w -r -e publicMetadataSmoke true -e class de.iptv.tv.PublicPortraitSmokeTest de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 pull /sdcard/Download/iptv-metadata/public-cast-no-token.png artifacts/movie-metadata-screenshots/
adb -s emulator-5554 pull /sdcard/Download/iptv-metadata/public-logo-probe.json artifacts/
```

Dieser Test prüft echte öffentliche Arrival-Credits und ausdrücklich die Anzeige des Fotos von **Amy Adams** ohne Token. Zusätzlich protokolliert er die Erreichbarkeit des geprüften öffentlichen Logos aus dem Emulator. Bei CI-Läufen ohne das Argument wird er übersprungen.

## Skill-Prüfbericht

### Umfang und Abdeckung

Geprüft: Filmdetails von Öffnen über Favorit, Besetzung und Abspielen bis Zurück; vorhandene Lade-/Fehler-/fehlende-Daten-Verzweigungen im Quelltext und HTTP-/Metadatentests. Stack: native Views, `TvUi`, RecyclerView, Android-sp/dp, deutsche und englische Ressourcen. Gelesene Projektkonventionen: `docs/UI_REDESIGN.md`, `docs/UI_NAVIGATION_052.md`, `docs/UI_IMPLEMENTIERUNG_031.md` sowie `docs/MOVIE_LAYOUT.md`. Gemeinsame Farb-/Fokusbausteine wurden beibehalten; eine neue semantische Farbe `TvUi.favoriteActive` ergänzt die gewünschte gelbe Auswahl.

| Fachgebiet | Geprüfte Belege | Ergebnis |
| --- | --- | --- |
| Accessibility | `MovieDetailsView.kt`, `DetailActionIcon.kt`, native Rollen/Namen/Auswahl, D-Pad, vollständige Aktionsbreite bei 320 dp/200 % | Behobener Befund unten; TalkBack-Hörtest nicht verifiziert |
| Layout | Filmkopf und Cast-Grid, normale/große Schrift, 320-dp-RTL-Dialog; instrumentierte Zentrierung und Spaltenbegrenzung | Behobene Befunde unten |
| Writing | Deutsche/englische `metadata.xml`, bestehende Favoriten-/Play-Begriffe, Fehler mit Wiederholungsaktion | Irreführender Token-Hinweis behoben |
| Typography | Native sp-Skalierung, vollständige Titel/Namen/Rollen, längere Texte auf etwa 70 Zeichen begrenzt, skalierender Zeilenabstand | Besetzungsnamen/Rollen mit Faktor 1,4; kein neuer offener Befund |
| Colors | Tatsächlich verwendete opake Vorder-/Hintergrundwerte und Formwechsel des Sterns | Kontrastprüfung bestanden, siehe unten |
| UI polish | Native Buttonflächen, projektübliche sofortige Fokusdarstellung, skalierbare Canvas-Symbole; keine neue Bewegung | Kein neuer offener Befund |

### Behobene Befunde

| Schwere | Fachgebiet | Stelle | Vorher | Nachher | Warum |
| --- | --- | --- | --- | --- | --- |
| HIGH, behoben | Writing | `app/src/main/res/values/metadata.xml:31`, englisches Gegenstück | Personenfotos wurden als tokenpflichtig beschrieben | Öffentliche/Anbieter-Fotos werden tokenfrei aufgelöst; API-Angaben bleiben optional | Die bisherige Meldung erklärte eine unnötige Zugangsvoraussetzung |
| MEDIUM, behoben | Layout | `app/src/main/java/de/iptv/tv/MovieDetailsView.kt:71` | Vollbreite sekundäre Aktion und separates Vollbreite-Play | Kompakte Aktionsgruppe mit Play-Symbol | Eine primäre Aktion, klare Gruppierung |
| MEDIUM, behoben | Layout | `app/src/main/java/de/iptv/tv/MovieDetailsView.kt:47` | Lange vertikale Besetzungsliste | Raster mit anpassbarer Spaltenzahl und vollständigen Namen/Rollen | Gemeinsame Kanten und lesbare Gruppen bei Größenänderung |
| MEDIUM, behoben | Accessibility | `app/src/main/java/de/iptv/tv/MovieDetailsView.kt:96` | Lange Textaktion, Zustand weniger unmittelbar sichtbar | Nativer Button mit gefülltem/ungefülltem Stern, Aktionsname und ausgewähltem Zustand | WCAG 1.4.1 und 4.1.2; Zustand sichtbar und programmatisch verfügbar |

### Verifikation

- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`: **84 Unit-Tests**, keine Fehler; davon 31 Metadatentests. Lint: **0 Fehler, 71 bestehende Warnungen**. `artifacts/details-layout-build.log` und XML-Ergebnisse in `app/build/test-results/testDebugUnitTest/`.
- `scripts/test-movie-metadata.ps1 -SkipBuild`: **8 Regressionstests bestanden**. `artifacts/movie-metadata-final-device.log`.
- Abschließender Lauf von `MovieMetadataUiTest` und opt-in `PublicPortraitSmokeTest`: **3 Tests bestanden**, einschließlich tatsächlicher Amy-Adams-Bitmap ohne Token. `artifacts/details-layout-focused-device.log`.
- Zusätzlich verschärfte Textplatzierungsprüfung: Jede tatsächlich gerenderte Zeile unter dem Cast-Foto bleibt bei 100/200 % am Mittelpunkt ihrer eigenen Spalte ausgerichtet. Fokussierter Test bestanden: `artifacts/cast-text-check-device.log`.
- Visuell geprüft: `normal-details.png`, `normal-cast.png`, `large-details.png`, `large-cast.png`, `narrow-rtl-large.png` und `public-cast-no-token.png` in `artifacts/movie-metadata-screenshots/`. Das echte Besetzungsfoto ist im letzten Screenshot sichtbar.
- Öffentliche Seite zur untersuchten Anbieter-ID: HTTP 200, fünf exakt übereinstimmende Namen aus dem Anbieterbefund, Personenbilder vorhanden. Keine Anbieter-Anmeldedaten wurden an TMDB übergeben.
- Gemessene WCAG-Kontrastwerte: Weiß auf Buttonfläche **14,62:1**, Weiß auf Fokusfläche **11,44:1**, Sekundärtext auf Seitenhintergrund **6,91:1**, gelber Stern auf Button-/Fokusfläche **10,36:1 / 8,11:1**, Fokusumrandung gegen Fokusfläche **7,06:1**. Besteht die jeweils einschlägigen 4,5:1-Text- und 3:1-Grafik-Grenzen.
- **Nicht verifiziert:** echter TalkBack-Hörtest, physisches TV-Gerät, weitere Plattformen, ganze übrige App und visuelle Darstellung jedes extern verursachten Fehlerzustands. Die Prüfung ist keine vollständige WCAG-Zertifizierung.

Die Logo-Host-Erreichbarkeit bleibt als externer Befund offen; die lokale Logo-Pipeline ist geprüft.

Quellen: [öffentliche TMDB-Bildadressen](https://developer.themoviedb.org/docs/image-basics), [öffentliche Arrival-Besetzungsseite](https://www.themoviedb.org/movie/329865-arrival/cast), [jsoup](https://jsoup.org/download).

**Approve** — für die oben abgegrenzte und geprüfte native Detailansicht; die genannten nicht verifizierten Prüfungen sind ausgenommen.

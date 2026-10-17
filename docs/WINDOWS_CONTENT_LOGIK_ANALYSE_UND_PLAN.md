# Windows-Inhaltslogik nach Android TV: Analyse und Umsetzungsplan

Stand: 17. Oktober 2026. Status: **Windows-Portierung und ergänzte Detailnavigation zur Umsetzung freigegeben. Zuletzt gesehen, Favoriten und Fortsetzen sind als nächster Ausbau geplant.** Die Abschnitte 1–6 dokumentieren die ursprüngliche Analyse vor dem Go; Abschnitt 7 ergänzt die neuen Anforderungen. Der tatsächlich geprüfte Implementierungsstand steht in `CONTENT_LIBRARY_040.md`.

## 1. Geprüfter Stand

Windows: `C:\Users\ninos\Documents\ChatGPT\IPTV Player`, sauberer Git-Arbeitsstand bei `17dd21f41fd8a802df236ef1ce9d2c1087fb034e`.

Relevante Commits:

| Commit | Datum | Bedeutung für die Übertragung |
| --- | --- | --- |
| `17dd21f` | 17.10.2026, 01:18 MESZ | Neue Klassifikation, Metadatenerhalt, Filmraster, Serien-/Staffelindex, Cover, arabische Darstellung und gemeinsame Playerbedienung |
| `1c7fb8e` | 15.10.2026, 22:56 MESZ | Ursprüngliche Aufteilung Live / Movies / Series, bereichsbezogene Kategorien und Suche |
| `d05855e` | 15.10.2026, 18:49 MESZ | Mehrere Quellen und unabhängiger Browserzustand; Hintergrund für die bestehende Architektur |
| `7fcb083` | 16.10.2026, 17:56 MESZ | Transport-/Pufferänderungen; bereits separat nach Android übernommen, kein neuer Schwerpunkt dieses Plans |

Geprüft wurden die tatsächlichen Parser-, Bibliotheks-, UI- und Teständerungen sowie `docs/CONTENT_LIBRARY.md`. Die Commitnachrichten allein waren nicht die Grundlage.

Android TV: lokaler Quellstand `0.3.1` / versionCode `7`. Dieser Ordner enthält aktuell kein Git-Repository; ein Android-Commitvergleich ist deshalb nicht möglich. Der Abgleich erfolgte direkt gegen den vorhandenen Code. Android nutzt inzwischen einen Streaming-Import nach SQLite und ist keine unveränderte Kopie des alten Windows-Parsers mehr.

## 2. Konkrete Unterschiede

| Thema | Windows aktuell | Android TV aktuell | Erforderliche Änderung |
| --- | --- | --- | --- |
| Typpriorität | URL-Routen vor expliziten Typattributen | Typattribute vor URL-Routen | Windows-Reihenfolge übernehmen |
| Routenprüfung | Erkannte Pfadsegmente; Konflikte Series → Movie → Live; Live-Kurzroute `/user/password/numeric-id` | Ältere positionsabhängige Routenerkennung | Neue Regeln im Streaming-Parser |
| Unklare Einträge | `Unclassified` / „Unzugeordnet“ | Dateiendungen wie MP4/MKV ergeben Movie, sonst Live | Vierten Typ durch Core, Datenbank, API und UI führen |
| Kategorien | Originalinhalt, arabische Film-/Serienwörter berücksichtigt | Nichtleere Kategorien werden getrimmt; arabische Erkennungswörter fehlen | Originalwerte bewahren, Erkennung angleichen |
| Metadaten | Anzeigename und TVG-Name getrennt, alle Attribute, Pfad, Endung, Jahr, Qualität, Episode | Reduziertes Channel-Modell und acht bekannte Attribute | Modell und persistente Records erweitern |
| M3U-Serien | Ein Serienobjekt pro normalisiertem Titel, darunter Staffeln/Episoden | Jede Episode ist ein einzelner Eintrag in der Hauptliste | Persistenten Serienindex und eigene Serienabfragen ergänzen |
| Episodenmuster | `S01 E05`, `S01E05`, `S1 E5`, `Season 1 Episode 5`, `1x05` | Nur ältere kompakte Muster, keine Staffelmetadaten für M3U | `parse_episode` samt Grenzfällen übernehmen |
| Xtream-Serien | Der neue Bibliotheksaufbau ist vor allem auf M3U ausgerichtet; Windows-Xtream lädt hier Live und Movies | Serienobjekte und verzögertes Laden der Episoden bereits vorhanden | Bestehende Funktion erhalten und in die neue Detailansicht integrieren |
| Filme | Virtualisiertes Poster-Raster mit Jahr/Qualität und separaten Versionen | Textliste | TV-Poster-Raster mit unveränderten Originaleinträgen |
| Seriennavigation | Serienraster → Staffel → kompakte Episodenliste neben großem Player | M3U direkt abspielbar; Xtream über Staffel-/Episodendialoge | Gemeinsame dauerhafte Detailansicht |
| Bilder | Begrenzter asynchroner Download und Speicher-Cache | Kein Bildadapter; Suchantwort ohne Logo; produktiver Xtream-Katalog setzt `logo: None` | Covermapping, begrenzte Bildabfrage und Android-Bildlader |
| Wiedergabe | Ein gemeinsamer Player, Bibliothekswechsel und Vollbild ohne neuen Load | Gemeinsamer Media3-Player und Vollbild durch Umhängen der PlayerPane bereits vorhanden | In neue Ansichten einbinden; Wiedergabekontext von Browserkontext trennen |
| Arabische Texte | Windows-GDI als Lösung für egui | Native TextViews/EditTexts, RTL im Manifest aktiviert | Native Textdarstellung gezielt konfigurieren und visuell prüfen |

Wichtige Fundstellen in Android:

- `crates/iptv-core/src/playlist.rs`: `ContentKind`, `Channel`, `parse_stream`, `classify`, `attributes`.
- `crates/iptv-core/src/catalog.rs`: Schema 1, `Writer`, `search`, `episode_page`, `resolve_series`.
- `crates/iptv-core/src/service.rs`: Typmapping, JSON-Verträge und Auswahl von abspielbaren Einträgen.
- `app/src/main/java/de/iptv/tv/MainActivity.kt`: `buildUi`, `selectChannel`, `showEpisodes`, Suche/Paging, Vollbild und Fernbedienung.
- `app/src/main/java/de/iptv/tv/CatalogStore.kt` und `SourceLoader.kt`: Schema im Manifest, Offline-Restore, HTTP-304 und Kataloggenerationen.
- `crates/iptv-core/src/xtream.rs`: produktives Streaming-Mapping von Filmen, Serien und Bildern.

Die historischen Aussagen in `docs/CONTENT_SECTIONS.md` und Teilen von `docs/UPSTREAM.md` beschreiben ältere Zustände. Maßgeblich ist der aktuelle Quellcode.

## 3. Zielverhalten

### Klassifikation und Originaldaten

Die Reihenfolge wird wie im geprüften Windows-Stand festgelegt:

1. URL-Pfadsegmente `series`, `movie`/`movies`, `live`; bei mehreren Treffern in dieser Priorität. Die bestätigte Live-Kurzroute gewinnt ebenfalls vor Metadaten.
2. Explizite Typattribute.
3. Typangaben in URL-Queryparametern.
4. Kategorienwörter einschließlich arabischer Bezeichnungen, mit Series → Movie → Live bei Konflikten.
5. Episodenmarker aus Anzeigename oder TVG-Name; danach positive EXTINF-Dauer als Movie.
6. Broadcast-Protokolle und TS-/HLS-Endungen als Live-Fallback.
7. Sonst „Unzugeordnet“. MP4/MKV allein sind kein ausreichender Filmnachweis.

„Unzugeordnet“ erscheint nur bei vorhandenen Einträgen und bleibt abspielbar. Wie unter Windows startet ein solcher Eintrag zunächst mit `is_live = false`; das ist keine Behauptung über seinen tatsächlichen Inhalt. Die bereits vorhandene Erkennung eines direkt importierten endlichen HLS-Manifests als Movie bleibt erhalten.

Absolute Streamadressen bleiben einschließlich Groß-/Kleinschreibung und Query-Escapes unverändert. Nur relative Adressen werden gegen die Importbasis aufgelöst. Fehlende Stream-URLs werden gezählt und übersprungen. HD/4K-Versionen werden weder zusammengelegt noch durch erfundene Adressen ersetzt.

### Serien, Kategorien und Suche

- M3U-Gruppierung entspricht Windows: Titel vor dem Episodenmarker, normalisierte Leerzeichen und Kleinschreibung als Schlüssel; Unicode und bedeutungsvolle SUB-/DUB-Labels bleiben erhalten. Titelgleiche Serien können mehreren Kategorien angehören. Kategorien erzeugen keine Serien- oder Staffelnamen.
- Staffeln und Episoden werden numerisch sortiert; gleiche Episodennummern mit mehreren Versionen bleiben separate Zeilen in stabiler Importreihenfolge.
- Nicht zuverlässig erkannte Folgen bleiben unter ihrem Originaltitel und „Ohne Staffelnummer“ erreichbar. Keine erfundenen Nummern.
- Serienkarten werden erst nach Bereichs-, Kategorie- und Episodensuche zusammengefasst. Alle Suchbegriffe müssen wie bisher denselben passenden Eintrag treffen. Pro Serie erscheint höchstens eine Karte, auch über Seitengrenzen hinweg.
- Nach dem Öffnen zeigt die Serie wie unter Windows ihre vollständigen Staffeln/Episoden. Die Bibliothekssuche bestimmt die gefundenen Karten, nicht eine versteckte Einschränkung der Detailansicht.
- Xtream-Serien behalten ihre Anbieter-ID als Identität; zwei verschiedene Anbieter-IDs werden nicht allein wegen identischer Titel verschmolzen. M3U-Titelgruppierung und Xtream-Identität bleiben unterscheidbar.
- Die Bereichszahl bei Series zählt Serienobjekte. Episodenzahlen und rohe Importzahlen werden separat geführt. Kategorienzahlen im Serienbrowser zählen ebenfalls eindeutige Serien innerhalb der Kategorie; das ist eine bewusst festgelegte TV-Anzeige.

### TV-Ansichten

- Live: Kategorien → schmale Senderliste mit Logos → großer Player, in der vorhandenen TV-Struktur.
- Movies: Kategorien und Suche neben einem virtualisierten Poster-Raster; Titel, Jahr und Qualität sichtbar. Auswahl startet die genaue Version im gemeinsamen Player rechts. Ohne aktive Wiedergabe steht mehr Breite für das Raster zur Verfügung.
- Series: Kategorien und Serienraster; Auswahl öffnet eine Detailansicht mit Titel, Cover, Staffelauswahl und schmaler Episodenliste links vom großen Player. Das Öffnen der Serie selbst startet keinen Stream.
- „Zur Serienbibliothek“ und Kategorieauswahl führen ins Raster zurück. Die erneute Auswahl auch derselben Kategorie schließt die Details und setzt Serie/Staffel zurück, entsprechend Windows.
- Zurück führt aus Vollbild zunächst zur bisherigen Ansicht, aus Seriendetails zur Serienbibliothek und erst auf der obersten Ebene aus der App. Sichtbare Playercontrols behalten ihre bestehende vorgelagerte Zurück-Behandlung.
- Fokus, Scrollposition, Suchfilter und gewählte Staffel bleiben beim Vollbildwechsel erhalten. D-Pad-Rechts navigiert innerhalb des Rasters zur nächsten Karte und erst am Rand zum Player.
- Beim Browsen läuft die bisherige Wiedergabe weiter. Erst eine explizite Film-/Episodenauswahl ersetzt sie. Die vorhandene Android-Regel beim Quellenwechsel bleibt bestehen.
- Vorheriger/nächster Titel und Channel-Tasten verwenden den tatsächlichen Wiedergabekontext: Live-Liste, Filmergebnisse oder Episoden der gewählten Staffel. Eine Serienkarte darf niemals direkt an `playback.select` gelangen. Kein automatischer Folgenstart ohne gesonderten Auftrag.

## 4. Technischer Umsetzungsplan

### Schritt 1: Parser und fachliches Modell

Betroffen: `playlist.rs`, `xtream.rs`, Core-Tests und notwendige Channel-Konstruktoren.

- Windows-Helfer für Klassifikation, Episoden, Jahr, Qualität und korrekt maskierte Attributwerte in den Android-Parser übertragen.
- Getrennte Originalnamen, Originalkategorie und Attribute erhalten; Suche um TVG-Namen ergänzen.
- Androids `parse_stream` mit Sink, Formatprüfung, Zeilen-/Eintragsgrenzen und Abbruch erhalten. Kein vollständiger Windows-`Playlist::build_series`-RAM-Index im produktiven Importpfad.
- Live-/Movie-/Series-Mapping aus Xtream explizit halten, bekannte Coverfelder auswerten und dieselben abgeleiteten Metadaten ergänzen.
- Den bestehenden In-Memory-Kompatibilitätspfad fachlich konsistent halten und gemeinsam testen.

Abnahme: Windows-Parsertestfälle ergeben dieselben Typen und Metadaten, ohne Androids Importgrenzen oder Formatprüfung zu verlieren.

### Schritt 2: SQLite-Schema und Serienindex

Betroffen: `catalog.rs`, `service.rs`, Katalogtests.

- Schema 2 mit persistierten Metadaten und eigenständiger Serien-/Mitgliedschaftsstruktur einführen. Abspielbare Originaleinträge bleiben getrennt von Bibliotheksobjekten; Serien erhalten keine fiktive abspielbare URL.
- M3U-Serien während des Imports transaktional über einen normalisierten Schlüssel aufbauen. Kategorienmitgliedschaften, optionale Staffel-/Folgenummern und stabile Sortierung speichern und indexieren.
- Xtream-Objekte und bereits vorhandenes Lazy-Loading in dieselbe fachliche Detail-API überführen, mit unterscheidbarer Herkunft. API-Aufrufe nur beim erforderlichen Auflösen einer Xtream-Serie; M3U benötigt dafür keinen Anbieterabruf.
- Serienkarten bereits in SQL vor `LIMIT/OFFSET` deduplizieren. Zusammenfassen erst in Kotlin würde doppelte Karten, falsche Trefferzahlen und fehlende Serien auf Folgeseiten verursachen.
- Keine vollständigen Episode- oder Trefferlisten für das UI aufbauen. Bestehende Grenzen von höchstens 128 DTOs pro Antwort und 512 UI-Einträgen bewahren; Staffeln bei großen Mengen ebenfalls begrenzt laden.
- „Alle Staffeln“ und „Ohne Staffelnummer“ müssen im API-Vertrag verschiedene Filter sein. Ein einzelnes `season: null` darf nicht beide Bedeutungen haben.
- JSON-DTOs erhalten einen expliziten Objekttyp und getrennte Serien-/Eintragsreferenzen sowie Jahr, Qualität und benötigte Anzeigemetadaten. Alle Antworten bleiben revisionsgebunden.
- URLs, Requestheader und sensible Originalattribute/Pfade bleiben im verschlüsselten Record. Listen-DTOs geben keine Streamadressen aus. Coveradressen werden nur gezielt und begrenzt für sichtbare Elemente bereitgestellt.

Abnahme: Eine Serie mit mehr als einer Ergebnisseite erscheint genau einmal im Raster, sämtliche Episoden bleiben über die Detailseiten erreichbar und mit ihrer exakten URL abspielbar.

### Schritt 3: Upgrade und Offline-Kataloge

Betroffen: `CatalogStore.kt`, `SourceLoader.kt`, `catalog.rs`, `service.rs` und Importfehlermapping.

Schema 1 enthält weder alle Originalattribute noch EXTINF-Dauer und unabhängigen TVG-Namen. Eine nachträgliche Umklassifikation allein aus diesem Katalog wäre deshalb nicht zuverlässig Windows-identisch.

- Vorhandenen Schema-1-Katalog als lesbaren Übergangsstand weiter unterstützen. Offline bleibt die bisherige Bibliothek erreichbar; neue Gruppierung wird für diesen Übergangsstand nicht als vollständig ausgegeben.
- Beim ersten erfolgreichen Zugriff die Quelle vollständig neu importieren und Schema 2 in einer neuen Generation aufbauen. Quellenmanifest und Keystore-Schlüssel bleiben erhalten.
- Für einen erforderlichen Neuaufbau keine alten HTTP-Validatoren senden: Ein `304 Not Modified` liefert die fehlenden Quelldaten nicht. Auch der Dateipfad muss trotz unveränderter Dateimetadaten neu gelesen werden.
- Schema-/Parserstand zentral zwischen Core und Kotlin abstimmen; die bisher mehrfach fest kodierte `1` in `info`, Manifestprüfung und `commit` ablösen.
- Erst nach erfolgreichem Aufbau, Validierung und sicherer Manifestablage die neue Generation zum dauerhaften Standard machen. Fehler/Abbruch lassen alten Katalog und bisherige Quelle verfügbar.
- Für nicht mehr zugängliche Dateien eine verständliche erneute Dateiauswahl anbieten. Keine automatische Löschung der noch nutzbaren alten Generation.

Abnahme: Upgrade online, offline, bei HTTP-304, Abbruch, ungültiger Quelle und Speicherfehler getestet; kein erzwungener Verlust vorhandener Quellen.

### Schritt 4: TV-Browser und Serienansicht

Betroffen: `MainActivity.kt`; neue kleine Komponenten für Bibliothekszustand, Poster-Adapter und Seriendetails.

- Browserzustand von Wiedergabezustand lösen: Bereich, Kategorie, Suchtext, Rasterseite, fokussierte Karte, geöffnete Serie, Staffel und Episodenposition.
- Movies/Series mit `RecyclerView`-Raster und eigenen Karten darstellen. Vorhandene Paging-/Generationsprüfungen auch für Raster und Details anwenden.
- Xtream-Dialoge durch dieselbe Serienansicht ersetzen, die M3U-Serien verwendet; Lade-, Leer- und Fehlerzustände sowie erneutes Laden vorsehen.
- D-Pad, lange Titel, große Schrift und Rückkehr zu bereits aus dem 512-Einträge-Fenster entfernten Karten berücksichtigen.
- „Unzugeordnet“ nur bei Bedarf zeigen; Toolbar muss mit vier Bereichen und großen Zählerwerten funktionieren. Nicht lediglich einen vierten festen Button in die bisherige Breite einfügen.

Abnahme: Alle Ansichten sind ohne Touch bedienbar; keine Fokusfallen beim Rasterpaging, Staffelwechsel oder Rücksprung.

### Schritt 5: Bilder, Text und gemeinsamer Player

Betroffen: neuer begrenzter Android-Bildlader, Karten-/Logo-Views, `TvUi.kt`, `PlayerPane.kt` und bei Bedarf `PlaybackController.kt`.

- Vorhandenes OkHttp verwenden; Downloads/Decode außerhalb des Main-Threads. Maximal zwei parallele Bildabrufe, begrenzte Warteschlange und bytebegrenzter LRU-Speicher; nur benötigte Bildgrößen decodieren.
- Startwerte: maximal 8 MiB pro Antwort, 20 Sekunden Gesamtzeit, 48 wartende Aufträge, 60 Sekunden Pause nach fehlgeschlagenem Abruf. Bitmap-Cache zunächst höchstens 16 MiB; auf dem TV anhand der Messungen prüfen.
- PNG/JPEG/WebP/BMP anhand des Inhalts prüfen, übergroße/ungültige Bilder mit Platzhalter behandeln. Keine Disk-Persistenz. Recycelte Views dürfen keine veralteten Cover erhalten; Decoder- und Cachebudgets müssen getrennt begrenzt werden.
- Bilder nur bei Anzeige abrufen; Katalogimport und Parservergleich fordern keine Providerstreams oder Poster an. Provideradressen und Zugangsdaten nicht in Diagnoseausgaben übernehmen.
- Arabische/gemischte Texte in nativen TextViews/EditTexts mit passender Textrichtung und unverändertem logischen Unicode darstellen. Den Windows-GDI-Renderer und dessen Texturcache nicht portieren.
- Bestehende Media3-/PlayerPane-Instanz in alle Ansichten einbinden. Pause/Seek, Verbindungsdetails, Fehler, Retry und Vollbild bleiben gemeinsam verfügbar; Ansicht/Vollbild löst keinen neuen Load aus.
- Androids vorhandene Seek-Schritte und Live-Pufferprofile beibehalten. Windows-RTX, mpv und Maus-/F11-Bedienung gehören nicht zur TV-Portierung.

Abnahme: Serien-/Filmauswahl nutzt dieselbe Playerinstanz; Vollbild erhält Load-Versuch, Position, Staffel und Fokus. Coverfehler beeinträchtigen weder Navigation noch Wiedergabe.

### Schritt 6: Regression, Performance und Dokumentation

- Herkunft auf Windows-Commit `17dd21f` aktualisieren; den Android-Parser weiter ausdrücklich als Ableitung dokumentieren. `upstream-sha256.json` enthält geprüften Android-Hash und neuen Windows-Referenzhash.
- `CONTENT_SECTIONS.md`, `ARCHITECTURE.md`, `UPSTREAM.md` und Testbericht auf den tatsächlich implementierten Stand bringen.
- Abschließend alle erforderlichen Rust-/Android-Checks und Paketprüfungen ausführen, danach Debug-APK und R8-Release-APK/AAB prüfen. Vorhandene Skripte verwenden.
- Kein Windows-Umbau und keine gemeinsame Crate-Auslagerung als Voraussetzung dieses Vorhabens.

## 5. Test- und Abnahmematrix

| Ebene | Zu prüfende Fälle |
| --- | --- |
| Parserparität | Routen-/Metadatenkonflikte, mehrere Routen, Live-Kurzroute, arabische Kategorien, alle Episodenmuster, maskierte Anführungszeichen/Kommas, absolute/relative URLs, unbekannte MP4/MKV, fehlende URLs, SUB/DUB, Jahr/Qualität |
| Katalog/API | Metadaten nach erneutem Öffnen, vier Typen, Serien über Kategoriegrenzen, getrennte Film-/Folgenversionen, numerische Sortierung, unbekannte Staffel, sichere DTOs, ungültige/veraltete IDs und Revisionen |
| Suche/Paging | Episodensuche ergibt eine Serienkarte; alle Begriffe im selben Eintrag; Deduplizierung vor Paging; Vorwärts-/Rückwärtsseiten; höchstens 128 DTOs, UI-Fenster 512 |
| Xtream | Coverimport, Lazy-Resolve, Offline-Episodencache, gleiche Titel mit verschiedenen IDs, fehlender optionaler Endpunkt, fehlerhafter Refresh erhält alte Episoden |
| Upgrade | Schema 1 online/offline, Neuimport trotz unveränderter Datei, keine 304-Wiederverwendung beim nötigen Neuaufbau, Abbruch/Speicherfehler, Manifest-Commitfehler |
| Bilder | Lokaler HTTP-Testserver mit echten Formaten, fehlende Endung, 404/Timeout, falsche Daten, große Pixelmaße, Abbruch, Cachebudget und View-Recycling |
| TV/UI | Raster und Kategorieauswahl per D-Pad, Detail-/Staffelwechsel, kein ungewollter Streamstart, Zurückhierarchie, Fokuswiederherstellung, große Schrift, lange arabisch-lateinische Titel |
| Wiedergabe | Live läuft beim Browsen weiter; Film/Folge ersetzt gezielt; Kontext für nächste/vorherige Auswahl; Vollbild ohne neuen Load; Pause/Seek/Retry/Fehler, Home/Rückkehr |
| Last | Reale lokale Referenzliste und synthetische große Liste: Importzeit, Suchlatenz, Karten-/Folgenpaging, Prozessspeicher und Bitmapbudget separat messen |

Für den lokalen vollständigen Referenzabgleich die bestehende Audit-/Benchmark-Infrastruktur um Serienmitgliedschaften und neue Metadaten erweitern. Jede gültige Originalepisode muss genau einer Seriengruppe angehören, alle Versionen müssen erhalten bleiben. Absolute Import-/Bibliothekszahlen werden mit der neuen Klassifikation neu ermittelt; alte Bereichszahlen dürfen nicht ungeprüft als Sollwert dienen. Keine Namen oder Zugangsdaten im Bericht veröffentlichen und keine privaten Playlisten in APKs aufnehmen.

Reale Wiedergabetests zunächst mit synthetischen lokalen Streams durchführen. Der optionale Millioneneinträge-Test ist ein gesonderter Lasttest; eine Hostmessung ersetzt keine TV-Gerätemessung.

## 6. Bereits durchgeführt und Umsetzungsschranke

- Windows-Commitverlauf und aktueller Arbeitsstand geprüft; keine uncommitteten Windows-Änderungen vorgefunden.
- Parser-, Speicher-, Xtream-, UI-, Playback- und Testpfade beider Projekte gelesen und abgeglichen.
- Bestehende Android-Hosttests ausgeführt: `cargo test --locked -p iptv-core --test playlist --test catalog`.
- Ergebnis: **15 Tests bestanden, 0 fehlgeschlagen; 1 optionaler Millioneneinträge-Test übersprungen.** Nur ein Hinweis auf Kopieren statt Hardlinks im inkrementellen Compiler-Cache, kein Testfehler.
- In dieser Analyse keine App-/Parserimplementierung geändert, keine APK installiert und keine Anbieterstreams oder Cover abgerufen. Neu erstellt wurde ausschließlich dieses Plandokument; der Testlauf aktualisierte lokale Build-Artefakte.

Empfohlene Umsetzung nach dem Go: Schritte 1–3 als fachliche Basis, anschließend 4–5 als TV-Oberfläche, danach die vollständige Abnahme aus Schritt 6. Kernumfang sind sämtliche hier beschriebenen Parser-, Film- und Serienfunktionen einschließlich sicherem Katalogübergang und TV-Navigation.

## 7. Ergänzung: Bibliotheksbreite, Filmdetails, Verlauf und Favoriten

### 7.1 Navigation — laufende Implementierung

- Film- und Serienbibliothek verwenden die gesamte verfügbare Breite neben den Kategorien. Player und Verbindungsdetails bleiben dort ausgeblendet, auch wenn bereits etwas läuft. Die bestehende Wiedergabe kann weiterlaufen.
- Kleinere Poster mit adaptiver Spaltenzahl: Zielbreite ungefähr 108 dp, bei großer Schrift 146 dp; Seitenverhältnis 2:3, lesbarer Titel und Jahr/Qualität. Keine feste Spaltenzahl unabhängig von Schriftgröße und Bildschirmbreite.
- Serienkarte → Serienansicht mit Cover, Titel, Staffelauswahl, Episodenliste und Player rechts. Öffnen und Staffelwechsel starten keine Wiedergabe; OK auf Episode startet diese.
- Filmkarte → Filmansicht mit Cover, vollständigem Titel, vorhandenen Metadaten, explizitem Play-Button und Player rechts. Erst Play startet den Film. Vollbild verwendet weiterhin dieselbe PlayerPane ohne neuen Load.
- Zurück aus Details stellt Seite und Kartenfokus wieder her. Kategorie-/Bereichswechsel schließt Details. Live behält die bestehende dreispaltige Ansicht. Tests prüfen den versteckten Player auch nach vorheriger Wiedergabe sowie Fokus und große Schrift.

### 7.2 Architekturbefund und kleinste passende Erweiterung

`MainActivity` besitzt Bereich, Kategorie, Such-/Pagingzustand und separate Wiedergabekontexte. `PlaybackController` besitzt Media3-Position, Dauer, Pause-/Fehler-/Endestatus und einen bestehenden Sekundenticker. `CatalogStore` verwaltet austauschbare, revisionsgebundene SQLite-Kataloggenerationen. Deren numerische Eintrags-/Serien-IDs ändern sich bei Neuimporten und eignen sich **nicht** als dauerhafte Benutzeridentitäten.

Für Quellen existiert bereits `SourceStore`: Keystore-verschlüsseltes JSON in `noBackupFilesDir` mit `AtomicFile`. Diese Speichertechnik wird für einen kleinen versionierten `UserLibraryStore` wiederverwendet; keine neue Datenbank, kein Server und keine Profile. Benutzerzustand liegt außerhalb austauschbarer Kataloggenerationen. Ein einzelner IO-Writer serialisiert Änderungen und schreibt atomare Snapshots; kein Keystore-/Dateizugriff im Media3-Callback. JSON-Strukturen werden typisiert und von Quellencredentials getrennt gehalten.

Der Core erhält indizierte dauerhafte Inhaltsschlüssel und begrenzte Auflösungs-/Batchabfragen. Dadurch können bis zu 15 Verlaufseinträge oder eine Seite Favoriten ohne vollständigen Katalogscan wieder auf aktuelle revisionsgebundene IDs abgebildet werden. Bestehende DTO-/UI-Grenzen bleiben bestehen. Das ist die einzige notwendige Katalogerweiterung; normale Such-/Serienabfragen und Wiedergabe bleiben erhalten.

### 7.3 Identitäten und Datenmodelle

Schlüssel enthalten einen Quellenbezug, Typ und stabile Inhaltsidentität. Xtream nutzt Anbieter-IDs einschließlich Objektart; Serie und Episode erhalten getrennte Schlüssel. M3U nutzt eine eindeutige TVG-ID, wenn innerhalb Quelle/Typ tatsächlich eindeutig, andernfalls den Hash der unveränderten Original-Streamadresse. Filmversionen und unterschiedliche Streams bleiben unterscheidbar. M3U-Serien verwenden die bereits vorhandene normalisierte Serienidentität innerhalb ihrer Quelle. Wiederholte identische Einträge werden dedupliziert, verschiedene gleichnamige Serienanbieter-IDs nicht.

Die Quellenidentität verwendet zunächst den vorhandenen Quellenfingerabdruck. Gleiche Quelle mit neuem Import oder anderer Sortierung erhält denselben Bezug. Geänderte Adresse/Zugangsdaten, rotierende Streamadressen oder umbenannte M3U-Serien können die Identität ändern: niemals allein wegen eines gleichen Titels automatisch fremde Inhalte zuordnen. Nicht auflösbare Einträge bleiben gespeichert und werden verständlich als derzeit nicht verfügbar angezeigt; erneute Verfügbarkeit stellt die Zuordnung wieder her. Eine automatische übergreifende Identitätsmigration wäre ein gesonderter Ausbau.

| Logisches Modell | Persistierte Informationen |
| --- | --- |
| Verlauf je Bereich | eindeutiger Inhaltsschlüssel, letzter tatsächlicher Wiedergabestart, Metadaten-/Bildreferenz als Fallback; höchstens 15 Einträge |
| Serienverlauf | genau ein Serienschlüssel, letzter Episodenschlüssel, optionale Staffel-/Episodennummer und Episodentitel; Fortschritt separat |
| Favoriten je Bereich | eindeutiger Film-/Serien-/Kanalschlüssel, Zeitpunkt des Markierens, Fallback-Metadaten; keine Episodenfavoriten |
| Fortschritt | Film- oder Episodenschlüssel, Position und bekannte Dauer in Millisekunden, aktualisiert am, Status teilweise/abgeschlossen |

Bildadressen mit möglichen Credentials bleiben wie andere sensible Daten verschlüsselt; keine Posterbytes in Benutzer-Snapshots. Verlauf, Favoriten und Fortschritt sind logisch unabhängig, auch wenn sie gemeinsam atomar gespeichert werden. Entfernen eines Favoriten oder Verlaufseintrags löscht weder die anderen Listen noch Fortschritt. Ein Auswurf aus den letzten 15 löscht keinen Fortschritt.

### 7.4 Ereignisse, Reihenfolge und Fortsetzen

1. Nur tatsächlicher Beginn der Wiedergabe (`isPlaying` und bestätigter Medienfortschritt/erstes Medienereignis) erzeugt einen Verlaufseintrag. Auswahl, Detailöffnung, Staffelwechsel und fehlgeschlagener Stream tun dies nicht. Kurze erfolgreiche Wiedergabe darf erscheinen; keine künstliche Mindestsehzeit. Ein Wiedergabeversuch wird einmal registriert, Retry oder Pause/Fortsetzen erzeugen keine Duplikate.
2. Atomar vorhandenen Schlüssel aktualisieren, nach vorne stellen und je Live/Movies/Series auf 15 eindeutige Einträge kürzen. Reihenfolge durch monotone Ereignisfolge zusätzlich zum Zeitstempel absichern. Fortschrittsspeicherung verändert diese Reihenfolge nicht jede Sekunde.
3. Den vorhandenen Ticker verwenden und geänderte Film-/Episodenposition etwa alle 10 Sekunden zusammenfassen. Zusätzlich Pause, Streamwechsel, Stop, Ende und `onStop` speichern. Keine parallele Zeitmessung; Position/Dauer stammen aus Media3. Bei Prozessabbruch bleiben normalerweise höchstens rund 10 Sekunden ungespeichert; ein hartes Ausschalten kann einen gerade laufenden Schreibvorgang verhindern.
4. Live speichert ausschließlich den Kanal und die letzte Nutzung, keine EPG-Sendung und keine Fortsetzungsposition. Nicht seekbare/unbekannt lange VOD-Inhalte werden ohne erfundene Prozentanzeige dargestellt.
5. Bei Film-/Episodenauswahl gespeicherte Position nach Timeline-/Seekbarkeitsprüfung einmal anwenden, nicht bei jedem READY-/Retry-Ereignis. Ungültige Position auf den verfügbaren Zeitraum begrenzen. In der Detailansicht „Fortsetzen bei 23:10“ und „Von Anfang an“ anbieten.
6. Bei `STATE_ENDED` abgeschlossen markieren. Als zusätzlicher Vorschlag für Inhalte ohne zuverlässiges Endereignis: mindestens 95 % und höchstens 120 Sekunden Rest, nur bei bekannter positiver Dauer. Abgeschlossene Inhalte starten erneut bei 0; die letzte Episode bleibt als zuletzt gesehen erkennbar. Keine automatische nächste Episode im Rahmen dieses Ausbaus.
7. Episodenstart aktualisiert den einen Serienverlaufseintrag und den Fortschritt dieser Episode. Bloßes Navigieren verändert weder letzte Episode noch Serienfavorit. Serienöffnung zeigt die gespeicherte Staffel/Episode und bietet Fortsetzen; eine bewusste andere Episodenauswahl aktualisiert den Verlauf erst bei erfolgreichem Start.

### 7.5 Kategorien und TV-Bedienung

In Live, Movies und Series erscheinen „Zuletzt gesehen“ und „Favoriten“ als eigene Kategorien vor den Providergruppen. Eine explizite Filterart (`all`, `provider`, `recent`, `favorites`) ersetzt die Annahme, dass jede Kategorie eine numerische Provider-ID ist. Bereichswechsel, Suche und Rücksprung behalten diesen Kontext; Channel +/− nutzt die beim Start gespeicherte Ergebnisreihenfolge.

Filmkarten und Serienkarten sowie Kanalzeilen erhalten einen direkt bedienbaren Stern mit zugänglichem Namen „… zu Favoriten hinzufügen/aus Favoriten entfernen“ und eindeutigem aktivem Zustand. Der Adapter darf dazu nicht mehr pauschal alle Kindfokusse blockieren: Karte öffnen und Stern bleiben getrennte D-Pad-Ziele ohne Doppelaktion. Detailansichten zeigen denselben Favoritenstatus, bei Serien stets den der ganzen Serie. Maus/Touch bedienen dieselben Aktionen. Nach Entfernen in der Favoritenliste Fokus auf den nächsten erreichbaren Eintrag oder die Leeransicht zurückführen.

Film-/Serienkarten zeigen kompakt Position, bei Serien zusätzlich letzte Staffel/Episode, optional einen Fortschrittsbalken. Vollständige Information bleibt in Details erreichbar. Live zeigt Logo und Kanalname ohne Fortschritt. Passende Leeransichten unterscheiden noch keinen Verlauf, keine Favoriten und derzeit nicht verfügbare Inhalte; keine leeren Playerflächen im Bibliotheksraster.

### 7.6 Umsetzung und verbindliche Abnahme des Folgeausbaus

Reihenfolge: stabile Katalogschlüssel und Auflösung → typisierter atomarer Store → Playerereignisse und Resume → Kategorien/Sterne/Detailanzeigen → vollständige Regression. Keine nur optischen Favoriten oder flüchtigen Verlaufslisten ausliefern.

| Prüfung | Erwartung |
| --- | --- |
| 16 Inhalte pro Bereich, danach Wiederholung | jeweils genau 15, ältester verdrängt, erneut gestarteter Inhalt genau einmal zuerst; deterministische Ordnung bei gleicher Uhrzeit |
| Mehrere Folgen einer Serie | ein Serienobjekt; letzte Staffel/Folge aktualisiert, jeder Episodenfortschritt separat |
| Restart/Prozessneustart | alle drei Verläufe, Favoriten und Film-/Episodenposition wiederhergestellt; echte Datei neu öffnen, kein Test nur mit derselben Storeinstanz |
| Neustart nach Pause/Stop und periodischem Save | Position/Dauer korrekt; kein irrtümliches Speichern einer alten Playerinstanz unter neuem Inhalt |
| Live/Fehler/Detailöffnung | nur erfolgreich gespielter Kanal erscheint; keine Sendung/Liveposition, kein Verlauf durch Details oder Streamfehler |
| Fortsetzen/Abschluss | Seek einmal nach gültiger Timeline; unbekannte Dauer/kein Seek robust; beendet startet bei 0, „Von Anfang an“ funktioniert |
| Favoriten an/aus | persistiert in allen drei Bereichen; Serie ausschließlich als Show, niemals Folgen; Änderungen sofort sichtbar |
| Unabhängigkeit | Favorit entfernen erhält Fortschritt/Verlauf; Verlauf entfernen oder verdrängen erhält Favorit/Fortschritt |
| Neuimport/Quellenwechsel | Sortierung und neue Revision erhalten Zuordnung; identische Titel verschiedener Quellen und Filmvarianten bleiben getrennt; fehlender Inhalt löscht keine Benutzerdaten |
| Schreibfehler/Abbruch | vorheriger atomarer Snapshot lesbar, keine Teilzustände; Fehler sichtbar, Wiedergabe und vorhandene Daten weiter nutzbar |
| TV/UI | D-Pad, Sterne ohne versehentlichen Start, Rücksprungfokus, leere Listen, lange arabische Titel, doppelte Schriftgröße, Screenshots aller Spezialkategorien |
| Abschluss | Rust-/JVM-/Emulatortests, Lint, Debug- und R8-Release-Build/APK/AAB sowie Paket-/Datenschutzprüfung wiederholen |

Diese Erweiterung ist geplant; sie wird im aktuellen 0.4.0-Prüfbericht ausdrücklich nicht als bereits implementiert ausgegeben.

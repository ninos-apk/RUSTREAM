# Playlist-Import: Messung, Ursachen und Umsetzungsplan

**Umsetzung:** Die anschließende Version 0.3.0 implementiert Diagnose, Formatprüfung, Xtream-Serien, Katalogcache und den speicherschonenden Import. Aktueller Stand, Abwägungen und Abnahmen stehen im [Umsetzungsbericht](PLAYLIST_IMPLEMENTIERUNG_030.md). Die folgenden Zahlen beschreiben den ursprünglichen Analysezeitpunkt.

Stand: 16. Oktober 2026. Untersucht wurden der aktuelle TV-Quellstand 0.2.1 (VersionCode 5), die Windows-Version und `../IPTV Player/tests/fixtures/BigListWithRealVideoStream.m3u`. Der angegebene Dateiname ohne Endung wurde zur vorhandenen `.m3u` aufgelöst. Änderungen dieser Untersuchung betreffen Messwerkzeuge und Dokumentation; die vorgeschlagenen Produktkorrekturen sind noch nicht umgesetzt.

## Ergebnis

Der aktuelle **M3U-Parser erkennt die Serien dieser Datei auf Windows und Android**. Die beiden Parserdateien sind byteidentisch (SHA-256 `04BEFC78204FDC47361F28795D08C40E19D02CFF1CA2463100169FD41C565219`). Auch im Android-Emulator erscheinen alle drei Inhaltsbereiche mit den erwarteten Trefferzahlen. Ein leerer Serienbereich mit dieser Datei lässt sich mit dem aktuellen Build nicht reproduzieren. Ein anderer installierter APK-/JNI-Stand, ein anderer Importweg oder eine abweichende Anbieterantwort bleiben mögliche Ursachen auf dem tatsächlichen Fernseher.

**Xtream hat dagegen eine bestätigte Funktionslücke:** Der TV-Import lädt nur Live und Filme. Serienkategorien, Serienkatalog und Episodenauflösung fehlen vollständig. Auch der aktuelle Windows-Xtream-Loader lädt nur Live und Filme; die korrekt erkannte Windows-M3U belegt deshalb keine Xtream-Parität.

Zwei weitere Fehler sind bestätigt: HTTP-Fehler werden in der Oberfläche pauschal zusammengefasst, und eine HTML-Anmeldeseite mit HTTP 200 wird beim URL-Import als scheinbar gültige Playlist angenommen. Außerdem erzeugt das Neuladen eine messbare native Speicherspitze durch die gleichzeitig vorhandene alte und neue Bibliothek.

Die gemeldeten zehn Sekunden auf dem physischen TV sind **noch nicht einer einzelnen Ursache zugeordnet**. Kein physischer Fernseher war verbunden. Die Emulatorwerte ersetzen weder eine ARM-TV-Messung noch einen Anbieter-Download über das WLAN des Fernsehers.

## Aktuelle Messwerte

Die Datei enthält 75.866.529 Bytes (72,35 MiB), 257.468 Einträge und keine vom Parser übersprungenen Einträge. Kein Eintrag landet in „Ohne Kategorie“.

| Bereich | Einträge | Kategorien |
| --- | ---: | ---: |
| Live | 6.186 | 182 |
| Filme | 20.416 | 131 |
| Serien/Episoden | 230.866 | 82 |
| Gesamt | 257.468 | 395 |

230.628 Einträge besitzen die eindeutige `/series/…`-Route; keiner davon wird einem anderen Bereich zugeordnet. Weitere Klassifikationen beruhen auf Metadaten, Kategorien oder Namen. Die Zahlen sind ein reproduzierbares Ergebnis des aktuellen Algorithmus, keine manuelle semantische Vollprüfung aller Einträge. Die historischen Zahlen in `PLAYLIST_REVIEW.md` stammen aus einem älteren Klassifikationsstand und sind hierfür keine Sollwerte.

| Messung | Ergebnis | Abgrenzung |
| --- | --- | --- |
| Windows `iptv-check --inspect`, Release, 5 Läufe | 836–997 ms, Median 876 ms | Reines lokales Parsen; anschließend Suche separat |
| TV-Parser auf demselben Windows-PC, Release, 5 Läufe | 842–1.030 ms, Median 895 ms | Derselbe Parser; kein Android-/JNI-/Netzwerkanteil |
| TV-Service auf Windows, lokal und mit URL-Basis, jeweils Erstimport/Reload | 904–1.092 ms | URL-Basis ist nur Auflösung relativer URLs; kein Download |
| Android-Core/JNI, zwei Sitzungen mit je 3 Imports | 861–1.181 ms | Google-TV-Emulator, Android 16, x86_64, 16-KB-Seiten, 2 GiB RAM; Rust nativ optimiert, Android-App Debug |
| Android: lokale temporäre Dateikopie | 244 ms im ersten Lauf | Derselbe begrenzte Kopiercode wie im Dateiimport; kein SAF-/USB-/WLAN-Vergleich |
| Activity-Start bis erste tatsächlich angelegte Listenzeile | 2.575–2.998 ms | Zwei Starts; keine Geräte-Neustarts und keine statistisch belastbare p95 |
| Erste Android-Ergebnisse je Bereich, JNI | ca. 2–6 ms | Je 64 Einträge, leere Suchanfrage |
| Host-Service: getestete Bereichssuchen inkl. Suchbegriffe | 1,5–14,9 ms | 36 Einzelmessungen, keine p95-Aussage |
| Kategorien-JSON auf dem Host | 28.065 Bytes, 0,49–0,74 ms | Kategorienübertragung erklärt hier keine zehn Sekunden |
| Android: URL-Import über lokalen HTTP-Testserver | 2.994 ms insgesamt, davon 822 ms Rust | Rest umfasst HTTP-Client-Start, lokalen Transfer, Dateischreiben, Scheduling und JNI; kein reiner Netzwerkzeitwert |
| Android: belegter nativer Heap nach Import | ca. 136,6–136,8 MiB | Ohne Videowiedergabe |
| Android: abgetastete native Heap-Spitze | ca. 263–265 MiB | 25-ms-Abtastung über Erstimport, Reloads und UI-Test; kann echte Spitze unterschätzen |
| Android: Prozess-PSS nach Imports/Reloads | ca. 196–280 MiB | Momentaufnahmen, kein PSS-Spitzenprofil; nach UI-Start ca. 233–235 MiB |

Die Vergleiche laufen nacheinander auf demselben Host, ohne kontrolliertes Leeren des Betriebssystem-Dateicaches. Unterschiedliche Hardware, CPU-Last und warme Caches beeinflussen die Werte. Die direkte lokale Datei lässt sich damit nicht fair gegen einen vollständigen Anbieterdownload auf dem TV vergleichen.

### Reproduzierter Formatfehler

Der lokale Testserver liefert `text/html` und drei HTML-Zeilen bei HTTP 200. `SourceLoader` übergibt die Datei mit der endgültigen HTTP-Adresse als Basis. Der Rust-Parser versucht für jede Nicht-Kommentarzeile `base.join(...)`; dadurch werden HTML-Zeilen zu HTTP-Adressen und der Import meldet **Erfolg mit drei Einträgen**. Bei einer vorher geladenen Liste würde dadurch sogar ein ungültiger Katalog die funktionierende Bibliothek ersetzen.

### Reproduzierte Fehlerweitergabe

Lokale Antworten mit HTTP 401, 403, 404, 429, 500 und 503 erzeugen jeweils `IllegalStateException("http_<Status>")`. Der Code geht bis `launchSafe`, dessen Mapping keine dieser Meldungen behandelt. Alle sechs landen daher im Text „Vorgang fehlgeschlagen. Quelle, Verbindung und Speicher prüfen.“. Die Oberfläche kann die tatsächlich bereits bekannten HTTP-Statuscodes nicht unterscheiden.

### Vom Nutzer angeforderte Anbieterstreams

Nach ausdrücklicher Freigabe wurden ausschließlich die drei genannten Bein-Sport-Streams aus der vollständigen Fixture abgespielt (Parser-IDs 91, 92 und 93). Der Test lief auf demselben TV-Emulator mit dem Standard-Liveprofil, über den Netzwerkzugang des Hosts. Die Anbieterantwort kann zu einem anderen Zeitpunkt oder am tatsächlichen Fernseher abweichen.

| Stream | Erstes gerendertes Bild | Nachgewiesene Wiedergabe | Beobachtete HTTP-/Media3-Fehler |
| --- | ---: | --- | --- |
| Bein_Sport 1 (UHD) | 1.635 ms | Ja | Keine |
| Bein_Sport 2 (UHD) | 2.685 ms | Ja | Keine |
| Bein_Sport 3 (UHD) | 731 ms | Ja | Keine |

Alle drei lieferten im Test H.264 mit 1920×1080. Ein Erfolg verlangt ein echtes `onRenderedFirstFrame`-Ereignis, laufende Wiedergabe und mindestens 1,5 s Medienposition. Der erste Stream bestand zusätzlich 30 Sekunden kontinuierliche Wiedergabe ohne beobachtetes Nachpuffern, Pause, manuelles Reconnect (1.130 ms bis `isPlaying`) sowie Freigabe/Rückkehr beim Lifecycle-Wechsel. Reconnectzeit ist keine neue Erstbildmessung. Die beiden anderen Streams wurden kurz angespielt; ein Langzeittest aller drei ist damit nicht abgedeckt.

In dieser separaten Sitzung dauerte der vollständige lokale Bibliotheksstart 2.423 ms, davon 1.418 ms Rust-Import. Prozess-PSS lag nach Import bei ca. 216 MiB und während der Wiedergabestichproben bei ca. 232–240 MiB. Keine Anbieter-Playlist wurde heruntergeladen; diese Werte messen lokale Playlist plus getrennte Streamwiedergabe. Der gemeldete sporadische Fehler auf dem tatsächlichen TV wurde dabei nicht reproduziert.

## Befunde im Code

| Priorität | Befund und Auswirkung | Einstiegspunkte |
| --- | --- | --- |
| P0 | Bekannte HTTP-/I/O-/JSON-Fehler verlieren in der UI ihre Bedeutung; Rust verwirft teilweise schon vorher den ursprünglichen I/O-Grund. | `MainActivity.kt:280`, `SourceLoader.kt:69`, `service.rs:131`, `service.rs:186` |
| P0 | HTML/Fehlerantworten werden beim URL-Import als relative Streamadressen interpretiert. Kein brauchbarer Formatnachweis vor Bibliothekswechsel. | `service.rs:146`, `playlist.rs:110`, `SourceLoader.kt:69` |
| P0 | Serien fehlen im Xtream-Transport und -Modell. Eine Änderung an Dateiendungsheuristiken behebt das nicht. | `SourceLoader.kt:44–60`, `service.rs:193`, `xtream.rs:7` |
| P1 | Jeder Start lädt `sources.first()` vollständig neu. Es gibt keinen persistenten Katalog, keine bedingten HTTP-Abfragen und keine Wiederherstellung der zuletzt ausgewählten Quelle. | `MainActivity.kt:111–115`, `SourceStore.kt` |
| P1 | Der Dateiimport kopiert die gesamte Datei zuerst in den Cache. Windows liest lokale Dateien direkt. Bei URL-Quellen kommt der vollständige Download vor dem Parsen hinzu. | `SourceLoader.kt:37–66`, Windows `sources.rs:103–128` |
| P1 | `Engine` hält die alte Playlist bis zum erfolgreichen vollständigen Aufbau der neuen. Das schützt vor Datenverlust, verdoppelt aber vorübergehend den Katalogspeicher. | `service.rs:146–190`, `service.rs:378` |
| P1 | Xtream lädt sequenziell Account, Live-Kategorien, Live-Liste, Filmkategorien und Filmliste. Strings/Android-JSON, gemeinsames serialisiertes JSON und Rust-JSON verursachen zusätzliche Kopien. Accountprüfung erfolgt im Rust-Mapping erst nach den Downloads. | `SourceLoader.kt:49–60`, `service.rs:193`, `xtream.rs` |
| P1 | 128 MiB und 500.000 Einträge bleiben harte Grenzen. Die Eintragsgrenze wird erst nach vollständigem Aufbau geprüft. Einfaches Erhöhen verschärft das Speicherproblem. | `service.rs:16–17`, `service.rs:378`, `SourceLoader.kt:18` |
| P1 | Import und Wiedergabekommandos teilen denselben seriellen Executor/Engine-Mutex. Während des Rust-Imports warten Suche, Auswahl und Recovery. | `NativeBridge.kt:17–24`, `iptv-jni/src/lib.rs:54–62` |
| P2 | RecyclerView virtualisiert Views; `rows = rows + value` hält und kopiert beim Weiterblättern dennoch alle zuvor geladenen DTOs. Bei langem Scrollen steigen Aufwand und Speicher. | `MainActivity.kt:562` |
| P2 | M3U-Attribute werden pro Eintrag in eine neue HashMap kopiert. Namen/Kategorien werden zusätzlich im Suchtext gespeichert. Optimierbar, aber aktuell kein belegter Zehn-Sekunden-Hotspot. | `playlist.rs:404`, `playlist.rs:235` |

Die Messwerte belegen erhöhte Speicherlast beim Reload. Sie belegen keinen OOM auf dem Nutzer-TV. Ein `OutOfMemoryError`, ein nativer Allokationsabbruch oder ein Prozessabbruch durch Android wird auch nicht zuverlässig durch das vorhandene `catch (Exception)` oder Rust-`catch_unwind` abgefangen.

## Umsetzung in sinnvoller Reihenfolge

### 1. Diagnose und Importvalidierung — P0

1. Einen strukturierten Fehlervertrag einführen: `domain`, `stage`, `code`, optionale sichere HTTP-/OS-/Media3-Codes, Wiederholbarkeit und passende Nutzeraktion. Kotlin und Rust verwenden denselben Vertrag. Keine Exceptiontexte oder vollständigen URLs ungeprüft anzeigen.
2. Zeiten und Fortschritt getrennt erfassen: Quelle öffnen/DNS/Verbindung, Antwortbeginn, Bytes/Download, Dateikopie, Parser, Index, Commit, erste sichtbare Zeile. Ein Import erhält eine Diagnose-ID; Importgeneration und Revision begrenzen veraltete Antworten.
3. Vor dem Parsen Signatur/Präfix und tatsächlichen Inhalt prüfen: Extended-M3U, unterstützte einfache URL-Listen sowie HLS ausdrücklich unterscheiden. HTML, JSON-Fehlerseiten und binäre Antworten abweisen. `Content-Type` ist ein Hinweis, kein alleiniger Freigabegrund; falsch deklarierte echte M3U müssen weiterhin funktionieren. Relative Streamadressen nur in einem als Playlist bestätigten Format zulassen.
4. I/O-Ursachen erhalten. Ein Lesefehler ist kein Syntaxfehler. Fehlende URI-Berechtigung, fehlende Datei, voller Datenträger, Netzwerkabbruch und ungültige Metadaten separat behandeln. Verworfene Einträge mit sicheren Grundzählern/Zeilennummern melden.
5. Fehler nach dem Katalogwechsel, beispielsweise beim Speichern der Quelle, als „Liste geladen, Quelle nicht gespeichert“ darstellen. Der aktuelle Sammelhandler kann auch einen solchen Teilfehler wie einen vollständigen Importfehler aussehen lassen.
6. Fehlerdetails und letzte erfolgreiche Importstatistik in einem kopierbaren, redigierten Diagnosebericht anbieten. APK-Version, Build-ID und Rust-Core-/Schemasignatur einschließen; damit lässt sich der installierte TV-Stand eindeutig prüfen.

Abnahme: Alle unten genannten Fehlerklassen liefern verschiedene sinnvolle Texte; HTML 200 wird abgewiesen, die vorige Bibliothek bleibt erhalten; die echte Datei wird über Datei- und URL-Import mit identischen Bereichs-/Kategoriezahlen geladen.

### 2. Serien und Kategorien absichern — P0

1. Die bestehende korrekte M3U-Erkennung erhalten und Windows/TV über einen gemeinsamen Core bzw. gemeinsame Golden-Tests gegen Auseinanderlaufen absichern. Prüfen: direkte und relative URLs, `/series/`, explizite Typangaben, Episodennamen, Unicode-Gruppen, gleiche Gruppennamen in mehreren Bereichen sowie widersprüchliche Hinweise.
2. Kategorie und Inhaltsbereich als unterschiedliche Eigenschaften behalten. Anbietergruppen bleiben unter Live/Filme/Serien erhalten; keine Ersetzung sämtlicher Gruppen durch zwei Sammelkategorien.
3. Xtream um Serienkategorien und Serienkatalog erweitern. Serien sind zunächst Katalogobjekte mit `series_id`, keine künstlich erzeugten abspielbaren Film-Streams. Staffeln/Episoden erst beim Öffnen der Serie laden und cachen; nicht für jede Serie schon beim Start einen Detailrequest senden.
4. Anmeldung vor großen Katalogabrufen prüfen. Unterstützte Bereiche und Teilfehler pro Anbieter erfassen; ein nicht unterstützter Serienendpunkt darf nicht stillschweigend wie ein erfolgreich leerer Serienkatalog aussehen.
5. Aktiven Quellenimport samt Quelle/Art in den Diagnosedetails sichtbar machen. Wenn der aktuelle Core dieselbe M3U richtig liest, zuerst installierten Build und tatsächliche Antwort vergleichen, bevor die Klassifikation pauschal geändert wird.

Abnahme: Aktuelle echte M3U unverändert mit 230.866 Episoden und 82 Serienkategorien; synthetisches Xtream mit mehreren Serien, Staffeln und Episoden; Fehler im Serienendpunkt sichtbar, funktionsfähige andere Bereiche bleiben nutzbar.

### 3. Wiederholte Vollimporte vermeiden — P1, größter Hebel für Startzeit

1. Validierten Katalog lokal versioniert speichern und beim Start sofort die zuletzt aktive Quelle aus diesem Katalog zeigen. Download/Aktualisierung laufen danach im Hintergrund mit klarer Aktualisierungsanzeige.
2. Für HTTP `ETag`/`Last-Modified` und bedingte Requests verwenden, sofern unterstützt. Bei 304 den vorhandenen Katalog behalten. Ohne Validator definierte Aktualisierungsregel und manuelles Neuladen anbieten; kein unsicherer ewiger Cache.
3. Bei Dateien persistente SAF-Berechtigung und Änderungserkennung berücksichtigen. Wenn der Dokumentanbieter keine verlässlichen Änderungsdaten liefert, Cache anzeigen und kontrolliert neu validieren. Keine Löschannahme allein aus fehlender Änderungszeit.
4. Cache und Metadaten atomar ersetzen, Schema-/Parserversion prüfen und nach Abbruch den letzten vollständigen Stand öffnen. Anbieter-URLs können Zugangsdaten enthalten: Cache mindestens ebenso schützen wie die bisherige Quellenablage, aus Backups ausschließen und beim Entfernen der Quelle löschen.
5. EPG-Start entkoppeln, damit sein Fehler keinen erfolgreichen Playlist-Start überlagert. Zuerst Katalog und erste Zeilen zeigen, optionale Zusatzdaten separat laden.

Abnahmeziel für die Referenzliste: aus gültigem Cache erste bedienbare Einträge unter einer Sekunde auf dem festgelegten Referenz-TV, auch ohne Netzwerk. Das ist ein zu messendes Ziel, keine durch den Emulator belegte Zusage.

### 4. Sehr große Kataloge mit begrenztem RAM — P1

1. Einen datenträgergestützten Katalog hinter einer klaren Repository-/Core-Schnittstelle einführen. SQLite mit Indizes für Quelle, Generation, Bereich und Kategorie ist eine geeignete Option. Paging/SQL-Suche liefert nur benötigte DTOs; keine vollständige zweite Objektbibliothek im RAM.
2. Parser auf inkrementelle Ausgabe an einen begrenzten Import-Sink erweitern. Neue Generation transaktional aufbauen, nach erfolgreicher Validierung aktivieren. Die vorige Generation bleibt bis zum Commit lesbar; bei Fehler wird nur die unvollständige Generation verworfen. Speicher sparen, ohne die alte Bibliothek vorzeitig zu löschen.
3. Importverbindung/Importworker von UI- und Playback-Core trennen. Ein bloßer zweiter Executor genügt nicht, solange derselbe Engine-Mutex die gesamte Parserlaufzeit hält. Commit ist kurz, revisionsgebunden und verwirft veraltete Importjobs.
4. Xtream-Arrays direkt streamend aus den begrenzten Response-Dateien in den Katalog übernehmen. Android-JSONObject/JSONArray → großes JSON-String → Rust-Value vermeiden. Kontoantwort klein halten; Metadatenabrufe nur begrenzt parallelisieren, wenn der Anbieter dies verträgt.
5. Dateideskriptor-/Streaming-Zugriff prüfen, um bei SAF-Dateien die zusätzliche Vollkopie zu vermeiden. Fallback für nicht seekbare Dokumentanbieter beibehalten; Lebensdauer, Abbruch und Lesefehler sauber regeln. Dieser Schritt folgt den Messwerten des echten TV.
6. Byte-, Zeilen-, Eintrags- und Datenträgergrenzen bereits während des Imports durchsetzen. Abbruch regelmäßig prüfen, HTTP-Call abbrechen und temporäre Generation aufräumen. Grenzen erst nach Speichernachweis erhöhen und mit konkretem Grund kommunizieren.
7. UI-Seiten in einem begrenzten Fenster halten. Falls ein einfacher Append weiterhin genügt, mindestens die wiederholte vollständige Listenkopie beseitigen. Suche muss ihre bisherige Semantik behalten: Teilstrings/mehrere Begriffe und Kategorieintersection nicht unbemerkt durch eine andere FTS-Semantik ersetzen.

Zielgrößen: 100.000, 257.468, 500.000 und mindestens 1.000.000 synthetische Einträge, einschließlich einer Datei über 128 MiB. Bestehende Grenzen sind dabei zunächst erwartete Abweisungen; Erfolg oberhalb dieser Grenzen erst nach Umsetzung des neuen Speicherpfads verlangen. Gleichzeitigen alten Katalog, Import, Index und Videowiedergabe mitmessen. Die heutige native Reload-Spitze soll bei der Referenzliste mindestens halbiert werden; endgültiges Prozessbudget anhand des schwächsten Ziel-TV festlegen.

Eine persistente Datenquelle mit seitenweiser Ausgabe entspricht dem dokumentierten [Android-Muster für Datenbank und Paging](https://developer.android.com/topic/libraries/architecture/paging/v3-network-db). Für dieses Projekt ist zu entscheiden, ob SQLite im gemeinsamen Rust-Core oder Android-seitig liegt; zwei konkurrierende Kataloge vermeiden.

### 5. Parser nur anhand eines Profils weiter optimieren — P2

Die Attribut-HashMap durch gezieltes Auslesen unterstützter Felder ersetzen, Kategorien/konstante Strings wiederverwenden und unnötige Lowercase-/URL-Zwischenobjekte prüfen. Klassifikationsprioritäten und Unicode-Verhalten bleiben erhalten. Optimierungen mit denselben Fixtures und Release-Einstellungen vergleichen; kein pauschales „unter eine Sekunde auf jedem TV“ versprechen.

## Welche Fehlermeldung bei welchem Nachweis?

| Nachweis | Nutzertext / Kategorie | Aktion |
| --- | --- | --- |
| HTTP 401 | „Anbieter lehnt die Anmeldung ab (HTTP 401).“ | Zugangsdaten/Anmeldung prüfen |
| HTTP 403 | „Anbieter verweigert den Zugriff (HTTP 403).“ | Berechtigung, Konto oder Anbieterfreigabe prüfen; Ursache nicht auf ein falsches Passwort festlegen |
| HTTP 404/410 | „Die Playlist-/Streamadresse wurde nicht gefunden bzw. ist nicht mehr verfügbar.“ | Adresse aktualisieren |
| HTTP 429 | „Zu viele Anfragen beim Anbieter.“ | Begrenzte Wiederholung, `Retry-After` beachten |
| HTTP 5xx | „Der Anbieter meldet einen Serverfehler (HTTP …).“ | Später erneut versuchen, vorhandenen Katalog behalten |
| DNS/Connect/Read-Timeout | „Server nicht aufgelöst / Verbindung nicht aufgebaut / keine weiteren Daten innerhalb des Zeitlimits.“ | Netzwerk und Anbietererreichbarkeit prüfen; lokal oder serverseitig ohne weiteren Nachweis offenlassen |
| TLS-Fehler | „Sichere Verbindung zum Anbieter fehlgeschlagen.“ | Zertifikat/Gerätezeit prüfen; keine automatische Abschaltung der TLS-Prüfung |
| HTML/JSON statt M3U | „Die Adresse liefert eine Webseite oder Fehlerantwort statt einer Playlist.“ | Direkten Playlist-Link bzw. Anmeldung prüfen |
| URI-Zugriff abgelehnt / Datei fehlt | „Die gespeicherte Datei kann nicht mehr geöffnet werden.“ | Datei erneut auswählen/Berechtigung erneuern |
| ENOSPC beim Cache-/Katalogschreiben | „Der Gerätespeicher reicht für den Import nicht aus.“ | Freien Speicher anzeigen, bereinigbare Appdaten anbieten |
| Importgrenze | „Die Importgrenze wurde überschritten: … von … Bytes/Einträgen.“ | Tatsächliche Schutzgrenze nennen; nicht als OOM darstellen |
| Belegter Arbeitsspeichermangel | „Der Arbeitsspeicher reicht für diesen Import nicht aus.“ | Vorhandenen Stand öffnen, Importumfang/Budget anpassen |
| Belegtes nicht unterstütztes Medienformat | „Dieser Stream verwendet ein auf diesem Gerät nicht unterstütztes Format.“ | Codec-/Decoder-Code in Details; kein Verbindungsfehler |
| Unbekannter Fehler | „Import fehlgeschlagen. Details: Diagnose …“ | Sichere technische Details behalten; keine erfundene Ursachenzuordnung |

RAM und freier Dateispeicher sind getrennte Ressourcen. Ein Timeout allein beweist weder eine defekte Quelle noch schlechtes WLAN. Prozessabbrüche durch Android lassen sich nicht zuverlässig in einem normalen Exceptionhandler melden: auf unterstützten Android-Versionen beim nächsten Start [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo) auswerten, insbesondere einen belegten `REASON_LOW_MEMORY`; auf älteren Versionen Ursache als unbekannt führen. Prävention und native/Java-/PSS-Messung folgen den [Android-Hinweisen zur Speicherverwaltung](https://developer.android.com/topic/performance/memory/manage-app-memory).

## Test- und Abnahmeplan

1. Exakte echte Fixture lokal und per lokalem HTTP importieren; gleiche Bereichs- und Kategoriezuordnung, Listenende, Suche und UI prüfen. Parserparität nicht nur mit Gesamtsummen, sondern mit redigiertem Vergleich der Feldzuordnung absichern.
2. Fehler injizieren: 401/403/404/410/429/5xx, DNS, verweigerte Verbindung, Connect-/Read-Timeout, TLS, abgeschnittener Body, falsche Content-Length, Gzip, HTML 200, kaputtes JSON, ungültige M3U, übergroße Zeile, Größen-/Eintragslimit, fehlende SAF-Berechtigung, voller Datenträger und abgebrochener Import. Vorige Bibliothek muss erhalten bleiben.
3. Kataloge in den genannten Größen, mehrere Reloads, Quellenwechsel während Import, Suche/Recovery während Import und Scrollen über viele Seiten testen. Kein unbegrenztes Anwachsen der DTO-Liste oder zurückgelassene temporäre Generationen.
4. Release-Build auf dem tatsächlichen ARM-TV prüfen: mindestens fünf Erstimporte und zehn Warmstarts je Quelle; Median/p95 von Download, Parser, Commit und erster sichtbarer Zeile, maximalen nativen/Java-Heap, PSS/RSS und verfügbaren Dateispeicher aufzeichnen. Dabei Version/ABI/Build-ID, Netzwerkart und Gerätemodell erfassen. Langsames WLAN und Standby ergänzen.
5. Die drei vom Nutzer angegebenen Bein-Sport-Streams separat auf Erstbild, Decoder, Datenzufluss, Unterbrechungen und Reconnect prüfen. „UHD“ im Namen bleibt eine Bezeichnung; die tatsächliche Auflösung kommt aus Media3. Ein erfolgreicher Kurztest ist keine Langzeitgarantie und beweist keine Playlist-Downloadgeschwindigkeit.

## Nachweise und Reproduktion

Unter `artifacts/playlist-performance-20261016/`:

- `summary.json`: zusammengefasste lokale Benchmarkdaten und Validierungsstand.
- `windows-inspect.json`, `android-core-host.json`, `host-memory.json`: Hostmessungen. Der Hostspeicher ist nicht auf Android übertragbar.
- `android-device.json`: erster lokaler Emulatorlauf.
- `android-device-http.json`: zweiter Lauf mit lokalem URL-Import und injizierten HTTP-/HTML-Antworten.
- `android-instrumentation*.txt`, `android-build.txt`: erfolgreiche Android-Messläufe und Build.
- `requested-samples.json`, `requested-streams-device.json` und `requested-streams-instrumentation.txt`: Zuordnung und erfolgreicher separater Test der ausdrücklich angeforderten drei Streams.

50 vorhandene Rust-/JNI-Tests bestehen; Clippy mit `-D warnings` besteht. Zwei neue lokale Android-Benchmarkläufe bestehen, einschließlich der drei UI-Bereiche; der separate Anbieter-Test mit drei Streams besteht ebenfalls. Das gebaute Test-APK enthält ausschließlich das synthetische Videoasset, keine private Playlist. Beim ersten Start des neuen Benchmarktests war der Kotlin-Test-Rückgabewert nicht `Unit`; korrigiert, anschließend beide Messläufe erfolgreich. Die bekannten Format-/Diagnosebugs werden hier gemessen und dokumentiert, noch nicht als behoben bewertet.

Hostbenchmark:

```powershell
. ./scripts/env.ps1
cargo run --release --locked --offline -p iptv-core --example playlist_benchmark -- `
  '../IPTV Player/tests/fixtures/BigListWithRealVideoStream.m3u' `
  'artifacts/playlist-performance-20261016/android-core-host.json'
```

Androidbenchmark: `LocalPlaylistBenchmarkTest` ist opt-in und auf genau die untersuchte Fixture ausgelegt. Vorher die Datei separat als `local-benchmark.m3u` nach `/sdcard/Android/data/de.iptv.tv/files/` im Testemulator übertragen. Das Test-APK enthält die private Liste nicht. Der Test stellt die Quellenliste wieder her und löscht seine übertragene Testdatei.

```powershell
adb -s emulator-5554 shell am instrument -w `
  -e class de.iptv.tv.LocalPlaylistBenchmarkTest `
  -e benchmarkPlaylist /sdcard/Android/data/de.iptv.tv/files/local-benchmark.m3u `
  de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner
```

Optional `PlaylistBenchmarkServer.java` mit Port und Fixturepfad starten, `adb reverse tcp:18889 tcp:18889` setzen und `-e benchmarkUrl http://127.0.0.1:18889` ergänzen. Der Server bindet ausschließlich localhost. Keine Streamwiedergabe in diesem lokalen Benchmark; der gesonderte Anbieter-Test verwendet die bereits vorhandene `PrivatePlaylistReviewTest`-Strecke. Zugangsdaten und vollständige Anbieter-URLs erscheinen in keinem der JSON-Berichte.

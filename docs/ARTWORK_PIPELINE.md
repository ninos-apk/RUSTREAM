# Cover-Pipeline: Laden, Cache und Prefetch

Film- und Serienposter erscheinen auch nach schnellem Halten von Runter/Hoch zuverlässig. Bereits geladene Cover kommen nach dem Zurückscrollen, einem Activity-Neustart und einem erneuten Playlistimport ohne Download aus Speicher oder Disk. Betroffen sind `ArtworkLoader`, `ArtworkPolicy`, `ArtworkDiskCache` und die Rasteranbindung in `MainActivity`.

## Ursachen der fehlenden Cover

Reproduziert mit `PosterScrollTest` (1.500 Filme, lokaler Bildhost mit 120–320 ms Latenz, einzelnen 503-, 404- und langsamen Adressen) auf dem Google-TV-Emulator (API 36, 1920×1080):

1. **Nur `bind()` löste Laden aus.** RecyclerView hängt gecachte Zeilen (`setItemViewCacheSize(18)`) ohne erneutes Binden wieder an. Ging die Anfrage einer solchen View verloren oder schlug sie fehl, blieb genau diese Karte leer, bis sie zufällig neu gebunden wurde.
2. **Vorübergehende Fehler galten als endgültig.** Timeout, Verbindungsabbruch, 429 oder 5xx sperrten Schlüssel und Adresse 60 s, ohne Retry; `bind()` reihte während der Sperre nichts ein. Im Test blieb jedes Cover mit einem einmaligen 503 dauerhaft leer, nach 3 s Hostausfall fehlten 30 s später noch 9 von 16 sichtbaren Covern.
3. **Warteschlange mit Verwerfen.** Bei mehr als 256 Aufträgen fielen die ältesten weg, also gerade die Zeilen, die beim Halten in den RecyclerView-Cache wanderten.
4. **Weiterleitungen.** `followSslRedirects(false)` blockierte auch http→https; solche Cover luden nie.
5. **Serielle Worker.** Vier Worker erledigten Adresse, Download und Dekodierung nacheinander. Veraltete Aufträge wurden erst nach einem Sprung auf den Main-Thread verworfen; ein unerreichbarer Host hielt einen Worker bis zu 20 s.
6. **Kein Disk-Cache, zu große Bitmaps.** Dekodiert wurde bis 480×720 statt in Kartengröße (192×288 px auf dem Testgerät); der Speicher-LRU hielt dadurch nur etwa 45 Poster. Ein Neustart lud jedes Cover erneut.
7. **Fokus beim Halten.** Ab 512 Zeilen wurden 64 Einträge vorne entfernt. Bei 6 Spalten verschob das jede Karte um 4 Spalten; der fokussierte Eintrag konnte den Bildschirm verlassen und der Fokus sprang ins Suchfeld.

## Ablauf

- **Zustand auf dem Main-Thread, Arbeit auf begrenzten Lanes:** Adressauflösung über den Core (3 gleichzeitig), Disk-Lesen (2), Netz (4, davon höchstens 2 spekulativ), ein einzelner Disk-Schreiber.
- **Priorität:** sichtbar > gebunden, aber nicht angezeigt (RecyclerView-Cache, GapWorker) > Prefetch nach Abstand > Revalidierung. Bei Gleichstand gewinnt die neueste Anfrage, also die Richtung, in die gescrollt wird.
- **Keine verlorenen Anfragen:** Eine gebundene View behält ihre Anfrage, bis sie ihr Bild zeigt. Ein Attach-Listener fragt bei wieder angehängten Views erneut; recycelte Views werden mit `unbind()` abgemeldet. Wartet niemand mehr, wird nicht gestartete Arbeit verworfen, laufende Downloads enden im Cache.
- **Deduplizierung:** eine Anfrage pro Adresse; das Ergebnis geht an alle Views, deren aktueller Schlüssel noch passt. Episoden einer Serie und wiederholte Logos teilen eine Dekodierung, auch über eine neue Katalogrevision.
- **Disk-Index:** Adressen, die der Disk-Cache nicht kennt, gehen direkt ins Netz statt hinter Disk-Lesern zu warten.

## Prefetch

| Zustand | Fenster |
| --- | --- |
| D-Pad gehalten (Schritte < 160 ms) | 1 Zeile voraus, entspricht dem RecyclerView-GapWorker |
| Pause ≥ 160 ms | 3 Zeilen voraus, 1 zurück; die nächsten 2 Zeilen werden in den Speicher dekodiert |
| 1,2 s Ruhe | 8 Zeilen voraus, 2 zurück; jenseits von 2 Zeilen nur in den Disk-Cache |

Das Fenster bleibt innerhalb der geladenen Seite und wird bei jeder Scrollbewegung ersetzt. Sichtbare Karten haben immer Vorrang; spekulative Downloads belegen höchstens 2 der 4 Verbindungen.

Bewertet und verworfen: **Vorladen der ganzen Playlist** (bei 50.000 Filmen und 40 KB je Poster etwa 2 GB, Minuten an Bandbreite und Startverzögerung) und **Aufwärmen während des Imports** (konkurriert mit dem ohnehin netz- und CPU-intensiven Import, der zuerst geöffnete Bereich ist unbekannt). Gewählt ist das gestaffelte Fenster am Viewport plus persistenter Cache: Der erste Bildschirm lädt sofort, die restliche geladene Seite im Leerlauf, jeder spätere Besuch kommt von der Disk.

## Caches

- **Speicher:** `LruCache` nach Adresse, `maxMemory/8` (16–64 MiB; 24 MiB auf dem Testgerät, etwa 110 Poster). `onTrimMemory`: Running-Low und UI-Hidden auf die Hälfte, Background auf ein Viertel, Moderate/Complete leeren. Die Zielgröße folgt der Kartenbreite in 16-px-Stufen; eine neue Größe leert den Speicher-Cache.
- **Disk:** `cacheDir/artwork`, Budget 2 % des Volumens, 32–160 MiB, höchstens ein Viertel des freien Platzes. LRU nach Zugriff (Dateizeit höchstens täglich aktualisiert), Räumen auf 90 %, ein Eintrag höchstens ein Achtel des Budgets. Schreiben über temporäre Datei und Umbenennen; Länge und CRC32 im Kopf machen abgebrochene oder fremde Dateien zu einem Miss, die Datei wird gelöscht. Reste unterbrochener Schreibvorgänge entfernt der Start.
- **Datenschutz:** Dateinamen sind HMAC-SHA256 aus einem zufälligen Geräteschlüssel (`cacheDir/artwork.key`) über Größe und Adresse; Adressen stehen weder auf der Disk noch in Logs. Fehlt der Schlüssel, beginnt der Cache leer. `cacheDir` ist vom Backup ausgeschlossen.
- **HTTP:** `no-store` wird nie gespeichert. `max-age`/`Expires` gelten mit Grenzen von 1 bis 30 Tagen, ohne Angabe 7 Tage. Abgelaufene Einträge erscheinen sofort und werden einmal pro Sitzung über eine freie Verbindung mit ETag/Last-Modified revalidiert; 304 verlängert die Laufzeit, neue Inhalte ersetzen das angezeigte Bild.

## Größe und Format

- Dekodiert wird auf die tatsächliche Postergröße: Bounds lesen, Zweierpotenz-Sampling bis knapp über das Ziel, die exakte Restskalierung im selben Dekodiervorgang. Grenzen: 8192 px Quellbild, 8 MiB Antwort.
- Auf der Disk bleiben Originale bis 96 KiB in etwa Zielgröße unverändert. Größere werden in Dekodiergröße als JPEG (Qualität 88, mit Alpha WebP) gespeichert, nie größer als das Original. Ein 1200×1800-JPEG belegt danach weniger als ein Achtel.
- **TMDB:** `image.tmdb.org/t/p/<Größe>/…` wird auf die kleinste feste Breite (154/185/342/500/780) umgeschrieben, die die Karte abdeckt, nie vergrößert; fehlt sie (404), folgt das Original. Andere Hosts werden nicht umgeschrieben, weil Skalierung dort nicht vorausgesetzt werden kann. `Accept` bevorzugt WebP, falls ein CDN das anbietet.

## Fehler und Wiederholung

| Klasse | Auslöser | Verhalten |
| --- | --- | --- |
| fehlend | 404, 410 | Platzhalter, 30 min keine neue Anfrage |
| verweigert | 401, 403 | wie fehlend |
| ungültig | keine http(s)-Adresse, https→http-Weiterleitung, mehr als 5 Weiterleitungen, zu groß, kein Bild | wie fehlend |
| vorübergehend | Timeout, Abbruch, 408/425/429/5xx | Backoff 1, 3, 9, 30, 60, 120, 300 s, nur solange eine View wartet |

- Ein erneuter Besuch wiederholt einen vorübergehenden Fehler frühestens 5 s nach dem letzten Versuch.
- Ein Host, der viermal in Folge nicht erreichbar ist, pausiert 15 s (verdoppelnd bis 2 min), damit seine Cover die Verbindungen anderer Hosts nicht blockieren.
- Der erste Erfolg nach Verbindungsabbrüchen setzt den Backoff aller Cover dieses Hosts zurück; ein neues Netzwerk (ConnectivityManager) löscht alle vorübergehenden Fehler.
- Logs enthalten nur Zähler: der Disk-Cache beim Öffnen, Fehler höchstens einmal pro Minute. *Einstellungen → Diagnose* enthält den Abschnitt `artwork` mit Treffern, Netzvolumen, Fehlern, Prefetch-Nutzen, Dekodierzeit und Main-Thread-Zeit.

## Lebenszyklus

`onStop` startet keine neue Arbeit mehr, laufende Downloads enden im Cache; `onStart` setzt fort. `onDestroy` bricht alles ab. Beim Playlistwechsel ändern sich die Zeilenschlüssel, gleiche Adressen bleiben Cache-Treffer. Seiten werden im Raster in ganzen Zeilen nachgeladen und entfernt, damit Karten nicht die Spalte wechseln.

## Messungen

Google-TV-Emulator `sdk_google_atv64_amati_x86_64_16k`, API 36, 1920×1080, 6 Spalten, Debug-Build. Derselbe `PosterScrollTest` gegen den vorherigen Stand (Commit `1b7ad58`) und die neue Pipeline:

| Messung | vorher | nachher |
| --- | --- | --- |
| Gültige Cover dauerhaft leer | in jeder Phase, mindestens 1 von 16 | keine |
| Erster Bildschirm vollständig (ohne die 503-Testcover) | 1.628 ms | 1.177 ms |
| Nach 3 s Hostausfall | nach 30 s fehlen 9 von 16 | alle nach 5,3 s |
| Neustart, erster Bildschirm | 1.820 ms, 29 Downloads | 88 ms, 3 Anfragen, davon 2 an absichtlich fehlende 404-Adressen (Fehlerstatus wird nicht über Neustarts gespeichert) |
| Unnötige Doppeldownloads | 28 | 2 |
| Geteiltes Poster (Episoden) | 2 Downloads | 1 Download |
| Fokus nach 6 s gehaltenem Runter | Suchfeld | Karte |
| Main-Thread-CPU während der Scrollphasen | 6.250 ms | 5.710 ms |
| PSS nach GC | 149 MB | 136 MB |
| Native Heap nach GC | 62,9 MB | 51,4 MB |

Eigenanteil des Loaders am Main-Thread in derselben Messung: 733 ms CPU für 2.100 Bindungen und 1.000 Dispatch-Läufe; Dekodierung auf I/O-Threads im Mittel 1,7 ms. Bildraten des Emulators (p50 36 ms vorher wie nachher) sind nicht repräsentativ und werden nicht als Verbesserung gewertet. Die Wartezeiten nach dem Halten sind zwischen den Ständen nicht direkt vergleichbar: Der alte Stand verlor beim Halten den Fokus und hörte vorzeitig auf zu scrollen.

## Tests

- JVM: `ArtworkPolicyTest` (Fehlerklassen, Backoff, Frische, Weiterleitungen, TMDB-Größen, Geometrie, Prefetch-Fenster, Disk-Budget) und `ArtworkDiskCacheTest` (Roundtrip, Namen ohne Adresse, LRU, beschädigte Dateien, Neuaufbau des Index, Nebenläufigkeit).
- Android: `ArtworkPipelineTest` (Retry, keine Wiederholung bei 404, Neustart offline aus dem Disk-Cache, verkleinerte Speicherung, `no-store`, Prefetch, Revalidierung mit 304, Weiterleitung) und `PosterScrollTest` (Szenario oben, schreibt `/sdcard/Download/iptv-poster-scroll.json`). `ArtworkAndUpgradeTest` besteht unverändert.
- Gesamte Instrumentierungssuite auf dem Emulator: 39 Tests, 35 bestanden, 3 optionale Tests ohne externe Fixtures erwartungsgemäß per Annahme abgebrochen, 1 Fehlschlag in `UiDesignTest` (Klick in einen gerade erscheinenden Dialog). `UiDesignTest` ist schon vorher unzuverlässig: In 16 abwechselnden Läufen derselben APKs schlug die Klasse im alten Stand 2 von 8, im neuen 1 von 8 Mal fehl, der betroffene Leerstart-Test bestand in beiden 8 von 8. Lint ohne neue Befunde, R8-Release-Build erfolgreich.

## Offen für die Geräteabnahme

- Bildzeiten und Ruckler auf echter Google-TV-Hardware mit Release-Build; der Emulator rendert langsamer als ein Gerät.
- Speicher auf Geräten mit 1 GB RAM und kleinem Heap (Budget dort 16 MiB).
- Echte Anbieter: TMDB-Umschreibung, Weiterleitungen über TLS, Hosts mit Ratenbegrenzung.
- 4K-Oberflächen (größere Zielgröße), Disk-Räumung nach Wochen, Stromausfall während des Schreibens.

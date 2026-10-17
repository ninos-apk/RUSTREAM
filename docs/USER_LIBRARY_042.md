# Zuletzt gesehen, Favoriten und Fortsetzen: Android TV 0.4.2

Live, Movies und Series haben jetzt je eine Kategorie **Zuletzt gesehen** und eine Kategorie **★ Favoriten**. Beide stehen vor „Alle Kategorien“. Filme und Episoden setzen die Wiedergabe an der gespeicherten Position fort. Damit ist Abschnitt 7 des [Analyse- und Umsetzungsplans](WINDOWS_CONTENT_LOGIK_ANALYSE_UND_PLAN.md) umgesetzt.

## Verhalten

| Bereich | Zuletzt gesehen | Favoriten | Fortschritt |
| --- | --- | --- | --- |
| Live | Kanal-ID (stabiler Schlüssel), Name, Logo, Zeitpunkt | Kanal | nie |
| Movies | Film, Zeitpunkt | Film | Position, Dauer, Zeitpunkt, abgeschlossen |
| Series | genau ein Eintrag pro Serie mit letzter Staffel, Episode, Episodentitel und Episodenschlüssel | nur die ganze Serie | pro Episode |

- **Verlauf:**
  - Ein Eintrag entsteht nur beim tatsächlichen Wiedergabebeginn (Media3 `isPlaying`). Auswahl, Detailansicht, Staffelwechsel und fehlgeschlagene Streams zählen nicht.
  - Ein vorhandener Eintrag wird nach vorn verschoben, ohne Duplikat. Retry, Pause und Fortsetzen erzeugen keinen neuen Eintrag.
  - Pro Bereich sind es standardmäßig 15 Einträge. Unter *Einstellungen → Zuletzt gesehen · Anzahl* sind 5, 10, 15, 20, 30 oder 50 einstellbar. Wird der Wert verringert, wird sofort gekürzt.
  - Bei gleichem Zeitstempel entscheidet eine monotone Folgenummer über die Reihenfolge.
- **Unabhängigkeit:**
  - Verlauf, Favoriten und Fortschritt sind getrennte Datenbereiche.
  - Das Entfernen eines Favoriten oder Verlaufseintrags löscht keinen Fortschritt. Dasselbe gilt für das Herausfallen aus den letzten N Einträgen.
  - Ein Inhalt kann gleichzeitig in beiden Kategorien stehen.
- **Fortsetzen:**
  - Die Filmdetails zeigen „Fortsetzen bei 23:10“ (fokussiert), „Von Anfang an“ und den gesehenen Anteil.
  - Episoden und Kanalwechsel per Channel +/− innerhalb von Filmen setzen automatisch fort.
  - Die Serienübersicht zeigt „▶ Fortsetzen · S2 · E5 · 12:34“ mit Episodentitel und wählt beim Öffnen die zuletzt gesehene Staffel vor.
  - Unter 10 s gibt es kein Fortsetzen.
  - Als abgeschlossen gilt ein Inhalt bei `STATE_ENDED` oder bei bekannter Dauer, wenn mindestens 95 % gesehen sind und höchstens 120 s fehlen. Abgeschlossene Inhalte starten wieder bei 0.
  - Ohne bekannte Dauer wird nur die Position gespeichert, ohne Prozentbalken.
- **Anzeige:**
  - Karten zeigen den Fortschrittsbalken in Akzentfarbe und „23:10 / 1:45:00“ bzw. „✓ Gesehen“. Serienkarten zeigen „S1 · E2 · Position“.
  - Live- und Episodenzeilen zeigen Position bzw. Stern am Zeilenende.
  - Nicht mehr enthaltene Inhalte erscheinen gedimmt als „Derzeit nicht verfügbar“. Sie bleiben gespeichert, bis sie entfernt werden.
- **Leeransichten:** Es gibt eigene Texte für „noch nichts angesehen“, „noch keine Favoriten“ und „Bibliothek wird aktualisiert“ (Katalog älter als Schema 3).

## Bedienung

- **Stern:**
  - Ist der Inhalt ein Favorit, erscheint ein gefüllter Stern in Akzentfarbe. Auf der fokussierten Karte erscheint ein Umrissstern als Hinweis.
  - Gesetzt wird der Stern über die ★-Taste in den Film- und Seriendetails oder die ★-Taste in den Player-Controls. Diese deckt Live auch im Vollbild ab und gilt bei Episoden für die Serie.
  - Ein langes OK bzw. die Menü-Taste auf Karte oder Zeile öffnet ein Aktionsmenü mit „Zu Favoriten / Aus Favoriten“, „Aus ‚Zuletzt gesehen‘ entfernen“ und „Vollständigen Titel anzeigen“.
  - Per Touch oder Maus lässt sich der sichtbare Stern auf der Karte direkt umschalten.
- **OK auf Listen** wirkt jetzt beim Loslassen. Ein langer Druck öffnet deshalb nur das Aktionsmenü und startet nicht zusätzlich die Wiedergabe. Die D-Pad-Navigation im Raster bleibt unverändert: Pro Karte gibt es weiterhin genau ein Fokusziel.
- **Favoriten-Liste:** Nach dem Entfernen springt der Fokus auf den nachfolgenden Eintrag. Ist die Liste leer, landet er auf der Kategorie.
- **Channel +/−:** In „Zuletzt gesehen“ und „Favoriten“ von Live und Movies folgt Channel +/− der beim Start angezeigten Reihenfolge.
- Ein Live-Verlauf, der gerade fokussiert ist, sortiert sich nicht unter dem Fokus um. Er wird beim nächsten Öffnen der Kategorie aktualisiert.

## Architektur

```mermaid
flowchart LR
    Media3[ExoPlayer] -- "Sekundenticker, Pause, Ende, Release" --> PC[PlaybackController]
    PC -- "PlaybackSample (Token der Auswahl)" --> WT[WatchTracker]
    WT --> UL[UserLibrary · Main-Thread, O(1)-Abfragen]
    UL -- "Snapshot" --> ST[UserLibraryStore · ein IO-Writer]
    ST --> F[(user-library.enc · Keystore AES-GCM · AtomicFile)]
    UI[MainActivity] -- "resolve(keys)" --> Core[Rust-Katalog Schema 3]
    UI --> UL
```

- **Stabile Schlüssel:**
  - Numerische Katalog-IDs ändern sich bei jedem Import, Xtream-Episoden sogar bei jeder Auflösung. Schema 3 speichert deshalb pro Eintrag `ukey = "c:" + SHA-256(Salz ‖ Bereich ‖ Original-URL)[0..16]` als indizierte Spalte.
  - Serien verwenden die vorhandene Identität `s:xtream:{id}` bzw. `s:m3u:{normalisierter Titel}`.
  - Das zufällige Salz pro Quelle liegt im versiegelten `CatalogStore`-Manifest und bleibt über alle Generationen gleich. So stehen keine ungesalzenen Hashes von URLs mit Zugangsdaten im Klartext.
  - `browse`, `series_page` und `entry` liefern `key`.
  - `resolve` bildet bis zu 128 Schlüssel pro Aufruf per Indexzugriff auf die aktuelle Revision ab. Für Episoden liefert es auch Serie, Staffel und Episode mit. Kotlin teilt Anfragen in Pakete von höchstens 64 Schlüsseln bzw. 8 KB auf, unterhalb der 16-KB-JNI-Grenze.
- **Fortsetzen im Core:** `select` nimmt optional `resume` (Sekunden, nur VOD) an. Bis Media3 eine eigene Position meldet, verwenden auch Reconnects diese Position. `recovery.rs` und `playlist.rs` bleiben unverändert (Herkunftsprüfung).
- **Persistenz:**
  - Es wird dieselbe Technik wie bei den Quellen verwendet (`SourceStore`: Android Keystore, AES-GCM, `AtomicFile`, `noBackupFilesDir`). Es gibt keine neue Datenbank.
  - Struktur: `profiles.default.sources.<Quelle>.{recent,favorites,progress}`. Spätere Benutzerprofile brauchen damit keine Migration. Profile existieren derzeit nicht.
  - Daten werden pro Quelle gespeichert, weil Schlüssel nur innerhalb einer Quelle eindeutig sind. Beim Entfernen einer Quelle werden ihre Benutzerdaten mit entfernt; der Bestätigungsdialog nennt das.
- **Schreiblast:**
  - Fortschritt wird bei ≥ 10 s Positionsänderung im Speicher aktualisiert, sofort bei Pause, Ende, Stopp, Wechsel und Freigabe des Players.
  - Ein einzelner IO-Writer fasst Änderungen 2 s lang zusammen. Der Snapshot wird auf dem Main-Thread nur kopiert; JSON und Verschlüsselung laufen im Writer.
  - `onStop` und `onDestroy` schreiben sofort. Ein Prozessabbruch verliert typischerweise höchstens rund 12 s Position.
  - Media3-Callbacks schreiben nie.
- **Schutz vor falscher Zuordnung:**
  - Jeder Player meldet sich mit dem Token der Auswahl, die ihn erzeugt hat. Eine alte Instanz kann deshalb nie unter einem neuen Inhalt speichern.
  - Eine Fortschrittsmeldung nach dem Ende überschreibt den Status „abgeschlossen“ nicht.
- **Robustheit:**
  - Ein unlesbarer Benutzerdaten-Snapshot wird einmal als `user-library.enc.unreadable` beiseitegelegt; die App startet mit leeren Daten.
  - Schreibfehler behalten den letzten gültigen Snapshot, der neue Zustand wird beim nächsten Schreiben erneut versucht.
  - Fehler im Verlauf unterbrechen nie die Wiedergabe.
  - Der Diagnosebericht enthält `user_library` (Anzahl Schreibvorgänge, letzter Fehler, Limit).
- **Kosten** (Host, Release-Build, Referenzliste mit 257.468 Einträgen, `examples/catalog_benchmark`, je zwei Läufe):

  | | 0.4.1 | 0.4.2 |
  | --- | --- | --- |
  | Katalogaufbau | 4,41–4,43 s | 4,92–4,94 s (+11 %) |
  | Datenbank | 139,8 MB | 150,4 MB (+7,6 %) |
  | Katalog öffnen | 1,3–1,4 ms | 1,35–1,5 ms |

  - Der Schlüssel liegt als 16-Byte-BLOB vor; die Text-Variante hätte +14,5 % Speicher bedeutet.
  - Der Aufbau läuft wie bisher im Hintergrund und blockiert keine Wiedergabe.
  - Listen und Detailansichten machen keine zusätzlichen Katalogabfragen. Favoriten- und Fortschrittsstatus kommen aus Hash-Tabellen im Speicher.
  - Änderungen aktualisieren nur den Status sichtbarer Zeilen (RecyclerView-Payload, kein Neuladen von Bildern).
- **Grenzen:** 1.000 Favoriten pro Bereich, 500 Fortschrittseinträge pro Quelle (der älteste fällt heraus), höchstens 50 Verlaufseinträge.

## Upgrade

Schema-2-Kataloge bleiben offline lesbar, haben aber keine Schlüssel. Der bestehende Upgrade-Pfad baut sie beim nächsten Refresh als Schema 3 neu auf. Bis dahin zeigen die beiden Kategorien „Die Bibliothek wird aktualisiert …“. Sterne und Verlauf sind dann deaktiviert, die Wiedergabe funktioniert unverändert.

Eine Identität ändert sich, wenn die Stream-URL, die Zugangsdaten oder bei M3U der Serientitel geändert werden. Betroffene Einträge bleiben als „Derzeit nicht verfügbar“ sichtbar und werden nie anhand gleicher Titel automatisch fremden Inhalten zugeordnet.

## Prüfung

- **Rust:** 7 neue Tests (`tests/user_keys.rs`):
  - Schlüssel bleiben nach Neuimport und Umsortierung gleich, die IDs ändern sich.
  - SD- und 4K-Varianten bleiben getrennt.
  - Serienschlüssel gelten pro Show.
  - Episoden werden auf Serie, Staffel und Episode aufgelöst.
  - Ein anderes Salz ergibt andere Schlüssel.
  - `resolve` ist begrenzt und lehnt veraltete Revisionen ab.
  - Xtream-Episodenschlüssel bleiben über wiederholte Auflösung gleich.
  - Fortsetzen nur bei VOD, nie bei Live; nach dem ersten Poll hat die beobachtete Position Vorrang.
  - In-Memory-Pfad ohne Schlüssel.
- **JVM:** 19 neue Tests.
  - `UserLibraryTest`:
    - 16 Starts ergeben 15 Einträge, der älteste fällt heraus, eine Wiederholung steht vorn ohne Duplikat.
    - Die Ordnung bei gleicher Zeit ist deterministisch.
    - Das Limit ist einstellbar und kürzt sofort.
    - Quellen und Bereiche bleiben getrennt.
    - Favorit, Verlauf und Fortschritt sind unabhängig.
    - Eine Serie ergibt einen Eintrag, der Fortschritt wird pro Episode geführt.
    - Die Abschluss- und Fortsetzungsregeln greifen.
    - Der Fortschritt ist begrenzt.
    - JSON-Roundtrip und tolerantes Laden funktionieren.
  - `WatchTrackerTest`:
    - Erst der tatsächliche Start erzeugt Verlauf, ohne Duplikate bei Retry oder Pause.
    - 10-s-Drosselung; gespeichert wird bei Pause, Freigabe und Ende.
    - Veraltete Tokens werden verworfen.
    - Live speichert nur den Kanal.
    - Episoden aktualisieren den Serieneintrag.
- **Emulator** (`UserLibraryUiTest`, echte Media3-Wiedergabe mit synthetischem Video, echte D-Pad- und Lang-OK-Ereignisse, Neustart mit neuer Activity und erneutem Lesen der Datei):
  - Leeransicht wird angezeigt.
  - Live speichert nur den Kanal.
  - Kanalfavorit per langem OK.
  - Filmdetails zählen nicht als gesehen.
  - Filmfavorit lässt sich setzen.
  - Position wird bei Pause gespeichert.
  - Zwei Episoden ergeben einen Serieneintrag mit S1 · E2.
  - Serienfavorit wird nur als Show gespeichert.
  - Nach dem Neustart sind alle sechs Listen wiederhergestellt.
  - Die Karte zeigt den Fortschritt.
  - „Fortsetzen bei 0:1x“ startet an der gespeicherten Position.
  - Entfernen eines Favoriten lässt Verlauf und Fortschritt intakt.
  - Die Serienübersicht bietet „Fortsetzen · S1 · E2“ an.
- Die vollständige Regression `scripts/build.ps1 -Release -ConnectedTests` (Rust-Format, Clippy, alle Rust-, JVM- und Emulator-Tests, Lint, Debug-, R8-Release- und AAB-Build, Herkunfts- und Paketprüfung) steht im Abschlussbericht.
- Offen: Abnahme auf echter TV-Hardware und mit Screenreader.

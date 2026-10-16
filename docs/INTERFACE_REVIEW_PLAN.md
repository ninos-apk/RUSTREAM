# Interface-Review und Verbesserungsplan für IPTV TV

Aktualisierung: 16. Oktober 2026 · **in Version 0.3.1 umgesetzt**. Die ursprünglichen Befunde und Vorher-Belege unten beziehen sich auf 0.2.1. Die Umsetzung baut auf dem inzwischen vorhandenen Katalogstand 0.3.0 auf. Nachweise, Bedienung und verbleibende Geräteabnahme: [UI-Umsetzungsbericht](UI_IMPLEMENTIERUNG_031.md).

F01–F07 und F09–F11 sind im Anwendungscode bearbeitet; die letzte aktive Quelle (F08) wird bereits durch den beibehaltenen Katalog aus 0.3.0 wiederhergestellt. Die frühere Block-Bewertung beschreibt den damaligen Stand, nicht die aktuelle Version. Eine vollständige Accessibility-Konformität wird damit nicht behauptet.

Auch die Planergänzung ist implementiert und am Emulator geprüft: „Refresh“ steht als Textbutton unter den Statistiken und ist aus dem Video entfernt. Die Steuerung zeigt größere Sprungsymbole und separate −30-s-/+30-s-Beschriftungen. Im Film-/Serien-Vollbild springt ein kurzer Links-/Rechts-Druck 30 Sekunden; Halten beschleunigt zeitabhängig, Loslassen beendet den Ablauf.

## Umfang und Abdeckung

Geprüft wurden App-Start und Quellenladen, die Hauptansicht mit Live/Movies/Series, Kategorien und Suche, Senderauswahl und Nachladen, Videovorschau, Steuerleiste, Vollbild und Zurück-Verhalten sowie Einstellungen, Quellenverwaltung, Importformulare, Bildmodus, Pufferprofile und EPG-Anbindung.

Stack: native Android-Views in Kotlin, RecyclerView, Media3 PlayerView mit eigenem Overlay, Coroutines und Rust/JNI. Gemeinsame Farben und Fokusbehandlung liegen in `TvUi`; Schriftgrößen verwenden Androids sp. Der bestehende dunkle Stil mit Türkis-Akzent und die virtualisierten Listen bleiben die Grundlage. Ein Frameworkwechsel ist für diese Verbesserungen nicht erforderlich.

Gelesene Projektkonventionen: README, UI_REDESIGN, ARCHITECTURE, CONTENT_SECTIONS und DEVICE_ACCEPTANCE. Im Projekt und den geprüften übergeordneten Verzeichnissen wurde keine AGENTS.md gefunden; ebenso keine eigene CONTRIBUTING-, CODING_STANDARDS-, CLAUDE- oder Designsystem-Datei im sichtbaren Projektbestand. Die ausdrücklich dokumentierte kompakte Dichte ist eine Projektentscheidung und allein kein Fehler.

Laufzeitprüfung: vorhandene installierte Release-App 0.2.1, versionCode 5, auf Google-TV-Emulator API 36, 1920 × 1080, 320 dpi. Eine temporäre synthetische Liste enthielt 140 Live-Einträge, einen Film und eine Serienepisode sowie absichtlich lange Namen. Die Quelle wurde über die normale Oberfläche hinzugefügt und anschließend entfernt.

Grenzen: Hauptabläufe wurden zusätzlich am Emulator geprüft; nicht jede Import- oder Netzwerkvariante wurde ausgeführt. Ein echter Fernseher, TalkBack, API 23, 720p/4K, RTL, echte Xtream-Anmeldung und ein erfolgreicher XMLTV-Import wurden in diesem Review nicht verifiziert. Browserregeln wie 320 CSS-Pixel werden hier nicht als Android-TV-Anforderung ausgegeben. WCAG-Prinzipien dienen zur Einordnung; dies ist keine vollständige Konformitätsbescheinigung.

| Domäne | Geprüfte Evidenz | Ergebnis |
| --- | --- | --- |
| Accessibility | PlayerPane, TvUi, MainActivity; D-Pad-Wege, sichtbarer Fokus, UI-Automator-Zustände und Namen | F01, F02, F11; TalkBack nicht verifiziert |
| Layout | MainActivity, ConnectionDetails; Screenshots bei Schriftfaktor 1,0 und 2,0; Listen-Nachladen | F03; weitere Displaygrößen/RTL nicht verifiziert |
| Writing | Sämtliche lokalen UI-Texte in MainActivity, PlayerPane, PlaybackController, ConnectionDetails und Importformularen | F04, F05, F09, F10 |
| Typography | TvUi.text, Listentexte, Suchfelder, Status/EPG und Metriken; lange Namen und Schriftfaktor 2,0 | Schrift-Clipping unter F03 konsolidiert; keine zusätzliche bestätigte Ursache |
| Colors | TvUi-Farben, fokussierte Flächen, ConnectionDetails; sieben berechnete Paare und Abgleich mit Screenshotfarben | Kein Fehler in den gemessenen Paaren; Text über beliebigem Video nicht vollständig verifiziert |
| UI / Motion | PlayerPane-Zustände und Overlay, MainActivity-Start und Quellenauswahl, TvUi-Fokusanimationen | F06, F07, F08; Systemanimationen aus nicht eigens getestet |

Alle sechs zugehörigen `better-*`-Skills wurden gelesen. Die Befunde sind nach Nutzerwirkung und gemeinsamer Ursache zusammengeführt.

## Befunde

HIGH bedeutet ein Bedien- oder Inhaltsproblem, das vor Freigabe behoben werden sollte. MEDIUM beeinträchtigt Verständnis, Effizienz oder Verlässlichkeit. Die Kennungen dienen der Umsetzung und Abnahme.

| Schwere | Domäne | Ort | Vorher | Nachher | Warum |
| --- | --- | --- | --- | --- | --- |
| **HIGH F01** | Accessibility | [PlayerPane.kt:87][pane-focus], [PlayerPane.kt:143][pane-feedback] | Videofokus zeigt nur `flash("OK für Steuerung")`; der Hinweis verschwindet nach 1,5 s. Danach ist im Screenshot trotz fokussiertem Player kein Fokusindikator sichtbar. | In der Vorschau einen dauerhaft sichtbaren Rahmen über der Videosurface zeichnen, solange der Player oder seine Steuerung Fokus besitzt. Im bildschirmfüllenden Modus den Fokus innerhalb der eingeblendeten Steuerung anzeigen. | Fehlender sichtbarer Tastatur-/D-Pad-Fokus, entsprechend WCAG 2.4.7; die nächste OK-Aktion ist nicht erkennbar. Beleg: `video-focus-steady.png` und zugehöriger UI-Baum. |
| **HIGH F02** | Accessibility | [PlayerPane.kt:124][pane-keys], [MainActivity.kt:500][back] | Bei verborgenem Overlay werden Links und Rechts unabhängig von `isFullscreen` abgefangen. Links spult zurück bzw. zeigt eine LIVE-Meldung; Zurück kann in der Übersicht die App schließen. | Vorschau und Vollbild getrennt behandeln: Links aus der Vorschau zur zuletzt fokussierten Senderzeile; Zurück aus Player/Overlay zunächst in die Bibliothek. Seek-Tasten nur im passenden Wiedergabekontext verwenden. | Der direkte räumliche Rückweg ist blockiert; Links hat eine unerwartete Medienwirkung. Der UI-Baum bestätigt vor und nach Links denselben Videofokus. Andere Umwege ersetzen keinen klaren D-Pad-Pfad; Prinzipien 2.1.1/2.4.3. |
| **HIGH F03** | Layout | [MainActivity.kt:138][layout], [MainActivity.kt:157][columns], [ConnectionDetails.kt:18][details-size], [PlayerPane.kt:84][caption-size] | Feste Spaltenbreiten und Textzeilenhöhen treffen auf skalierbare sp-Texte. Bei `font_scale=2.0` werden u. a. Kategorien-/Suchbeschriftungen, Overlay-Beschriftung und Footer abgeschnitten. | Texthaltige Zeilen nach Inhalt mit Mindesthöhe messen; bei großer Schrift breitere bzw. zusammenklappbare Bibliotheksbereiche, mehrzeilige Statusflächen und scrollbare Detail-/Einstellungsinhalte vorsehen. Schrift nicht künstlich verkleinern. | Bestätigter Inhaltsverlust bei 200 Prozent Systemschrift, entsprechend 1.4.4. Belege: `font-200.png`, `font-200-settings.png`; ein Teil des ersten Screenshots zeigt noch einen Dialogübergang, die abgeschnittenen Hauptansichtstexte sind davon unabhängig. |
| **HIGH F04** | Writing | [MainActivity.kt:429][remove-source] | Die Auswahl eines Namens in „Quelle entfernen“ speichert sofort die verkürzte Quellenliste. Es gibt weder Bestätigung noch Rückgängig. | Vor dem Entfernen Quelle und Konsequenz benennen: „Quelle … entfernen? Die gespeicherte Anmeldung wird entfernt.“ Aktionen „Abbrechen“ und „Quelle entfernen“; Abbrechen zuerst fokussieren. Alternativ eine verlässliche Rückgängig-Funktion. | Eine versehentliche Auswahl entfernt gespeicherte Quellendaten/Zugangsdaten. Auch beim Entfernen der Demo-Quelle bestätigt: kein weiterer Bestätigungsschritt. |
| **HIGH F05** | Writing | [MainActivity.kt:285][errors], [PlaybackController.kt:206][player-error] | Einige Fehlertexte enden bei „Keine unterstützten Kanäle gefunden“, „Xtream-Anmeldung fehlgeschlagen“ oder „Format wird von diesem Gerät nicht unterstützt“. Der Importdialog bietet nur „OK“. | Fehler jeweils um einen konkreten nächsten Schritt und passende Aktion ergänzen, z. B. „Zugangsdaten prüfen“, „Andere Quelle wählen“, „Erneut versuchen“. Relevante Eingaben erhalten. | Diese konkreten Textzweige nennen keinen Weg zur Behebung. Bestätigt im Quelltext; die einzelnen Auth-/Codecfehler wurden nicht am Emulator ausgelöst. Der beobachtete generische Startfehler enthält bereits einen Prüfhinweis, aber keine direkte Wiederholungsaktion. |
| **MEDIUM F06** | UI | [MainActivity.kt:103][startup], [MainActivity.kt:299][import], [MainActivity.kt:359][search] | Die vollständige Hauptansicht wird vor dem Quellenladen aufgebaut. Der Import setzt lediglich eine Statuszeile; `search()` startet einen separaten Job. Ein eigener Start-/Bereitschaftszustand fehlt. | Einen expliziten Zustand für Start, Laden, erste Liste bereit, keine Quelle und Fehler einführen. Beim Start einen animierten Ladebildschirm zeigen und erst mit der bedienbaren ersten Ergebnisseite in die Bibliothek wechseln. | Das anfänglich leere Programmfenster wirkt fertig, obwohl die Inhalte fehlen. Das Ende des Importaufrufs ist nicht gleichbedeutend mit einer dargestellten Senderliste. Die fehlende Zustandsstruktur ist im Code bestätigt; lange Importzeiten wurden hier nicht gemessen. |
| **MEDIUM F07** | UI | [MainActivity.kt:90][channel-click], [MainActivity.kt:237][select-channel], [PlaybackController.kt:61][playback-select] | Jede Aktivierung derselben Senderzeile ruft erneut `playback.select` auf. Beim Test wechselte der pausierte aktive Sender wieder zu „Verbinde“; die Übersicht blieb bestehen. | Anderen Sender mit OK in der Vorschau starten; OK auf dem bereits aktiven Sender öffnet Vollbild ohne neue Medienauswahl. Eine gestoppte/beendete Wiedergabe wird zuerst wieder gestartet. | Der gewünschte schnelle Wechsel zum Vollbild fehlt; eine erneute Auswahl erzeugt unnötig einen neuen Wiedergabeaufbau. Beleg: `before-repeat.xml`, `repeat-selected.png`. |
| **MEDIUM F08** | UI | [MainActivity.kt:113][startup-source], [MainActivity.kt:423][choose-source] | Beim Start wird immer `sources.first()` geladen. Die letzte aktive Quelle wird beim Wechsel nicht gespeichert. | Quellen mit stabiler ID verwalten und die zuletzt erfolgreich gewählte Quellen-ID speichern. Bei entfernter/unbrauchbarer Quelle eine nachvollziehbare Auswahl oder Wiederherstellung anbieten. | Nach dem erfolgreichen Demo-Import lud der Neustart wieder die erste, nicht erreichbare Quelle. Der Nutzer landet trotz zuvor funktionierender Auswahl erneut im Fehlerzustand. |
| **MEDIUM F09** | Writing | [MainActivity.kt:141][empty-category], [MainActivity.kt:152][empty-channel], [MainActivity.kt:373][empty-search], [PlayerPane.kt:29][empty-video] | „Keine Einträge gefunden“ nennt weder die wirksame Suche noch einen Ausweg. Ohne Quelle verlangt die Video-Leeransicht trotzdem, links einen Eintrag auszuwählen. | Zwischen „Noch keine Quelle“, „Keine Treffer für …“, leerem Bereich und Ladefehler unterscheiden. Je Zustand eine passende Aktion: „Quelle hinzufügen“, „Suche löschen“, „Alle Kategorien“. | Der Nutzer muss die Ursache der leeren Liste selbst erschließen. Bestätigt an leerer Startansicht, Suchzustand und Quelltext; Belege: `empty.png`, `no-results.png`. |
| **MEDIUM F10** | Writing | [MainActivity.kt:452][form] | Formulare verwenden nur Hints und schließen durch den nativen positiven Dialogbutton vor Abschluss der asynchronen Prüfung. Feldbezogene Validierung ist nicht vorgesehen. | Sichtbare Feldbezeichnungen und konkrete Beispiele ergänzen; bei Absenden im Dialog validieren, Fehler am Feld zeigen und Eingaben erhalten. Erst nach erfolgreichem Import schließen. | Korrekturen erfordern unnötige erneute Eingabe mit der TV-Tastatur. Der Schließ-/Submit-Pfad ist im Code bestätigt; alle Formularfehler wurden nicht praktisch durchgespielt. |
| **MEDIUM F11** | Accessibility | [MainActivity.kt:344][tab-state], [MainActivity.kt:552][row-state] | Aktive Tabs/Zeilen setzen `isActivated`; Zeilenbeschreibungen enthalten nur den Namen. Im UI-Baum melden aktive Bereiche weiterhin `selected=false`/`checked=false`. | Auswahlzustand über geeignete native Selected-/Checked-Semantik bzw. einen Accessibility-Delegate ausgeben. Laufenden Sender separat als „Wird wiedergegeben“ kennzeichnen; Fokus und Auswahl getrennt halten. | Name und Aktion sind vorhanden, der aktuelle Zustand ist über die untersuchte Accessibility-Schnittstelle unvollständig. Entspricht dem Prinzip 4.1.2. Die tatsächliche TalkBack-Ausgabe bleibt zu prüfen; deshalb kein behaupteter TalkBack-Blocker. |

## Was bereits funktioniert

- Die vorhandenen Hauptfarben benötigen in den gemessenen Kombinationen keine Neugestaltung. Weiß auf Panel erreicht **17,63:1**, Sekundärtext auf Panel **6,40:1**, Weiß auf Fokusfläche **11,44:1**. Der Akzent erreicht zur Fokusfläche **7,06:1** und zum Panel **10,89:1**. Die zugrunde liegenden Flächenfarben wurden in den Screenshots wiedergefunden. Messdaten: `artifacts/ui-review/contrast.json`.
- Listen-Fokus und laufender Sender sind optisch bereits getrennt: Rahmen auf dem fokussierten Eintrag, Dreieck am aktiven Eintrag. Dieses Prinzip sollte auch für Video und Accessibility gelten.
- Das Nachladen über die erste 64er-Grenze funktionierte; die Fernbedienung erreichte „Demo Sender 068“. Ein genereller Umbau der RecyclerView ist daraus nicht begründet.
- Der bestehende Vollbild-Schalter zeigt ein randloses Bild. Die Implementierung versetzt denselben PlayerPane und behält damit die bestehende Playerinstanz beim Layoutwechsel.
- Medienaktionen, deutsche Beschriftungen und die bestehenden Bild-/Pufferprofile können weiterverwendet werden. „Movies“/„Series“ sind eine vorhandene bewusste Benennung und werden hier nicht allein wegen der Sprache als Fehler gewertet.

## Zielablauf für den App-Start

1. Sofort eine ruhige, dunkle Startfläche mit dem vorhandenen IPTV-Schriftzug und Türkis-Akzent anzeigen.
2. Das Logo einmal dezent einblenden, Vorschlag etwa 250–300 ms. Danach nur eine kleine funktionale Ladeanzeige; keine dauernde große Zoom-/Pulsanimation.
3. Ehrliche Phasentexte anzeigen, soweit die jeweilige Phase tatsächlich bekannt ist: „Quelle wird geladen …“, „Sender werden vorbereitet …“. Prozentwerte nur bei messbarem Fortschritt anzeigen.
4. Sobald Kategorien und erste Senderseite dargestellt und per D-Pad erreichbar sind, zur Hauptansicht wechseln. Die Animation erzeugt keine künstliche Mindestwartezeit.
5. EPG anschließend unabhängig im Hintergrund laden. Ein EPG-Fehler darf eine bereits benutzbare Senderliste nicht blockieren.
6. Ohne gespeicherte Quelle direkt einen Einrichtungszustand mit „Quelle hinzufügen“ anbieten. Bei Ladefehlern auf der Startfläche „Erneut versuchen“ und „Quelle wählen“ anbieten.
7. Androids deaktivierte Systemanimationen berücksichtigen und einen statischen Zustand mit Statusbeschreibung bereitstellen. Fokusänderungen müssen auch ohne Bewegung erkennbar sein. Ladevorgänge müssen nach Home/Zurück/Activity-Ende geordnet abbrechen oder veraltete Ergebnisse verwerfen.

Technisch gehört das lange Laden in die Activity-/App-Oberfläche. Eine mögliche Android-System-Splash-Anbindung müsste für API 23+ und Android 12+ separat geprüft werden; der System-Splash soll keine lange Netzwerkabfrage festhalten. Der Startzustand endet nach dem bestätigten ersten Listenlayout, nicht schon nach dem Anstoßen von `search()`.

## Verbindliche Fernbedienungsregeln

| Situation | Taste | Zielverhalten |
| --- | --- | --- |
| Bibliothek, anderer Sender fokussiert | OK | Sender in der Vorschau starten; Fokus bleibt auf der Senderzeile |
| Bibliothek, aktiver Sender fokussiert | OK | Vollbild öffnen; Player, Stream und VOD-Position erhalten |
| Sender nur ausgewählt, Wiedergabe gestoppt/beendet | OK | Wiedergabe wieder starten; keine leere Vollbildansicht |
| Senderliste | Rechts | Videovorschau fokussieren; deutlich sichtbarer Fokusrahmen |
| Videovorschau | Links | Zur zuletzt fokussierten Senderzeile zurückkehren; keine Seek-Aktion |
| Videovorschau mit Wiedergabe | OK | Vollbild öffnen; „OK: Vollbild“ bleibt als statischer Hinweis während des Fokus sichtbar |
| Videovorschau | Runter | Steuerung öffnen; eine verfügbare Hauptaktion fokussieren |
| Vollbild, Steuerung verborgen | OK / Runter | Steuerung öffnen |
| Vollbild eines seekbaren Films oder einer Serienepisode, Wiedergabefläche fokussiert | Links / Rechts kurz drücken | Verbindlich genau 30 s zurück / vor springen; an Anfang und Ende begrenzen |
| Vollbild eines seekbaren Films oder einer Serienepisode, Wiedergabefläche fokussiert | Links / Rechts gedrückt halten | Nach dem ersten 30-s-Sprung fortlaufend in derselben Richtung spulen; Geschwindigkeit mit der Haltedauer progressiv erhöhen und beim Loslassen sofort stoppen |
| Live ohne Timeshift / nicht seekbares Medium | Links / Rechts | Keine Seek-Aktion anbieten; die Film-/Serien-Spulbelegung greift hier nicht |
| Steuerung sichtbar | Links / Rechts | Zwischen verfügbaren Aktionen navigieren |
| Vorschau-Steuerung am linken Rand | Links | Zur Bibliothek zurückkehren |
| Steuerung sichtbar | Zurück | Steuerung schließen und nachvollziehbaren Fokus wiederherstellen |
| Vollbild, Steuerung verborgen | Zurück | Bibliothek mit vorheriger Senderzeile, Suche und Scrollposition wiederherstellen |
| Videovorschau, Steuerung verborgen | Zurück | Zur Senderliste zurückkehren |
| Bibliothek | Zurück | App gemäß bestehender Plattformnavigation verlassen; ein optionaler zusätzlicher Schritt zum Filterlöschen ist vor Umsetzung ausdrücklich festzulegen |
| Unterer Statistikbereich | „Refresh“ mit D-Pad fokussieren und OK drücken | Aktuell ausgewählte Wiedergabe neu verbinden; keine Playlist neu importieren |

Fokusrahmen als separate Ebene **über** der Surface zeichnen; ein Hintergrund hinter dem Video kann verdeckt werden. Vorschlag: 2–3 dp kräftiger Rahmen mit dunkler Einfassung, sodass er auf hellen und dunklen Videos erkennbar bleibt. Die bestehenden Farben zuerst wiederverwenden und die angrenzenden Paare messen. Die gesamte Videofläche nicht bei jedem D-Pad-Wechsel skalieren.

## Ergänzte Anforderungen an Refresh und Spulen

### Refresh im Statistikbereich

- „Neu verbinden“ aus der Videosteuerleiste entfernen, sowohl in der Vorschau als auch im Vollbild.
- Unterhalb der Statistikwerte einen normalen, deutlich erkennbaren Textbutton mit der sichtbaren Beschriftung **Refresh** platzieren. Er erhält denselben statischen Fokusindikator wie die übrigen TV-Buttons und ist aus der Bibliothek und Videovorschau per D-Pad erreichbar.
- Der Button übernimmt die bestehende Neuverbindungsfunktion für den ausgewählten Sender, Film oder die Serienepisode. Ohne ausgewählten Inhalt ist er deaktiviert; bei einem Verbindungsfehler bleibt eine erneute Verbindung möglich. Mehrfachaktivierungen dürfen keine parallelen Neuverbindungsaufträge erzeugen.
- Im Vollbild bleibt die Aktion außerhalb des Bildes: Zurück führt in die Übersicht zum Statistikbereich. Dort bleibt „Refresh“ sichtbar, auch wenn technische Detailwerte später eingeklappt werden.

### Klarere 30-Sekunden-Buttons im Video

- Zwei einheitliche, gut erkennbare Richtungssymbole verwenden: ein Rücksprungpfeil und ein Vorsprungpfeil mit deutlich lesbarer **30**. Die Zahl darf nicht als winziges Detail im Symbol untergehen.
- Die Bedeutung zusätzlich mit **„−30 s“** und **„+30 s“** unmittelbar an den jeweiligen Buttons sichtbar machen. Fokus, deaktivierter Zustand und ausreichender Kontrast bleiben klar unterscheidbar.
- Zugängliche Namen lauten „30 Sekunden zurück“ und „30 Sekunden vor“. Einmaliges OK auf dem jeweiligen Button führt genau einen 30-s-Sprung aus. Nicht seekbare Inhalte erhalten keine aktiven Sprungaktionen.

### Beschleunigtes Spulen per Fernbedienung

- Diese Belegung gilt für seekbare Filme und Serienepisoden im Vollbild, wenn die Wiedergabefläche fokussiert ist. Die sichtbare Buttonleiste verwendet Links/Rechts weiterhin zur Auswahl ihrer Aktionen. Während des Spulens darf eine Fortschrittsanzeige eingeblendet werden, ohne den Fokus von der Wiedergabefläche in die Buttonleiste zu verschieben.
- Ein kurzer Druck nach rechts springt sofort **30 Sekunden vor**, nach links **30 Sekunden zurück**. Loslassen erzeugt keinen zusätzlichen Sprung.
- Bei weiter gedrückter Taste beginnt nach einer kurzen Halteschwelle das fortlaufende Spulen. Als Startwert für die Umsetzung sind etwa **400 ms** vorgesehen. Die Geschwindigkeit steigt mit der gesamten Haltedauer kontinuierlich bis zu einer sinnvollen Obergrenze; die genaue Beschleunigung wird bei der TV-Abnahme abgestimmt.
- Zielzeit, Richtung und steigende Spulgeschwindigkeit verständlich anzeigen. Während des Haltens läuft die Zielposition sichtbar weiter. Seek-Aufträge gebündelt bzw. begrenzt ausführen, damit Decoder und Netzwerk nicht für jedes Tastenwiederholungsereignis einen neuen Auftrag abarbeiten müssen.
- Pro gehaltene Taste genau einen Spulablauf führen. Android-Tastenwiederholungen dürfen weder zusätzliche unabhängige Timer starten noch neben dem beschleunigten Spulen weitere pauschale 30-s-Sprünge auslösen.
- Bei Loslassen sofort anhalten und die letzte Zielposition übernehmen. Richtungswechsel setzen die Beschleunigung zurück. Anfang/Ende begrenzen die Zielposition; Pause/Wiedergabe bleiben erhalten. Bei Zurück, Fokusverlust, Kanal-/Episodenwechsel, Verlassen des Vollbilds oder Home den Spulablauf zuverlässig beenden und ausstehende Aufträge verwerfen.

## Umsetzungsreihenfolge und Abnahme

### Paket 1: Bedienung und Fokus

Umfang: F01, F02, F07, F11 sowie die ergänzte Film-/Serien-Spulbelegung. MainActivity, PlayerPane, PlaybackController und gemeinsame Fokus-/Accessibility-Helfer ändern. Zwischen aktiver Auswahl, fokussiertem Eintrag, Wiedergabephase und Vollbild unterscheiden. Wiederherstellung über stabile Sender-ID und Playlist-Revision planen; ein gespeicherter View-Verweis allein reicht bei recycelten oder gefilterten Zeilen nicht.

Abnahme:

- Jeder D-Pad-Stopp hat einen statischen sichtbaren Fokusindikator. In der Vorschau bleibt der Videorahmen auch nach zehn Sekunden sichtbar.
- Rechts in der Senderliste und Links in der Vorschau bilden einen reversiblen Weg ohne Wiedergabeneustart oder unbeabsichtigtes Spulen.
- Erneutes OK auf dem aktiven Sender wechselt ins Vollbild. Die Playerinstanz und die Verbindungs-Versuch-ID ändern sich dabei nicht; pausierte Wiedergabe bleibt pausiert.
- Das Verhalten funktioniert auch bei nicht seekbarem Live-TV, Fehler, gestoppter Wiedergabe und schnellem wiederholtem OK. Key-Repeat erzeugt keine mehrfachen Umschaltungen.
- Zurück aus dem Vollbild stellt die vorherige Zeile und Scrollposition wieder her, auch über die Grenzen 64 und 128 hinweg. Bei ungültiger ID gibt es einen definierten Ersatzfokus.
- Bei seekbaren Filmen und Serien im Vollbild führt ein kurzer Links-/Rechts-Druck genau einen Sprung um −30/+30 s aus, soweit die Mediengrenzen das erlauben. Loslassen fügt keinen zweiten Sprung hinzu.
- Gedrückthalten spult fortlaufend mit wachsender Geschwindigkeit. Zielposition und Richtung bleiben sichtbar; beim Loslassen endet das Spulen sofort. Richtungswechsel, Mediengrenzen, Pause und unterschiedliche Fernbedienungs-Wiederholungsraten sind geprüft.
- Fokusverlust, Zurück, Home und Inhaltswechsel beenden jeden Spulablauf; spätere Timer oder Seek-Aufträge verändern den neuen Inhalt nicht. In der Vorschau bleibt Links der Rückweg zur Senderliste.

### Paket 2: Start und Quellenzustände

Umfang: F06, F08 und der Startanteil von F09. Einen klaren Startzustand sowie die letzte aktive Quellen-ID ergänzen. SourceLoader kann echte Phasen liefern; vollständiges Downloadprozent nur bei verlässlicher Länge anbieten. Die begrenzten Imports und vorhandenen Revisionsprüfungen beibehalten.

Abnahme:

- Schneller, langsamer und fehlgeschlagener Import sowie Erststart ohne Quelle haben jeweils die passende Ansicht.
- Die Hauptansicht erscheint nach der ersten tatsächlich dargestellten Senderseite; ein langsames EPG verzögert sie nicht.
- Start ohne Animation bleibt verständlich. Ein schneller Import wird nicht für die Animation künstlich verzögert.
- Wiederholen/Quelle wechseln führt mit der Fernbedienung direkt weiter. Alte Imports überschreiben keinen neueren Zustand.
- Nach einem Quellenwechsel startet die App beim nächsten Öffnen mit der zuletzt erfolgreich aktiven Quelle.

### Paket 3: Lesbarkeit, Leeransichten und sichere Dialoge

Umfang: F03, F04, F05, F09, F10. Zuerst gemeinsame Größen-/Layoutannahmen korrigieren, dann einzelne Texte. Die kompakte Normalansicht beibehalten, solange sie auf echter Hardware aus üblichem Sitzabstand lesbar ist. Ein optionaler Modus „Größere Schrift“ ist eine Ergänzung, kein Ersatz für funktionierende Systemschrift.

Abnahme:

- Bei 100, 150 und 200 Prozent Systemschrift bleiben Labels und Aktionen zugänglich; Dialoginhalte scrollen bei Bedarf.
- Lange Sendernamen sind per Fokus lesbar. Lange Status- und EPG-Texte erhalten eine per Fernbedienung erreichbare Detailansicht, sofern sie abgeschnitten werden.
- Leere Suche zeigt die wirksamen Filter und eine Aktion zum Zurücksetzen. Ohne Quelle wird direkt eine Quelle hinzugefügt.
- Entfernen einer Quelle ist geschützt. Fehler nennen die Korrektur, lassen Eingaben stehen und fokussieren die richtige Aktion bzw. das fehlerhafte Feld.
- TalkBack meldet Name, Rolle und Zustand der Controls sowie den aktiven Bereich und laufenden Sender sinnvoll.

### Paket 4: Wiedergabeinformationen und Feinschliff

Wiedergabestatus soll auch im Vollbild erreichbar sein: Sendername, Jetzt-/Nächste-Sendung, Verbinden/Puffern und dauerhafte Fehler gehören in den Player-Kontext. Die aktuelle Implementierung schreibt wesentliche Statusinformationen in `chrome`, das im Vollbild verborgen wird. Das ist ein im Code erkannter Prüfpunkt; eine echte Netzunterbrechung im Vollbild wurde in diesem Review noch nicht verifiziert.

Im unteren Statistikbereich den normalen Textbutton **Refresh** ergänzen und den bisherigen Neuverbinden-Button vollständig aus der Videosteuerung entfernen. Dafür MainActivity, ConnectionDetails und PlayerPane anpassen. Die technischen Verbindungswerte können unter einer direkt erreichbaren Detailaktion zusammengefasst werden, falls die TV-Abnahme bestätigt, dass sie im Alltag zu viel Platz beanspruchen; Refresh bleibt dabei direkt sichtbar. Den bestehenden Urheberhinweis erhalten. VOD bietet Zeit, klare −30-s-/+30-s-Buttons und das beschleunigte Spulen aus Paket 1; Live zeigt LIVE und nur tatsächlich unterstützte Aktionen. Häufige Fokuswechsel bleiben sofort oder sehr kurz; ausdrucksvollere Animation auf Start und seltene Ansichtswechsel begrenzen.

Zusätzliche Abnahme:

- „Refresh“ steht als normaler Textbutton unter den Statistiken, besitzt sichtbaren Fokus und verbindet ausschließlich den ausgewählten Inhalt neu. Ohne Auswahl ist er deaktiviert; aus einem Verbindungsfehler kann er erneut starten.
- In Vorschau und Vollbild enthält die Videosteuerung keinen Neuverbinden-/Refresh-Button mehr. Nach dessen Entfernung hat die Button-Navigation keine toten Fokusverweise.
- Rück-/Vorsprung sind durch Richtungssymbol, lesbare 30 und „−30 s“/„+30 s“ verständlich. Die Beschriftungen bleiben bei vergrößerter Systemschrift zugänglich.

Erst danach weitere Produktfunktionen priorisieren: Favoriten, zuletzt gesehen, Sendernummern, Audio-/Untertitelwahl oder ein größeres EPG. Diese sind Erweiterungen und keine Voraussetzung, um die bestätigten Fokus- und Startprobleme zu lösen.

## Verifikation

| Prüfung | Befehl / Interaktion | Ergebnis |
| --- | --- | --- |
| Installierter Stand | `adb -s emulator-5554 shell dumpsys package de.iptv.tv` | Version 0.2.1, versionCode 5 |
| Startfehler | App öffnen mit nicht erreichbarer gespeicherter Quelle, Fehler schließen | Generischer Fehlerdialog und danach leere Bibliothek beobachtet |
| Demo-Import | Einstellungen → URL hinzufügen → lokale synthetische Liste | 142 Einträge, davon Live 140, Movies 1, Series 1 |
| Aktiven Sender erneut auswählen | Aktiven pausierten Sender fokussieren, OK | Erneuter Verbindungsaufbau, kein Vollbild |
| Videofokus | Rechts aus der Senderliste, Hinweis abwarten | Videofokus im UI-Baum vorhanden, statischer Indikator fehlt |
| Links aus Vorschau | Verborgene Steuerung, Links drücken | Videofokus bleibt; Quelle bestätigt Seek-Verarbeitung |
| Vorhandener Vollbild-Schalter | Steuerung öffnen, Vollbild fokussieren, OK, danach Zurück | Randloses Bild und Rückkehr in Übersicht beobachtet |
| Nachladen | Mit D-Pad über Sender 064 hinaus | Sender 068 erreichbar, weiterer Inhalt nachgeladen |
| Bereiche und leere Suche | Movies/Series wählen; Suche `NoMatchReview` | Bereichsinhalte und fehlende Suchtreffer visuell geprüft |
| Systemschrift | `adb ... shell settings put system font_scale 2.0`, Screenshots, Wiederherstellung auf `1.0` | Clipping beobachtet; ursprünglicher Wert wiederhergestellt |
| Kontraste | sRGB-Luminanzberechnung nach WCAG aus Quellfarben; Flächenfarben in Screenshots geprüft | Sieben untersuchte Paare bestanden; siehe contrast.json |
| JVM und Lint | `. ./scripts/env.ps1` und `./gradlew.bat testDebugUnitTest lintDebug --offline --console=plain -x buildRust` | BUILD SUCCESSFUL; JVM-Aufgabe UP-TO-DATE mit 7 Tests, 0 Fehlern; Lint neu ausgeführt: 0 Errors, 39 Warnings |
| Bereinigung | Quellen verwalten → Entfernen → ausschließlich UIReviewDemo; Quellenliste erneut prüfen | Demo-Quelle entfernt, Demo-Server und Portweiterleitung beendet; Systemschrift 1,0 |

Einige UI-Automator-Dumps schlugen während Wiedergabe/Laufschrift mit „could not get idle state“ fehl. Dabei stehengebliebene XML-Dateien wurden **nicht** als Nachweis des neuen Zustands gewertet. Unter anderem sind `movies.xml`, `movies-current.xml`, `buffer.xml`, `xtream.xml` und `epg.xml` keine belastbaren Nachweise der jeweils beabsichtigten Aktion. Die Puffer-/Xtream-/EPG-Dialoge sind deshalb hier quelltextlich geprüft; der Bildformatdialog wurde zusätzlich erfolgreich geöffnet. Für eine vollständige Geräteabnahme Aktionen einzeln mit Zustandsbestätigung ausführen.

Nicht verifiziert: physische TV-Bedienung aus Sitzabstand, TalkBack, Animationen ausgeschaltet, RTL, weitere Auflösungen und Android-Versionen, echte Anbieter-/Codecfehler, langer EPG-Text und Netzunterbrechung im Vollbild. Diese Punkte sind Teil der geplanten Abnahme. Neue Instrumentierungstests wurden für dieses reine Review nicht geschrieben.

Die automatische Sicherheitsprüfung lehnte einen vorbereitenden Versuch mit erweitertem Emulatorzugriff und Sicherung einer privaten Quelldatei ab. Dieser Zugriff wurde nicht ausgeführt oder umgangen. Die weitere Prüfung und Bereinigung erfolgten über die normale App-Oberfläche mit synthetischen Inhalten.

## Quellen und Nachweise

- [Android TV: Fokussystem](https://developer.android.com/design/ui/tv/guides/styles/focus-system): Fokusindikatoren für räumliche Fernbedienungsnavigation; diese Prinzipien unterstützen Paket 1.
- [Android TV: Navigation](https://developer.android.com/training/tv/get-started/navigation): klare D-Pad-Wege und kontrolliertes Zurück-Verhalten; Grundlage der vorgeschlagenen Navigationstabelle.
- [Android TV: Typografie](https://developer.android.com/design/ui/tv/guides/styles/typography): Lesbarkeit auf Abstand; die konkreten Layoutfehler dieses Reviews stammen aus der eigenen Emulatorprüfung.
- [Android ValueAnimator](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled()): systemweit deaktivierte Animationen berücksichtigen; bei API-übergreifender Umsetzung die Verfügbarkeit prüfen.

Screenshots, UI-Bäume, Demo-Liste und Prüfprotokoll liegen unter `artifacts/ui-review/`. Die gespeicherten Ansichten zeigen synthetische Medien; fehlerhafte/alte UI-Dumps sind oben ausdrücklich gekennzeichnet.

## Urteil

**Block.** F01–F05 sollten vor einer UI-Freigabe behoben werden. Danach folgen der animierte, zustandsbasierte Start und die Verbesserungen der alltäglichen TV-Bedienung. Die vorhandene Oberfläche bietet eine brauchbare Grundlage; der größte Nutzen entsteht durch klare Zustände und verlässliche Navigation.

[pane-focus]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/PlayerPane.kt:87>
[pane-feedback]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/PlayerPane.kt:143>
[pane-keys]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/PlayerPane.kt:124>
[caption-size]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/PlayerPane.kt:84>
[empty-video]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/PlayerPane.kt:29>
[layout]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:138>
[columns]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:157>
[details-size]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/ConnectionDetails.kt:18>
[back]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:500>
[remove-source]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:429>
[errors]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:285>
[player-error]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/PlaybackController.kt:206>
[startup]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:103>
[import]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:299>
[search]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:359>
[channel-click]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:90>
[select-channel]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:237>
[playback-select]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/PlaybackController.kt:61>
[startup-source]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:113>
[choose-source]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:423>
[empty-category]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:141>
[empty-channel]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:152>
[empty-search]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:373>
[form]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:452>
[tab-state]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:344>
[row-state]: <C:/Users/ninos/Documents/ChatGPT/IPTV Player Android TV/app/src/main/java/de/iptv/tv/MainActivity.kt:552>


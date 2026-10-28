# Datum und Wetter in der Kopfzeile

Reihenfolge: `28.Okt  [Wettersymbol]  12 °C  21:45:08`.

Das Datum verwendet die Gerätezeit und aktualisiert sich mit Androids TextClock auch bei Datums- oder Zeitzonenwechsel. Der abschließende Punkt einer Monatsabkürzung entfällt. Die vorhandene Uhr bleibt erhalten.

Die Wettersymbole sind kleine, skalierbare Canvas-Konturen im Stil der TV-Steuerung: Sonne, klare Nacht, teilweise bewölkter Tag/Nacht, Wolken, Nebel, Regen, gefrierender Regen, Schnee und Gewitter. Farbe und Linienstärke passen zur bestehenden Oberfläche. Die Anzeige fügt keinen Fokuspunkt für die Fernbedienung hinzu. Wetterlage, Ort und Temperatur haben eine gemeinsame Screenreader-Beschreibung; Aktualisierungen werden nicht automatisch vorgelesen.

## Automatische Wetterdaten

Der Ort wird automatisch anhand der Internetverbindung bestimmt. Ohne Daten erscheint `— °C` mit einem neutralen Strich als Symbol; es werden keine Beispielwerte als echtes Wetter angezeigt.

Die vom Nutzer ausdrücklich freigegebenen Dienste und Datenflüsse sind:

- `https://ipwho.is/`: Der Dienst sieht die öffentliche IP-Adresse des Fernsehers und liefert daraus die ungefähren Koordinaten und den Ortsnamen.
- `https://api.open-meteo.com/v1/forecast`: Die Wetterabfrage übermittelt die ermittelten Koordinaten und die gewünschten Wetterfelder. Auch dieser Dienst sieht die öffentliche IP-Adresse der Verbindung.

Dokumentation: [IP-Geolokalisierung](https://ipwhois.io/documentation), [Open-Meteo](https://open-meteo.com/en/docs). Die App fordert ausschließlich Ortsname, Koordinaten, Temperatur, Wettercode und Tag-/Nachtstatus an. Playlist-Adressen und Zugangsdaten werden nicht übermittelt. Die Ortsbestimmung erfolgt alle 15 Minuten erneut, sodass ein Wechsel der Internetverbindung berücksichtigt wird. VPNs können den ungefähren Ort verändern.

Die Aktualisierungslogik lädt bei aktiver Activity alle 15 Minuten, versucht es nach Fehlern nach 5 Minuten erneut und entfernt Werte, die älter als 45 Minuten sind. Beim Verlassen der App werden auch laufende Netzabfragen abgebrochen. Datum und Uhr bleiben unabhängig davon nutzbar. Die Antwortgröße ist auf 64 KiB begrenzt; Weiterleitungen werden nicht verfolgt.

Wetterdaten: [Open-Meteo](https://open-meteo.com/), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Temperaturwerte werden für die Anzeige auf ganze Grad gerundet. Ein Quellenhinweis steht auch unter „Über die App“.

## Prüfung

Unit-Tests prüfen die dokumentierten WMO-Wettercodes, Nachtanzeige, Celsius-Rundung, ungültige Antworten und das Verfallen alter Werte. Lokale HTTP-Tests prüfen die verkettete Standort-/Wetterabfrage, fehlgeschlagene Ortsbestimmung, Fehlerantworten, Größenlimit, Weiterleitungen und Abbruch laufender Anfragen. Build und Android-Lint werden ebenfalls ausgeführt. Die unveränderten Rust-Bibliotheken werden beim Android-Build wiederverwendet.

Ergebnis: 117 Unit-Tests erfolgreich, Android-Lint und APK-Build erfolgreich, ZIP-/16-KiB-ELF-Paketprüfung für alle drei ABIs erfolgreich. Version 0.5.12 (Build-Code 23) ist im laufenden TV-Emulator installiert.

UI-Polish nach `better-ui`: Keine neue Animation. Bei der ersten Sichtprüfung auf dem TV-Emulator wurde eine Überlappung der scrollbaren Tabs mit dem Datum festgestellt. Der Tab-Viewport wird jetzt unabhängig vom übrigen Toolbar-Fokus begrenzt. Die abschließende Sichtprüfung bei 1920 × 1080 mit großen Schriftwerten bestätigt die getrennte Anzeige von Tabs, Datum, Wetter und Uhr. Der Live-Abruf zeigt ein Nebelsymbol mit 9 °C. No actionable UI-polish findings in den geprüften Codezuständen und der sichtbaren Live-Anzeige. Not verified: manuelle Sichtprüfung jedes anderen Wettersymbols und eines realen Tag-/Nachtwechsels. Approve für den geprüften Umfang.

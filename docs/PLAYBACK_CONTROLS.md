# Wiedergabesteuerung und VOD-Nachladen (23. Oktober 2026)

## Zum Anfang und vorherige Episode

Der linke Navigationsbutton springt beim ersten Druck immer auf 00:00. Bei Serien
öffnet ein zweiter eigenständiger Druck innerhalb von einschließlich 10 Sekunden
die vorherige Episode der aktuellen Staffel. Nach Ablauf beginnt ein neues
Zeitfenster. Gedrückthalten von OK löst keinen Episodenwechsel aus. Eine neue
Wiedergabeauswahl und Stop setzen das Zeitfenster zurück.

Auch die erste Episode besitzt einen aktiven Anfangsbutton. Bei Filmen ist der
Button ebenfalls sichtbar und springt bei jedem Druck zum Anfang. Der nächste
Episodenbutton bleibt ausschließlich für Serien sichtbar. Am natürlichen Ende
startet der Anfangsbutton eine neue Wiedergabe ohne gespeicherte Fortsetzungsposition.
Bei normalem Zurückspringen während der Wiedergabe bleibt die Nutzerpause bestehen.

Die Steuerung folgt weiterhin dem abgespielten Inhalt, wenn man währenddessen
andere Filme oder Serien in der Bibliothek durchsucht. Die Texte gibt es auf
Deutsch und Englisch; ein kurzer Hinweis erklärt das zweite Drücken.

## Beschleunigtes Spulen

Die vorhandene zeitbasierte `SeekHold`-Kurve gilt auch für OK/Enter auf den
sichtbaren ±30-Sekunden-Buttons in Vorschau und Vollbild, für deren Touchbedienung
und für die separaten Vorlauf-/Rücklauftasten der Fernbedienung. Die bisherigen
Links-/Rechts-Tasten im Vollbild verwenden dieselbe Kurve.

- Kurzer Druck: einmalig 30 Sekunden, an Anfang und Ende begrenzt.
- Ab 400 ms Halten: 30 Sekunden Medienzeit pro Sekunde, Beschleunigung um
  weitere 20 Sekunden pro Sekunde je zusätzlicher Haltedauer-Sekunde.
- Obergrenze: 180 Sekunden Medienzeit pro Sekunde; eine Stunde ist nach etwa
  24 Sekunden Halten erreichbar.
- Aktualisierung alle 160 ms, abhängig von verstrichener Zeit statt der
  Wiederholrate der Fernbedienung. Anzeige von Zielposition und Geschwindigkeit.
- Loslassen, Fokusverlust, Fensterwechsel, Inhaltswechsel oder Stop beendet
  die Beschleunigung. Die Bedienelemente werden während des Haltens nicht
  automatisch ausgeblendet. Ein neuer Druck beginnt mit der Grundgeschwindigkeit.

## Nachladen unter 60 Sekunden

Für Filme und Serien bleiben 60 Sekunden Mindestreserve und 120 Sekunden
Zielreserve eingestellt. Media3 priorisiert bei VOD nun unterhalb der
Mindestreserve die Zeit vor dem 120-MB-Byteziel. Bei hoher Bitrate kann der
Speicherbedarf dadurch über 120 MB liegen. Ab 60 Sekunden kann das Byteziel
weiterhin das Vorausladen begrenzen; 120 Sekunden sind das Zeitmaximum.
Live-Pufferprofile behalten ihr bisheriges Verhalten.

Ein `VodRefillWatchdog` prüft im bestehenden Sekundentakt die Reserve, die insgesamt
empfangenen Netzwerkbytes und das Ende des geladenen Bereichs. Unter 60 Sekunden
ohne neue Daten oder Fortschritt im geladenen Bereich startet er nach 10 Sekunden
eine Wiederherstellung, auch wenn das Video aus der Reserve noch weiterläuft.
Aktive Downloads mit Fortschritt dürfen weiterladen.

Die Wiederherstellung verwendet den vorhandenen Rust-Recovery-Pfad, seine
gespeicherte Position und seinen wachsenden Retry-Abstand bis 20 Sekunden
(zuzüglich bestehendem Jitter). Es gibt keinen zweiten parallelen Retry-Job.
Weitere Stillstände werden erneut erkannt. Pause, Hintergrund, Stop, Live-TV,
Backoff und terminale Fehler lösen keine zusätzlichen VOD-Retries aus. Ein bis
auf 500 ms vollständig geladenes Medienende benötigt keine 60 Sekunden Reserve.
Spulen, die Prüfung eines pausierten Players und das Freigeben eines Players setzen die Überwachung zurück.

## Prüfungen

Lokal erfolgreich am 23. Oktober 2026: 102 JVM-Unit-Tests, fünf gezielte
Emulatorprüfungen (API 36), `lintDebug`, `assembleDebug` und `assembleDebugAndroidTest`.
Die Test-APK heißt `artifacts/RUSTREAM-IPTV-player-controls-debug.apk`.

Unit-Tests decken das 10-Sekunden-Fenster, dessen Ablauf und Rücksetzung, Filme,
erste Episoden, Spulkurve und Grenzen sowie Nachladeentscheidungen bei niedrigem
Puffer, Datenfortschritt, Pause, unbekannter Laufzeit und Medienende ab. Der
Media3-LoadControl-Test prüft das Nachladen unter 60 Sekunden bei erreichtem Byteziel.

Reproduzierbarer Emulatorlauf:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-playback-controls.ps1
```

Der Lauf installiert App- und Test-APK ausschließlich auf einem Emulator und
führt fünf Tests aus: Serien-/Filmnavigation bei einfacher und doppelter Schrift,
gehaltene Buttons und Medientasten, Live-Profilpersistenz, VOD-Reserve und
Wiederherstellung sowie die bisherige Links-/Rechts-Steuerung im Vollbild.
Ein synthetisches Fünf-Minuten-Video und ein lokaler Server simulieren einen
offenen, nicht mehr liefernden Download mit weniger als 60 Sekunden Reserve.
Der Test prüft den Playerwechsel und das Fortsetzen an der bisherigen Position.
Ergebnisse stehen unter `artifacts/playback-controls/instrumentation.txt`.

Die Tests prüfen lokale Quellen und den TV-Emulator; Anbieter- und Netzbedingungen
auf einem echten Fernseher können davon abweichen.

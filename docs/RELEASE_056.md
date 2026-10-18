# Puffer und Diagnosebericht 0.5.6

18. Oktober 2026 · versionName `0.5.6` · versionCode `17` · Tag `v0.5.6`.

## Wiedergabepuffer

Filme und Serien laden bis zum Zeitziel von 120 Sekunden voraus und beginnen unter 60 Sekunden wieder nachzuladen. Das Datenbudget beträgt 120.000.000 Bytes (120 MB, etwa 114,4 MiB). Die vorhandene Startschwelle von 1,5 Sekunden und die Nachfüllschwelle von 3 Sekunden bleiben erhalten; der Player wartet beim Start nicht auf die gesamte Reserve. Das Byte-Ziel hat Vorrang vor dem Zeitziel: Bei höherer Bitrate kann die Reserve kürzer sein. Media3 prüft während des Ladens in Blöcken; einzelne Blöcke oder Segmente können Zeit- und Byte-Ziele etwas überschreiten. Das Budget ist keine Obergrenze für den gesamten App-Prozess. Es gibt keinen Dateicache.

Die bestehenden Live-Profile Sport, Ausgewogen und Stabil bleiben verfügbar. Unter „Benutzerdefiniert“ stehen 10 Sekunden/60 MB, 15 Sekunden/90 MB und 20 Sekunden/120 MB bereit. Diese Profile behalten die Start-/Nachfüllschwellen von 1/2 Sekunden. Die gespeicherte Auswahl gilt ab der nächsten Verbindung und beeinflusst die Film-/Serienreserve nicht. Die Zeitziele beschreiben vorausgeladene Medien, nicht die Verzögerung zum realen Ereignis. Reserve setzt voraus, dass der Anbieter genügend Daten liefert; sie verhindert nicht jede Wiedergabeunterbrechung.

## Diagnosebericht und Barrierefreiheit

Der Bericht nutzt einen begrenzten nativen Dialog mit umbrechendem Text, Scrollleiste, sichtbarer Fokusmarkierung und separaten nativen Buttons. Beim Öffnen erhält der Scrollbereich den Fokus. Hoch/Runter scrollt, Rechts erreicht Kopieren, Hoch von den Aktionen kehrt zum Text zurück. Kopieren lässt den Bericht und seine Leseposition erhalten. Zurück schließt den Dialog und stellt den ursprünglichen Fokus wieder her. Der native Accessibility-Baum enthält den Berichtstext, benannte Buttons und die Scrollaktionen des ScrollView.

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| HIGH – behoben | `app/src/main/java/de/iptv/tv/MainActivity.kt:1464`; `app/src/main/java/de/iptv/tv/DiagnosticsDialog.kt:36` | Auswählbarer Text konnte den Fokus übernehmen; der Scrollbereich war nicht gezielt per Fernbedienung erreichbar. | Der ScrollView erhält den Eingabefokus, zeigt ihn sichtbar an und trennt das Lesen von den Aktionen. | WCAG 2.1.1 und 2.4.3: Der vollständige Bericht muss per Tastatur/D-Pad erreichbar sein. |

## Lokale Verifikation

- 53 JVM-Tests einschließlich eines Media3-Verhaltenstests für die Nachladeschwellen und den Vorrang des 120-MB-Datenbudgets.
- Android Lint sowie Debug- und optimierter Release-Build; Tag-/Versionsprüfung.
- Zwei gezielte Google-TV-Emulatortests in `BufferProfileTest`: D-Pad-Auswahl aller benutzerdefinierten Live-Profile, Persistenz nach Activity-Neustart, Abbrechen ohne Änderung, reale Film-/Serienreserve mit einem synthetischen Fünfminutenvideo, erneutes Nachladen und Spulen bis kurz vor das Ende.
- Zwei gezielte Emulatortests in `DiagnosticsDialogTest`: echter Diagnosebericht, Scrollen in beide Richtungen bis zum Ende, Kopieren ohne Schließen, Fokus-Rückkehr, vollständiger Text im Accessibility-Baum, native Accessibility-Scrollaktionen, Textumbruch bei 200 % Schrift und 320 dp Dialogbreite sowie vollständig sichtbare Aktionsbuttons.

Die vier Funktionstests liefen vor der reinen Versionsanhebung; die Android-Builds und JVM-/Lint-Prüfungen wurden für 0.5.6 wiederholt. Lokale Protokolle und Screenshots liegen unter `artifacts/buffer-profile/` und `artifacts/diagnostics-*`. Der Pufferlauf lässt sich mit `scripts/test-buffer.ps1` wiederholen; die Diagnose-Tests mit `adb shell am instrument -w -r -e class de.iptv.tv.DiagnosticsDialogTest de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner` nach Installation von App und Test-APK.

TalkBack-Sprachausgabe, formale Kontrastmessung und die Abnahme auf einem physischen Fernseher: **Not verified**. Die Bewertung betrifft ausschließlich den hier beschriebenen Diagnose-Dialog und die geprüften Bedienwege.

Approve

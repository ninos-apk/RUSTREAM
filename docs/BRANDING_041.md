# RUSTREAM IPTV PLAYER · Branding

Der App-Name lautet seit dem Update vom 17. Oktober 2026 **RUSTREAM IPTV PLAYER**. Das Manifest referenziert zentrale String-Ressourcen für Name und Beschreibung. Dadurch verwenden Installer und Android-System den neuen Namen; Startseite, App-Info und TV-Banner verwenden ebenfalls den vollständigen Namen. Die Paket-ID `de.iptv.tv` bleibt für Updates vorhandener Installationen erhalten. Die ursprüngliche Branding-Version war VersionCode 9 / VersionName 0.4.1.

Beschreibung unter **Einstellungen → App-Info**:

> RUSTREAM IPTV PLAYER ist ein IPTV-Player mit einem in Rust entwickelten Core. Der Fokus liegt auf hoher Performance und einem flüssigen Streaming-Erlebnis – besonders bei Live-Sport und Fußball.

Unter der Version steht **developed by Ninos Oshana**. Startbildschirm und Toolbar verwenden ebenfalls die neue Marke.

## Logo und Android-Ressourcen

Seit dem Logo-Update vom 17. Oktober 2026 ist `app/src/assets/logo/new_logo.png` die Quelle. Die dunkle Ableitung `app/src/assets/logo/new_logo_dark.png` ersetzt die helle Außenfläche durch Blaugrau für das dunkle TV-UI; das bereitgestellte Original bleibt erhalten. `scripts/prepare-branding.ps1` exportiert daraus mit Windows/System.Drawing die Android-Ressourcen für App-/Installer-Icon, TV-Banner, Start-/Ladebildschirm und App-Info. Icons und TV-Banner verwenden das vorhandene dunkle Blaugrau `#131923`; auch die UI-Version verwendet diese dunkle Fläche. Die semantischen Farben stehen in `app/src/main/res/values/branding.xml`. Das Quellarchiv enthält Original und dunkle Ableitung.

- Klassische TV-Icons: 80/120/160/240/320 px in den fünf Dichtestufen.
- Adaptives Launcher-Icon ab API 26: Hintergrund und gepolsterter Logo-Vordergrund für unterschiedliche Masken.
- TV-Banner: 160×90 bis 640×360, einschließlich 320×180 bei xhdpi; vollständiger App-Name im Bild.
- In-App-Logo: 512×512 px, damit das große Original nicht bei jedem Start vollständig decodiert werden muss.

Die Größen richten sich nach den [Android-TV-Icon-Vorgaben](https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines). Das Original ist kein unreferenziertes APK-Asset, sondern eine ausdrücklich erlaubte Quelldatei im Quellarchiv. Die vorhandene Ausschlussregel für private Playlist-Testassets bleibt bestehen.

## Farbprüfung des Logo-Updates vom 17. Oktober 2026

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| LOW | `app/src/main/res/values/branding.xml:3`, `scripts/prepare-branding.ps1` und alle daraus exportierten Branding-Bilder | Große weiße Logo-/Bannerfläche | Dunkles Blaugrau `#131923`, weiße Titel und türkisfarbene Unterzeile | Vorhandene TV-Palette wiederverwenden; die helle Fläche dominiert Startseite und App-Info nicht mehr. |

Gemessene WCAG-2-Kontraste der Banner-Schriftfarben auf der tatsächlich exportierten Hintergrundfarbe `#131923`: `#FFFFFF` **17,63:1**, `#67DED3` **10,89:1**. Beide überschreiten den hier verwendeten AA-Maßstab von 4,5:1 für normalen Text. Das Logo selbst ist eine Markenabbildung; seine Farbverläufe werden nicht als Text-Kontrastpaar bewertet. Die Farbwerte liegen im sRGB-Farbraum. Beurteilung auf einem physischen Fernseher: nicht geprüft.

Startseite und App-Info im TV-Emulator visuell geprüft; Icon und Banner als exportierte Ressourcen geprüft. Der vorhandene Branding-Instrumentierungstest besteht (1 Test, 0 Fehler). Debug-/Release-APK und AAB gebaut; Lint: 0 Fehler, 59 Warnungen. Protokoll: `artifacts/new-logo-dark-build.log`. Screenshots: `artifacts/dark-logo-rustream-startup.png` und `artifacts/dark-logo-rustream-app-info.png`. Farbprüfung dieses Branding-Umfangs: **Approve**.

## Ausgaben und Prüfung

Installierbare, mit lokalem Debug-Testschlüssel signierte R8-Fassung: `artifacts/RUSTREAM-IPTV-0.4.1.apk`. Zusätzlich werden `RUSTREAM-IPTV-debug.apk`, `RUSTREAM-IPTV-release-unsigned.aab` und `RUSTREAM-IPTV-source-0.4.1.zip` erzeugt. Die bisherigen technischen Dateinamen bleiben als Kompatibilitätskopien erhalten. Eine Veröffentlichung im Store ist nicht Bestandteil dieser Änderung.

Der neue Instrumentierungstest `BrandingTest` prüft den installierten Namen, Beschreibung, ladbares Icon, 16:9-TV-Banner, Launcher-Auflösung, Startbildschirm, App-Info, Version und Entwicklerzeile sowie den App-Namen in den Android-Einstellungen. Die bestehende Film-/Seriennavigation und große Systemschrift werden in der Regression mitgeprüft.

Prüfprotokolle: `artifacts/rustream-branding-build-final.log`, `rustream-branding-lint-release.log`, `rustream-branding-release-smoke.log` und `rustream-package-verification.log`.

Abnahme: 68 Rust-/JNI-, 15 JVM- und 21 Android-Tests bestanden; drei optionale Tests ohne externe Fixtures erwartungsgemäß per JUnit-Annahme abgebrochen. Die Besonderheit der AGP-HTML-Ausgabe für solche Annahmeabbrüche ist im [Inhaltsbibliotheksbericht](CONTENT_LIBRARY_040.md) erläutert. Debug/Release, vollständige Release-Lintprüfung, AAB, drei native ABIs, JNI-Exporte und 16-KiB-Ausrichtung bestanden. Die signierte R8-APK wurde mit synthetischen Medien auf Import, Filmstart und Vollbild geprüft. Branding, Beschreibung und Entwicklerzeile wurden im Emulator visuell kontrolliert; die Toolbar bleibt auch bei doppelter Schrift bedienbar.

Screenshots: [Start](../artifacts/rustream-startup.png), [App-Info](../artifacts/rustream-app-info.png), [Android-Appname](../artifacts/rustream-installed-info.png).

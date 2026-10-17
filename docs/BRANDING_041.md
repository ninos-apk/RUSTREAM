# RUSTREAM IPTV · Branding 0.4.1

Der App-Name lautet **RUSTREAM IPTV**. Das Manifest referenziert zentrale String-Ressourcen für Name und Beschreibung. Dadurch verwenden Installer und Android-System den neuen Namen. Die Paket-ID `de.iptv.tv` bleibt für Updates vorhandener Installationen erhalten; VersionCode 9 / VersionName 0.4.1.

Beschreibung unter **Einstellungen → App-Info**:

> RUSTREAM IPTV ist ein IPTV-Player mit einem in Rust entwickelten Core. Der Fokus liegt auf hoher Performance und einem flüssigen Streaming-Erlebnis – besonders bei Live-Sport und Fußball.

Unter der Version steht **developed by Ninos Oshana**. Startbildschirm und Toolbar verwenden ebenfalls die neue Marke.

## Logo und Android-Ressourcen

Das bereitgestellte Original `app/src/assets/logo/logo.png` bleibt die Quelle. `scripts/prepare-branding.ps1` exportiert daraus mit Windows/System.Drawing die Android-Ressourcen. Dabei werden Größe und Plattformflächen angepasst; das Motiv bleibt erhalten.

- Klassische TV-Icons: 80/120/160/240/320 px in den fünf Dichtestufen.
- Adaptives Launcher-Icon ab API 26: Hintergrund und gepolsterter Logo-Vordergrund für unterschiedliche Masken.
- TV-Banner: 160×90 bis 640×360, einschließlich 320×180 bei xhdpi; vollständiger App-Name im Bild.
- In-App-Logo: 512×512 px, damit das große Original nicht bei jedem Start vollständig decodiert werden muss.

Die Größen richten sich nach den [Android-TV-Icon-Vorgaben](https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines). Das Original ist kein unreferenziertes APK-Asset, sondern eine ausdrücklich erlaubte Quelldatei im Quellarchiv. Die vorhandene Ausschlussregel für private Playlist-Testassets bleibt bestehen.

## Ausgaben und Prüfung

Installierbare, mit lokalem Debug-Testschlüssel signierte R8-Fassung: `artifacts/RUSTREAM-IPTV-0.4.1.apk`. Zusätzlich werden `RUSTREAM-IPTV-debug.apk`, `RUSTREAM-IPTV-release-unsigned.aab` und `RUSTREAM-IPTV-source-0.4.1.zip` erzeugt. Die bisherigen technischen Dateinamen bleiben als Kompatibilitätskopien erhalten. Eine Veröffentlichung im Store ist nicht Bestandteil dieser Änderung.

Der neue Instrumentierungstest `BrandingTest` prüft den installierten Namen, Beschreibung, ladbares Icon, 16:9-TV-Banner, Launcher-Auflösung, Startbildschirm, App-Info, Version und Entwicklerzeile sowie den App-Namen in den Android-Einstellungen. Die bestehende Film-/Seriennavigation und große Systemschrift werden in der Regression mitgeprüft.

Prüfprotokolle: `artifacts/rustream-branding-build-final.log`, `rustream-branding-lint-release.log`, `rustream-branding-release-smoke.log` und `rustream-package-verification.log`.

Abnahme: 68 Rust-/JNI-, 15 JVM- und 21 Android-Tests bestanden; drei optionale Tests ohne externe Fixtures erwartungsgemäß per JUnit-Annahme abgebrochen. Die Besonderheit der AGP-HTML-Ausgabe für solche Annahmeabbrüche ist im [Inhaltsbibliotheksbericht](CONTENT_LIBRARY_040.md) erläutert. Debug/Release, vollständige Release-Lintprüfung, AAB, drei native ABIs, JNI-Exporte und 16-KiB-Ausrichtung bestanden. Die signierte R8-APK wurde mit synthetischen Medien auf Import, Filmstart und Vollbild geprüft. Branding, Beschreibung und Entwicklerzeile wurden im Emulator visuell kontrolliert; die Toolbar bleibt auch bei doppelter Schrift bedienbar.

Screenshots: [Start](../artifacts/rustream-startup.png), [App-Info](../artifacts/rustream-app-info.png), [Android-Appname](../artifacts/rustream-installed-info.png).

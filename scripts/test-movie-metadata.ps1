param([string]$Serial='emulator-5554',[switch]$SkipBuild)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/env.ps1"
$env:PATH=$env:PATH.Replace('"','')
$previousPreference=$ErrorActionPreference
try {
    $ErrorActionPreference='Continue'
    if(!$SkipBuild){
        & "$tvRoot/gradlew.bat" -p $tvRoot testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
        if($LASTEXITCODE){throw 'Build, unit tests or lint failed'}
    }
    $boot=(& adb -s $Serial shell getprop sys.boot_completed | Out-String).Trim()
    if($boot -ne '1' -or $Serial -notmatch '^emulator-\d+$'){throw 'A booted test emulator is required'}
    foreach($apk in @('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')){
        & adb -s $Serial install -r "$tvRoot/$apk"
        if($LASTEXITCODE){throw 'APK installation failed'}
    }
    $classes='de.iptv.tv.MovieMetadataUiTest,de.iptv.tv.ContentLibraryTest,de.iptv.tv.UserLibraryUiTest,de.iptv.tv.NavigationStateTest,de.iptv.tv.ContentVisualTest'
    $result=& adb -s $Serial shell am instrument -w -r -e class $classes de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $result | Set-Content -Encoding utf8 "$tvRoot/artifacts/movie-metadata-final-device.log"
    $text=$result | Out-String
    Write-Output $text
    if($LASTEXITCODE -or $text -notmatch 'OK \(8 tests\)' -or $text -match 'FAILURES|Process crashed|INSTRUMENTATION_STATUS_CODE: -[234]'){
        throw 'Movie metadata UI/regression tests failed or did not all execute'
    }
    $screenshots="$tvRoot/artifacts/movie-metadata-screenshots"
    New-Item -ItemType Directory -Force $screenshots | Out-Null
    foreach($name in @('normal-grid','normal-details','normal-cast','normal-series-grid','large-grid','large-details','large-cast','large-series-grid','narrow-rtl-large')){
        & adb -s $Serial pull "/sdcard/Download/iptv-metadata/$name.png" "$screenshots/$name.png"
        if($LASTEXITCODE){throw 'Screenshot collection failed'}
    }
} finally {$ErrorActionPreference=$previousPreference}

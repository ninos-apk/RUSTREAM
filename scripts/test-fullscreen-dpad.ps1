param([string]$Serial = 'emulator-5554', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
# Match the existing device runners: repair inherited PATH quotes in this process only.
$env:PATH = $env:PATH.Replace('"', '')
if ($Serial -notmatch '^emulator-\d+$') { throw 'Fullscreen D-pad tests use test emulators only.' }
if ((& adb -s $Serial shell getprop sys.boot_completed | Out-String).Trim() -ne '1') { throw 'Android is not fully booted' }
$out = "$tvRoot/artifacts/fullscreen-dpad"
New-Item -ItemType Directory -Force $out | Out-Null
if (!$SkipBuild) {
    & "$tvRoot/gradlew.bat" -p $tvRoot assembleDebug assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE) { throw 'Build failed' }
}
& adb -s $Serial install -r "$tvRoot/app/build/outputs/apk/debug/app-debug.apk"
if ($LASTEXITCODE) { throw 'App installation failed' }
& adb -s $Serial install -r "$tvRoot/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if ($LASTEXITCODE) { throw 'Test installation failed' }
$classes = 'de.iptv.tv.FullscreenDpadTest,de.iptv.tv.UiDesignTest,de.iptv.tv.SeriesPlaybackControlsTest,de.iptv.tv.SeekControlsTest,de.iptv.tv.NavigationStateTest'
& adb -s $Serial shell am instrument -w -r -e class $classes de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner | Tee-Object -FilePath "$out/instrumentation.txt"
if ($LASTEXITCODE) { throw 'Instrumentation command failed' }
$result = Get-Content -Raw -LiteralPath "$out/instrumentation.txt"
if ($result -match 'AssumptionViolatedException|INSTRUMENTATION_STATUS_CODE: -' -or $result -notmatch 'OK \(9 tests\)') {
    throw 'Fullscreen D-pad tests failed or were skipped; see artifacts/fullscreen-dpad/instrumentation.txt'
}
Write-Host 'Nine remote tests passed: fullscreen switching, preview and control focus, episode boundaries, held/media seeking and library navigation.'

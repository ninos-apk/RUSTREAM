param([string]$Serial = 'emulator-5554', [switch]$SkipBuild, [switch]$StartEmulator)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
if ($Serial -notmatch '^emulator-\d+$') { throw 'Recovery tests use test emulators only.' }
if ($StartEmulator -and ((& adb devices | Out-String) -notmatch "$Serial\s+device")) {
    Start-Process -FilePath "$env:ANDROID_HOME/emulator/emulator.exe" -ArgumentList @('-avd','iptv-ui-design','-no-window','-no-audio','-no-snapshot','-gpu','swiftshader','-memory','3072','-no-boot-anim') -WindowStyle Hidden -RedirectStandardOutput "$tvRoot/.tools/recovery-emulator.stdout.log" -RedirectStandardError "$tvRoot/.tools/recovery-emulator.stderr.log"
    $bootDeadline = [DateTime]::UtcNow.AddSeconds(180)
    do {
        Start-Sleep -Seconds 2
        $ErrorActionPreference = 'Continue'
        $booted = ((& adb -s $Serial shell getprop sys.boot_completed 2>$null | Out-String).Trim() -eq '1')
        $ErrorActionPreference = 'Stop'
        if ([DateTime]::UtcNow -gt $bootDeadline) { throw 'Test emulator did not boot' }
    } until ($booted)
}
if ((& adb -s $Serial shell getprop sys.boot_completed | Out-String).Trim() -ne '1') { throw 'Test emulator is not booted' }
if (!$SkipBuild) {
    & "$tvRoot/gradlew.bat" -p $tvRoot assembleDebug assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE) { throw 'Build failed' }
}
& adb -s $Serial install -r "$tvRoot/app/build/outputs/apk/debug/app-debug.apk"
if ($LASTEXITCODE) { throw 'App installation failed' }
& adb -s $Serial install -r "$tvRoot/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if ($LASTEXITCODE) { throw 'Test installation failed' }
$out = "$tvRoot/artifacts/live-recovery"
New-Item -ItemType Directory -Force $out | Out-Null
$classes = 'de.iptv.tv.LiveRecoveryTest,de.iptv.tv.PlaybackRecoveryPolicyTest,de.iptv.tv.BufferProfileTest#threeLiveChoicesPersistAndOldCustomChoiceMigrates,de.iptv.tv.TvEndToEndTest#liveProfileEofTimerAndPendingRetryCancellation'
& adb -s $Serial shell am instrument -w -r -e class $classes de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner | Tee-Object -FilePath "$out/instrumentation.txt"
$result = Get-Content -Raw -LiteralPath "$out/instrumentation.txt"
if ($result -match 'AssumptionViolatedException|INSTRUMENTATION_STATUS_CODE: -' -or $result -notmatch 'OK \(5 tests\)') { throw 'Live recovery tests failed; see artifacts/live-recovery/instrumentation.txt' }

param([string]$Serial = 'emulator-5554', [int]$Port = 18894, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
if ($Serial -notmatch '^emulator-\d+$') { throw 'Playback tests use test emulators only.' }
if ((& adb -s $Serial shell getprop sys.boot_completed | Out-String).Trim() -ne '1') { throw 'Android is not fully booted' }
$out = "$tvRoot/artifacts/playback-controls"
New-Item -ItemType Directory -Force $out | Out-Null
if (!$SkipBuild) {
    & "$tvRoot/gradlew.bat" -p $tvRoot assembleDebug assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE) { throw 'Build failed' }
}
& adb -s $Serial install -r "$tvRoot/app/build/outputs/apk/debug/app-debug.apk"
if ($LASTEXITCODE) { throw 'App installation failed' }
& adb -s $Serial install -r "$tvRoot/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if ($LASTEXITCODE) { throw 'Test installation failed' }
$ffmpeg = Join-Path (Split-Path $tvRoot) 'IPTV Player/.tools/ffmpeg/ffmpeg.exe'
if (!(Test-Path -LiteralPath $ffmpeg)) { $ffmpeg = (Get-Command ffmpeg -ErrorAction Stop).Source }
$video = "$out/reserve.mp4"
& $ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=320x180:rate=10' -t 300 -c:v libx264 -preset ultrafast -crf 32 -pix_fmt yuv420p -movflags +faststart -y $video
if ($LASTEXITCODE) { throw 'Video generation failed' }
$server = $null
try {
    $server = Start-Process -FilePath "$env:JAVA_HOME/bin/java.exe" -ArgumentList @(('"' + "$PSScriptRoot/UiDemoServer.java" + '"'), $Port, ('"' + $video + '"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput "$out/server.stdout.log" -RedirectStandardError "$out/server.stderr.log"
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while (!(Select-String -LiteralPath "$out/server.stdout.log" -Pattern 'ready' -Quiet)) {
        if ($server.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Fixture server failed to start' }
        Start-Sleep -Milliseconds 200
    }
    & adb -s $Serial reverse "tcp:$Port" "tcp:$Port"
    if ($LASTEXITCODE) { throw 'adb reverse failed' }
    $classes = 'de.iptv.tv.SeriesPlaybackControlsTest,de.iptv.tv.SeekControlsTest,de.iptv.tv.BufferProfileTest,de.iptv.tv.UiDesignTest#keyReleaseStopsAcceleratingSeekAndRepeatEventsDoNotRestartIt'
    & adb -s $Serial shell am instrument -w -r -e bufferFixtureUrl "http://127.0.0.1:$Port/reserve.mp4" -e class $classes de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner | Tee-Object -FilePath "$out/instrumentation.txt"
    $result = Get-Content -Raw -LiteralPath "$out/instrumentation.txt"
    if ($result -match 'AssumptionViolatedException|INSTRUMENTATION_STATUS_CODE: -' -or $result -notmatch 'OK \(5 tests\)') { throw 'Playback tests failed or were skipped; see artifacts/playback-controls/instrumentation.txt' }
    Write-Host 'Playback tests passed: restart/previous, held buttons/media keys, reserve refill, stalled download recovery and existing D-pad seeking.'
} finally {
    $ErrorActionPreference = 'Continue'
    if ($server) { Stop-Process -Id $server.Id -ErrorAction SilentlyContinue }
    & adb -s $Serial reverse --remove "tcp:$Port" 2>$null
}

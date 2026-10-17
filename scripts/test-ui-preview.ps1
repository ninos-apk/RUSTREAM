param([string]$Serial = 'emulator-5554', [int]$Port = 18891, [string]$Ffmpeg, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
$env:ANDROID_SERIAL = $Serial
if ((& adb -s $Serial shell getprop sys.boot_completed | Out-String).Trim() -ne '1') { throw 'Android is not fully booted' }
$out = "$tvRoot/artifacts/ui-preview"
New-Item -ItemType Directory -Force $out | Out-Null
if (!$Ffmpeg) {
    $Ffmpeg = Join-Path (Split-Path $tvRoot) 'IPTV Player/.tools/ffmpeg/ffmpeg.exe'
    if (!(Test-Path -LiteralPath $Ffmpeg)) { $Ffmpeg = (Get-Command ffmpeg -ErrorAction SilentlyContinue).Source }
}
if (!$Ffmpeg -or !(Test-Path -LiteralPath $Ffmpeg)) { throw 'Pass a local ffmpeg executable using -Ffmpeg.' }
# Five silent minutes at 320x180 (about 6.5 MB): room for the hold-to-seek check, small enough to seek quickly through adb reverse.
$video = "$out/demo-long.mp4"
& $Ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=320x180:rate=10' -t 300 -c:v libx264 -preset ultrafast -crf 32 -pix_fmt yuv420p -movflags +faststart -y $video
if ($LASTEXITCODE) { throw 'Demo video generation failed' }
$screenshots = @('ui-design-import.png', 'ui-design-real-tap.png', 'ui-design-real-hold.png', 'ui-design-real-controls.png')
function Save-Screenshots {
    # adb reports pulled files on stderr; Windows PowerShell 5.1 would stop on it when output is captured.
    $ErrorActionPreference = 'Continue'
    foreach ($name in $screenshots) { & adb -s $Serial pull "/sdcard/Download/$name" "$out/$name" 2>$null | Out-Null }
}
$server = $null
Push-Location $tvRoot
try {
    if (!$SkipBuild) {
        & ./gradlew.bat installDebug installDebugAndroidTest --console=plain
        if ($LASTEXITCODE) { throw 'App or test APK installation failed' }
    }
    # Only this run's screenshots end up in artifacts/ui-preview.
    & adb -s $Serial shell rm -f ($screenshots | ForEach-Object { "/sdcard/Download/$_" })
    $quotedScript = '"' + "$PSScriptRoot/UiDemoServer.java" + '"'
    $quotedVideo = '"' + $video + '"'
    $server = Start-Process -FilePath "$env:JAVA_HOME/bin/java.exe" -ArgumentList @($quotedScript, $Port, $quotedVideo) -WindowStyle Hidden -PassThru -RedirectStandardOutput "$out/server.stdout.log" -RedirectStandardError "$out/server.stderr.log"
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while (!(Select-String -LiteralPath "$out/server.stdout.log" -Pattern 'ready' -Quiet)) {
        if ($server.HasExited) { throw "UI demo server stopped: $(Get-Content -Raw "$out/server.stderr.log")" }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'UI demo server did not start' }
        Start-Sleep -Milliseconds 200
    }
    & adb -s $Serial reverse "tcp:$Port" "tcp:$Port" | Out-Null
    if ($LASTEXITCODE) { throw 'adb reverse failed' }
    & adb -s $Serial shell am instrument -w -r -e uiDemoUrl "http://127.0.0.1:$Port/playlist.m3u" -e class de.iptv.tv.UiPreviewTest de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner | Tee-Object -FilePath "$out/instrumentation.txt"
    Save-Screenshots
    $result = Get-Content -Raw -LiteralPath "$out/instrumentation.txt"
    # A skipped test (assumption failure, status -4) still ends with "OK (1 test)"; only status 0 is a pass.
    if ($result -match 'AssumptionViolatedException|INSTRUMENTATION_STATUS_CODE: -') { throw 'UiPreviewTest failed or was skipped; see artifacts/ui-preview/instrumentation.txt' }
    if ($result -notmatch 'INSTRUMENTATION_STATUS_CODE: 0' -or $result -notmatch 'OK \(1 test\)') { throw 'UiPreviewTest did not pass; see artifacts/ui-preview/instrumentation.txt' }
    & adb -s $Serial logcat -d -s IptvUiSeek:I | Select-Object -Last 1
    Write-Host 'UiPreviewTest: local import, movie details, fullscreen and real-decoder seeking passed.'
} finally {
    # Cleanup must not stop halfway: adb reports a missing reverse rule on stderr.
    $ErrorActionPreference = 'Continue'
    if ($server) { Stop-Process -Id $server.Id -ErrorAction SilentlyContinue }
    & adb -s $Serial reverse --remove "tcp:$Port" 2>$null
    Pop-Location
}

param([string]$Serial = 'emulator-5554', [int]$Port = 18893, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
if ($Serial -notmatch '^emulator-\d+$') { throw 'Buffer tests use disposable emulators only.' }
if ((& adb -s $Serial shell getprop sys.boot_completed | Out-String).Trim() -ne '1') { throw 'Android is not fully booted' }
$out = "$tvRoot/artifacts/buffer-profile"
New-Item -ItemType Directory -Force $out | Out-Null
$ffmpeg = Join-Path (Split-Path $tvRoot) 'IPTV Player/.tools/ffmpeg/ffmpeg.exe'
if (!(Test-Path -LiteralPath $ffmpeg)) { $ffmpeg = (Get-Command ffmpeg -ErrorAction Stop).Source }
$video = "$out/reserve.mp4"
& $ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=320x180:rate=10' -t 300 -c:v libx264 -preset ultrafast -crf 32 -pix_fmt yuv420p -movflags +faststart -y $video
if ($LASTEXITCODE) { throw 'Buffer fixture generation failed' }
$server = $null
try {
    if (!$SkipBuild) {
        & "$tvRoot/gradlew.bat" -p $tvRoot installDebug installDebugAndroidTest --console=plain
        if ($LASTEXITCODE) { throw 'App or test installation failed' }
    }
    $quotedScript = '"' + "$PSScriptRoot/UiDemoServer.java" + '"'
    $quotedVideo = '"' + $video + '"'
    $server = Start-Process -FilePath "$env:JAVA_HOME/bin/java.exe" -ArgumentList @($quotedScript, $Port, $quotedVideo) -WindowStyle Hidden -PassThru -RedirectStandardOutput "$out/server.stdout.log" -RedirectStandardError "$out/server.stderr.log"
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while (!(Select-String -LiteralPath "$out/server.stdout.log" -Pattern 'ready' -Quiet)) {
        if ($server.HasExited) { throw "Buffer server stopped: $(Get-Content -Raw "$out/server.stderr.log")" }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Buffer server did not start' }
        Start-Sleep -Milliseconds 200
    }
    & adb -s $Serial reverse "tcp:$Port" "tcp:$Port"
    if ($LASTEXITCODE) { throw 'adb reverse failed' }
    & adb -s $Serial shell am instrument -w -r -e bufferFixtureUrl "http://127.0.0.1:$Port/reserve.mp4" -e class de.iptv.tv.BufferProfileTest de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner | Tee-Object -FilePath "$out/instrumentation.txt"
    $result = Get-Content -Raw -LiteralPath "$out/instrumentation.txt"
    if ($result -match 'AssumptionViolatedException|INSTRUMENTATION_STATUS_CODE: -' -or $result -notmatch 'OK \(2 tests\)') { throw 'Buffer tests failed or were skipped; see artifacts/buffer-profile/instrumentation.txt' }
    & adb -s $Serial pull /sdcard/Download/buffer-profiles.png "$out/buffer-profiles.png"
    Write-Host 'Buffer tests passed: remote selection, persistence, two-minute film/series reserve, refill and seeking near the end.'
} finally {
    $ErrorActionPreference = 'Continue'
    if ($server) { Stop-Process -Id $server.Id -ErrorAction SilentlyContinue }
    & adb -s $Serial reverse --remove "tcp:$Port" 2>$null
}

param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
$env:ANDROID_SERIAL = $Serial
if ((& adb -s $Serial shell getprop sys.boot_completed | Out-String).Trim() -ne '1') { throw 'Android is not fully booted' }
& adb -s $Serial shell getconf PAGE_SIZE
Push-Location $tvRoot
try {
    & ./gradlew.bat connectedDebugAndroidTest --console=plain
    if ($LASTEXITCODE) { throw 'Connected tests failed' }
    New-Item -ItemType Directory -Force artifacts | Out-Null
    & adb -s $Serial pull /sdcard/Download/iptv-tv-e2e.png "$tvRoot/artifacts/tv-e2e.png"
} finally { Pop-Location }


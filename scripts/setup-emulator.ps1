$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
$image = 'system-images;android-36;google-tv-ps16k;x86_64'
& "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" "--sdk_root=$env:ANDROID_HOME" 'emulator' $image
if ($LASTEXITCODE) { throw 'Emulator packages failed' }
if (!(Test-Path "$tvRoot/.tools/avd/iptv-google-tv-16k/config.ini")) {
    'no' | & "$env:ANDROID_HOME/cmdline-tools/latest/bin/avdmanager.bat" create avd --name iptv-google-tv-16k --package $image --path "$tvRoot/.tools/avd/iptv-google-tv-16k" --device tv_1080p
    # Some TV images omit optional devices.xml; avdmanager still writes the complete config.
    if (!(Test-Path "$tvRoot/.tools/avd/iptv-google-tv-16k/config.ini")) { throw 'AVD creation failed' }
}
$config = "$tvRoot/.tools/avd/iptv-google-tv-16k/config.ini"
(Get-Content -Raw $config).Replace('avd.id=<build>','avd.id=iptv-google-tv-16k').Replace('avd.name=<build>','avd.name=iptv-google-tv-16k').Replace('hw.initialOrientation=portrait','hw.initialOrientation=landscape') | Set-Content -Encoding utf8NoBOM $config
if ((& adb devices | Out-String) -notmatch 'emulator-\d+\s+device') {
    Start-Process -FilePath "$env:ANDROID_HOME/emulator/emulator.exe" -ArgumentList @('-avd','iptv-google-tv-16k','-no-window','-no-audio','-no-snapshot','-gpu','swiftshader','-memory','3072','-no-boot-anim') -WindowStyle Hidden -RedirectStandardOutput "$tvRoot/.tools/emulator.stdout.log" -RedirectStandardError "$tvRoot/.tools/emulator.stderr.log"
}
Write-Host 'Emulator starting. Wait for adb shell getprop sys.boot_completed = 1, then run scripts/test-device.ps1.'


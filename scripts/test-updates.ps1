param([switch]$DeviceTests, [string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
# A stray quote in an inherited Windows PATH breaks the JVM's native-library argument.
# Repair only this process environment, never the user's machine settings.
$env:PATH = $env:PATH.Replace('"', '')
Push-Location $tvRoot
try {
    $ErrorActionPreference = 'Continue'
    & ./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest --no-daemon --console=plain
    if ($LASTEXITCODE) { throw 'Update build, tests or lint failed' }
    $ErrorActionPreference = 'Stop'
    $source = Get-Content -Raw -Encoding utf8 app/build.gradle.kts
    $version = [regex]::Match($source, '(?m)^\s*versionName\s*=\s*"([^"\r\n]+)"\s*$').Groups[1].Value
    & "$PSScriptRoot/check-release-tag.ps1" -Tag "v$version" -CheckHistory | Out-Null
    & "$PSScriptRoot/generate-release-notes.ps1" -Version $version
    $env:UPDATE_STORE_PASSWORD = 'android'
    $env:UPDATE_KEY_PASSWORD = 'android'
    $key = "$env:ANDROID_USER_HOME/debug.keystore"
    $apk = "artifacts/RUSTREAM-IPTV-PLAYER-$version.apk"
    & "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" sign --ks $key --ks-key-alias androiddebugkey --ks-pass env:UPDATE_STORE_PASSWORD --key-pass env:UPDATE_KEY_PASSWORD --out $apk app/build/outputs/apk/release/app-release-unsigned.apk
    if ($LASTEXITCODE) { throw 'Signing the local release test copy failed' }
    & "$PSScriptRoot/create-update-manifest.ps1" -Apk $apk -Keystore $key
    if ($DeviceTests) {
        if ($Serial -notmatch '^emulator-\d+$') { throw 'Automated installation tests are restricted to test emulators.' }
        $ErrorActionPreference = 'Continue'
        & adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
        if ($LASTEXITCODE) { throw 'Debug APK installation failed' }
        & adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
        if ($LASTEXITCODE) { throw 'Test APK installation failed' }
        $result = & adb -s $Serial shell am instrument -w -r -e class de.iptv.tv.UpdateDialogTest de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner 2>&1
        $result | Write-Output
        if ($LASTEXITCODE -or ($result -join "`n") -notmatch 'OK \([0-9]+ tests?\)') { throw 'TV update UI tests failed' }
        & adb -s $Serial pull /sdcard/Download/updates-large-text.png artifacts/updates-large-text.png
    }
    Write-Output 'Update checks passed. No tag or GitHub release was created.'
} finally { Pop-Location }

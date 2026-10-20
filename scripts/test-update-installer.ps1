param([ValidatePattern('^emulator-\d+$')][string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
$env:PATH = $env:PATH.Replace('"', '')
Push-Location $tvRoot
$originalMode = 'default'
try {
    $source = Get-Content -Raw -Encoding utf8 app/build.gradle.kts
    $code = [int][regex]::Match($source, '(?m)^\s*versionCode\s*=\s*([0-9]+)\s*$').Groups[1].Value
    $version = [regex]::Match($source, '(?m)^\s*versionName\s*=\s*"([0-9.]+)"\s*$').Groups[1].Value
    if (!$version) { throw 'Installer fixture script expects a plain numeric baseline version.' }
    $parts = $version.Split('.')
    $nextVersion = "$($parts[0]).$($parts[1]).$([int]$parts[2] + 1)-alpha.1"
    $nextCode = $code + 1
    $ErrorActionPreference = 'Continue'
    & ./gradlew.bat assembleDebug assembleDebugAndroidTest --no-daemon --console=plain
    if ($LASTEXITCODE) { throw 'Baseline build failed' }
    & adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE) { throw 'Baseline installation failed' }
    & adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE) { throw 'Test installation failed' }
    $ErrorActionPreference = 'Stop'
    New-Item -ItemType Directory -Force artifacts | Out-Null
    $init = @'
gradle.beforeProject { p ->
    if (p.path == ':app') {
        p.plugins.withId('com.android.application') {
            p.androidComponents.onVariants(p.androidComponents.selector().withBuildType('debug')) { variant ->
                variant.outputs.each { output ->
                    output.versionCode.set(FIXTURE_CODE)
                    output.versionName.set('FIXTURE_VERSION')
                }
            }
        }
    }
}
'@
    [IO.File]::WriteAllText((Join-Path $tvRoot 'artifacts/update-fixture.gradle'), $init.Replace('FIXTURE_CODE', [string]$nextCode).Replace('FIXTURE_VERSION', $nextVersion), [Text.UTF8Encoding]::new($false))
    $ErrorActionPreference = 'Continue'
    & ./gradlew.bat assembleDebug --init-script artifacts/update-fixture.gradle --no-daemon --console=plain
    if ($LASTEXITCODE) { throw 'Higher-version test fixture build failed' }
    Copy-Item -LiteralPath app/build/outputs/apk/debug/app-debug.apk -Destination artifacts/update-fixture.apk -Force
    & adb -s $Serial push artifacts/update-fixture.apk /data/local/tmp/rustream-update-fixture.apk
    if ($LASTEXITCODE) { throw 'Cannot push installer fixture' }
    & adb -s $Serial shell run-as de.iptv.tv cp /data/local/tmp/rustream-update-fixture.apk files/update-fixture.apk
    if ($LASTEXITCODE) { throw 'Cannot stage installer fixture in the private test app directory' }
    $mode = (& adb -s $Serial shell appops get de.iptv.tv REQUEST_INSTALL_PACKAGES 2>&1) -join "`n"
    if ($mode -match 'REQUEST_INSTALL_PACKAGES:\s*(allow|deny|ignore|default)') { $originalMode = $Matches[1] }
    & adb -s $Serial shell appops set de.iptv.tv REQUEST_INSTALL_PACKAGES allow
    if ($LASTEXITCODE) { throw 'Cannot grant install-source access for the test' }
    $result = & adb -s $Serial shell am instrument -w -r -e class de.iptv.tv.UpdateInstallerTest de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $result | Write-Output
    if ($LASTEXITCODE -or ($result -join "`n") -notmatch 'OK \(1 test\)') { throw 'System installer confirmation test failed' }
} finally {
    $ErrorActionPreference = 'Continue'
    & adb -s $Serial shell appops set de.iptv.tv REQUEST_INSTALL_PACKAGES $originalMode
    & adb -s $Serial shell run-as de.iptv.tv rm -f files/update-fixture.apk
    & adb -s $Serial shell rm -f /data/local/tmp/rustream-update-fixture.apk
    # Recreate the ordinary debug output; never leave the fixture as the default APK.
    & ./gradlew.bat assembleDebug --no-daemon --console=plain
    Pop-Location
}

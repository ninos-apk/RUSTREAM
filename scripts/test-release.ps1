param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
if ($Serial -notmatch '^emulator-\d+$') { throw 'Release smoke test is restricted to disposable emulators.' }
New-Item -ItemType Directory -Force "$tvRoot/artifacts" | Out-Null
$signed = "$tvRoot/artifacts/iptv-tv-release-test.apk"
$apksigner = "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat"
& $apksigner sign --ks "$env:ANDROID_USER_HOME/debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out $signed "$tvRoot/app/build/outputs/apk/release/app-release-unsigned.apk"
if ($LASTEXITCODE) { throw 'Signing test copy failed' }
& $apksigner verify $signed
if ($LASTEXITCODE) { throw 'Test signature verification failed' }
$quotedScript = '"' + "$PSScriptRoot/FixtureHttpServer.java" + '"'
$quotedFixture = '"' + "$tvRoot/app/src/androidTest/assets/fixture.mp4" + '"'
$server = Start-Process -FilePath "$env:JAVA_HOME/bin/java.exe" -ArgumentList @($quotedScript, '18888', $quotedFixture) -WindowStyle Hidden -PassThru -RedirectStandardOutput "$tvRoot/.tools/release-server.stdout.log" -RedirectStandardError "$tvRoot/.tools/release-server.stderr.log"
function Get-Ui {
    & adb -s $Serial shell uiautomator dump /sdcard/Download/iptv-release-ui.xml | Out-Null
    & adb -s $Serial pull /sdcard/Download/iptv-release-ui.xml "$tvRoot/artifacts/release-ui.xml" 2>$null | Out-Null
    return [xml](Get-Content -Raw "$tvRoot/artifacts/release-ui.xml")
}
function Tap-Node($Node) {
    if (!$Node) { throw 'Required UI element missing' }
    $coordinates = [regex]::Matches($Node.bounds,'\d+') | ForEach-Object { [int]$_.Value }
    & adb -s $Serial shell input tap ([int](($coordinates[0]+$coordinates[2])/2)) ([int](($coordinates[1]+$coordinates[3])/2))
}
function Focus-Input([int]$Index) {
    for ($attempt=0; $attempt -lt 3; $attempt++) {
        $current=Get-Ui
        $inputs=@($current.SelectNodes('//node') | Where-Object class -eq 'android.widget.EditText')
        if ($inputs.Count -le $Index) { throw 'Release form input missing' }
        Tap-Node $inputs[$Index]
        # A TV keyboard can consume the outside tap; wait for the actual input focus.
        $current=Get-Ui
        $inputs=@($current.SelectNodes('//node') | Where-Object class -eq 'android.widget.EditText')
        Write-Host "Form focus $Index, attempt $($attempt+1): $($inputs | ForEach-Object { "$($_.bounds) focused=$($_.focused)" })"
        if ($inputs.Count -gt $Index -and $inputs[$Index].focused -eq 'true') { return }
    }
    throw 'Release form input did not receive focus'
}
try {
    & adb -s $Serial reverse tcp:18888 tcp:18888
    & adb -s $Serial install -r $signed
    if ($LASTEXITCODE) { throw 'Release installation failed' }
    & adb -s $Serial shell pm clear de.iptv.tv | Out-Null
    & adb -s $Serial shell am start -n de.iptv.tv/.MainActivity | Out-Null
    $ui = Get-Ui
    $onboarding = $ui.SelectNodes('//node') | Where-Object text -eq 'Quelle hinzufügen' | Select-Object -First 1
    if ($onboarding) { Tap-Node $onboarding }
    else { Tap-Node ($ui.SelectNodes('//node') | Where-Object 'content-desc' -eq 'Einstellungen' | Select-Object -First 1) }
    $ui = Get-Ui
    if (!($ui.SelectNodes('//node') | Where-Object text -eq 'URL hinzufügen')) {
        & adb -s $Serial shell input keyevent KEYCODE_DPAD_CENTER
        $ui = Get-Ui
    }
    Tap-Node ($ui.SelectNodes('//node') | Where-Object text -eq 'URL hinzufügen' | Select-Object -First 1)
    $ui = Get-Ui
    $fields = @($ui.SelectNodes('//node') | Where-Object class -eq 'android.widget.EditText')
    Focus-Input 0
    & adb -s $Serial shell input text ReleaseFixture
    # Close the TV keyboard before moving to the next field: its overlay consumes taps.
    & adb -s $Serial shell input keyevent KEYCODE_BACK
    $ui = Get-Ui
    $fields = @($ui.SelectNodes('//node') | Where-Object class -eq 'android.widget.EditText')
    Focus-Input 1
    & adb -s $Serial shell input text http://127.0.0.1:18888/playlist.m3u
    $ui = Get-Ui
    Copy-Item -LiteralPath "$tvRoot/artifacts/release-ui.xml" -Destination "$tvRoot/artifacts/release-form.xml"
    $fields = @($ui.SelectNodes('//node') | Where-Object class -eq 'android.widget.EditText')
    if ($fields[1].text -ne 'http://127.0.0.1:18888/playlist.m3u') { throw 'Release test URL entry failed' }
    # The TV keyboard consumes the first outside tap. Close it before locating the dialog button.
    & adb -s $Serial shell input keyevent KEYCODE_BACK
    $ui = Get-Ui
    Tap-Node ($ui.SelectNodes('//node') | Where-Object text -eq 'Übernehmen' | Select-Object -First 1)
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do { $ui = Get-Ui; $imported = $ui.SelectNodes('//node') | Where-Object text -eq 'Release Fixture' } while (!$imported -and [DateTime]::UtcNow -lt $deadline)
    if (!$imported) { throw 'Release JNI import did not finish' }
    & adb -s $Serial shell input keyevent KEYCODE_DPAD_CENTER
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $ui = Get-Ui
        $playing = $ui.SelectNodes('//node') | Where-Object text -eq 'Wiedergabe · Release Fixture'
    } while (!$playing -and [DateTime]::UtcNow -lt $deadline)
    if (!$playing) { throw 'Release playback did not start' }
    & adb -s $Serial shell screencap -p /sdcard/Download/iptv-release.png
    & adb -s $Serial pull /sdcard/Download/iptv-release.png "$tvRoot/artifacts/tv-release.png"
    Write-Host 'R8 release: launch, JNI import, D-pad and real Media3 playback passed.'
} finally {
    & adb -s $Serial shell am force-stop de.iptv.tv
    & adb -s $Serial reverse --remove tcp:18888
    Stop-Process -Id $server.Id -ErrorAction SilentlyContinue
}


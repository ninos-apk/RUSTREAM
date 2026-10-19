param(
    [ValidatePattern('^[A-Za-z0-9_.-]+$')]
    [string]$AvdName = 'iptv-google-tv-16k',
    [string]$Apk,
    [ValidateRange(30,600)][int]$BootTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = Join-Path $projectRoot '.tools/android-sdk'
$adb = Join-Path $sdkRoot 'platform-tools/adb.exe'
$emulator = Join-Path $sdkRoot 'emulator/emulator.exe'
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
$env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
Remove-Item Env:\ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    # Windows PowerShell treats native stderr as an error even when ADB succeeds.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $adb @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    $message = ($output | Out-String).Trim()
    if ($code -ne 0 -and !$AllowFailure) { throw "ADB fehlgeschlagen: $message" }
    if ($code -eq 0) { return $message }
}

function Find-TvEmulator {
    $devices = Invoke-Adb -Arguments @('devices')
    foreach ($match in [regex]::Matches($devices, '(?m)^(emulator-\d+)\s+(?:device|offline)\s*$')) {
        $candidate = $match.Groups[1].Value
        $name = Invoke-Adb -Arguments @('-s',$candidate,'emu','avd','name') -AllowFailure
        if (($name -split '\r?\n') -contains $AvdName) { return $candidate }
    }
}

function Select-TvEmulatorProcess {
    param([object[]]$Processes, [string]$Name, [string]$Executable)
    $namePattern = '(?:^|\s)-avd\s+"?' + [regex]::Escape($Name) + '"?(?=\s|$)'
    $Processes | Where-Object {
        $_.ExecutablePath -eq $Executable -and $_.CommandLine -match $namePattern
    } | Select-Object -First 1
}

function Get-TvEmulatorArguments {
    param([string]$Name)
    # A cold boot preserves userdata. Quick Boot state from GUI/headless test runs can
    # fail with "Error -22 while loading VM state" and leave ADB offline indefinitely.
    @('-avd',$Name,'-no-boot-anim','-no-snapshot','-gpu','swiftshader','-memory','3072')
}

try {
    foreach ($tool in @($adb,$emulator)) {
        if (!(Test-Path -LiteralPath $tool)) { throw "Android-Werkzeug fehlt: $tool. Zuerst scripts/setup.ps1 ausfuehren." }
    }
    $descriptor = Join-Path $env:ANDROID_AVD_HOME "$AvdName.ini"
    if (!(Test-Path -LiteralPath $descriptor)) { throw "TV-Emulator '$AvdName' fehlt. Zuerst scripts/setup-emulator.ps1 ausfuehren." }
    $pathLine = Get-Content -LiteralPath $descriptor | Where-Object { $_ -match '^path=' } | Select-Object -First 1
    if (!$pathLine) { throw "Kein AVD-Pfad in $descriptor gefunden." }
    $configPath = Join-Path $pathLine.Substring(5) 'config.ini'
    if (!(Test-Path -LiteralPath $configPath)) { throw "AVD-Konfiguration fehlt: $configPath" }

    if (!$Apk) {
        $candidates = @(
            (Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'),
            (Join-Path $projectRoot 'artifacts/RUSTREAM-IPTV-debug.apk'),
            (Join-Path $projectRoot 'artifacts/RUSTREAM-IPTV-filmdetails-debug.apk'),
            (Join-Path $projectRoot 'artifacts/RUSTREAM-IPTV-movie-layout.apk')
        )
        $latest = $candidates | Where-Object { Test-Path -LiteralPath $_ } |
            Get-Item | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (!$latest) { throw 'Keine Debug-APK gefunden. Zuerst scripts/build.ps1 ausfuehren.' }
        $Apk = $latest.FullName
    } else { $Apk = (Resolve-Path -LiteralPath $Apk).Path }

    # Enable physical keyboard input persistently for subsequent launches as well.
    $config = [System.IO.File]::ReadAllText($configPath)
    if ($config -notmatch '(?m)^hw\.keyboard=yes\r?$') {
        if ($config -match '(?m)^hw\.keyboard=') {
            $config = [regex]::Replace($config, '(?m)^hw\.keyboard=[^\r\n]*', 'hw.keyboard=yes')
        } else { $config = $config.TrimEnd() + "`r`nhw.keyboard=yes`r`n" }
        [System.IO.File]::WriteAllText($configPath, $config, [System.Text.UTF8Encoding]::new($false))
    }

    Write-Host 'RUSTREAM - Android TV starten' -ForegroundColor Cyan
    Invoke-Adb -Arguments @('start-server') | Out-Null
    $serial = Find-TvEmulator
    $running = Select-TvEmulatorProcess -Processes @(Get-CimInstance Win32_Process -Filter "Name='emulator.exe'") -Name $AvdName -Executable $emulator
    if ($running -and $running.CommandLine -match '(?:^|\s)-no-window(?=\s|$)') {
        if (!$serial) { throw 'Der Hintergrundemulator ist noch nicht ueber ADB erreichbar. Kurz warten und den Starter erneut ausfuehren.' }
        Write-Host 'Hintergrund-Testemulator wird fuer ein sichtbares Fenster neu gestartet (App-Daten bleiben erhalten) ...'
        $backgroundProcess = Get-Process -Id $running.ProcessId -ErrorAction SilentlyContinue
        Invoke-Adb -Arguments @('-s',$serial,'emu','kill') | Out-Null
        if ($backgroundProcess) { Wait-Process -InputObject $backgroundProcess -Timeout 30 -ErrorAction Stop }
        $serial = $null; $running = $null
    }
    $emulatorProcess = $null
    if (!$serial -and !$running) {
        Write-Host 'Emulatorfenster wird geoeffnet ...'
        $emulatorProcess = Start-Process -FilePath $emulator -ArgumentList (Get-TvEmulatorArguments -Name $AvdName) -WorkingDirectory $projectRoot -WindowStyle Normal -PassThru `
            -RedirectStandardOutput (Join-Path $projectRoot '.tools/emulator-launcher.stdout.log') `
            -RedirectStandardError (Join-Path $projectRoot '.tools/emulator-launcher.stderr.log')
    } else {
        if ($running) { $emulatorProcess = Get-Process -Id $running.ProcessId -ErrorAction SilentlyContinue }
        Write-Host "Laufender TV-Emulator wird verwendet: $serial"
    }

    Write-Host 'Warte auf Android ...'
    $deadline = [DateTime]::UtcNow.AddSeconds($BootTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (!$serial) { $serial = Find-TvEmulator }
        if ($serial) {
            $boot = Invoke-Adb -Arguments @('-s',$serial,'shell','getprop','sys.boot_completed') -AllowFailure
            if ($boot -eq '1') { break }
        }
        if ($emulatorProcess -and $emulatorProcess.HasExited) {
            throw "Emulator wurde beendet. Details: .tools/emulator-launcher.stdout.log und .tools/emulator-launcher.stderr.log"
        }
        Start-Sleep -Milliseconds 750
    }
    if (!$serial -or $boot -ne '1') { throw "Android ist nach $BootTimeoutSeconds Sekunden nicht bereit. Details: .tools/emulator-launcher.stdout.log und .tools/emulator-launcher.stderr.log. Bei einer bereits laufenden Instanz deren Fenster schliessen und den Starter erneut ausfuehren." }

    Invoke-Adb -Arguments @('-s',$serial,'shell','input','keyevent','KEYCODE_WAKEUP') | Out-Null
    Invoke-Adb -Arguments @('-s',$serial,'shell','wm','dismiss-keyguard') | Out-Null
    Invoke-Adb -Arguments @('-s',$serial,'shell','settings','put','secure','show_ime_with_hard_keyboard','0') | Out-Null
    Write-Host "Installiere: $Apk"
    Invoke-Adb -Arguments @('-s',$serial,'install','-r',$Apk) | Out-Null
    $launch = Invoke-Adb -Arguments @('-s',$serial,'shell','am','start','-W','-n','de.iptv.tv/.MainActivity')
    if ($launch -match '(?m)^Error:|Error type \d+') { throw "App konnte nicht gestartet werden: $launch" }
    Write-Host ''
    Write-Host 'App ist bereit. Ins Emulatorfenster klicken und testen.' -ForegroundColor Green
    Write-Host 'Pfeiltasten = Navigation | Enter = OK | Esc = Zurueck'
    Write-Host 'Suchfelder: direkt mit der PC-Tastatur schreiben.'
    exit 0
} catch {
    Write-Host "`nFehler: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

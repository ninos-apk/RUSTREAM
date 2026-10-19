$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$launcher=Join-Path $PSScriptRoot 'start-emulator.ps1'
$tokens=$null;$parseErrors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($launcher,[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw ($parseErrors | Out-String)}
# Load the real helper functions without running the launcher's emulator/install entry point.
foreach($definition in $ast.FindAll({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst]},$false)){
    . ([scriptblock]::Create($definition.Extent.Text))
}
$checks=0
function Assert-Launcher {
    param([bool]$Condition,[string]$Message)
    if(!$Condition){throw $Message}
    $script:checks++
    Write-Output "PASS: $Message"
}
$launchArguments=@(Get-TvEmulatorArguments -Name 'iptv-google-tv-16k')
Assert-Launcher ($launchArguments -contains '-no-snapshot') 'Cold boot bypasses the incompatible Quick Boot VM state'
Assert-Launcher ($launchArguments -notcontains '-wipe-data') 'Cold boot never wipes installed apps or app data'
Assert-Launcher ($launchArguments -notcontains '-no-window') 'The interactive launcher requests a visible emulator'

$exe='C:\SDK with spaces\emulator.exe'
$processes=@(
    [pscustomobject]@{ProcessId=1;ExecutablePath='C:\other-sdk\emulator.exe';CommandLine='emulator -avd iptv-google-tv-16k -no-window'},
    [pscustomobject]@{ProcessId=2;ExecutablePath=$exe;CommandLine='emulator -avd iptv-google-tv-16k-other -no-window'},
    [pscustomobject]@{ProcessId=3;ExecutablePath=$exe;CommandLine='emulator -avd "iptv-google-tv-16k" -no-window'}
)
$matching=Select-TvEmulatorProcess -Processes $processes -Name 'iptv-google-tv-16k' -Executable $exe
Assert-Launcher ($matching.ProcessId -eq 3) 'Only the exact AVD from this SDK can be selected for reuse/restart'
Assert-Launcher ($matching.CommandLine -match '(?:^|\s)-no-window(?=\s|$)') 'An existing headless test instance is distinguishable from a visible window'
$dots=@([pscustomobject]@{ExecutablePath=$exe;CommandLine='emulator -avd tvXtest'})
Assert-Launcher ($null -eq (Select-TvEmulatorProcess -Processes $dots -Name 'tv.test' -Executable $exe)) 'An AVD name is matched literally rather than as a regular expression'

$temporary=Join-Path ([System.IO.Path]::GetTempPath()) ('rustream-launcher-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
$stub=Join-Path $temporary 'adb stub.cmd'
try {
    @'
@echo off
if "%~1"=="devices" (
    echo List of devices attached
    echo emulator-5554 offline
    echo emulator-5556 offline
    exit /b 0
)
if "%~1"=="-s" (
    if "%~2"=="emulator-5554" (
        echo another-avd
    ) else (
        echo iptv-google-tv-16k
    )
    echo OK
    exit /b 0
)
if "%~1"=="stderr-success" (
    echo daemon startup message 1>&2
    exit /b 0
)
echo synthetic adb failure 1>&2
exit /b 7
'@ | Set-Content -LiteralPath $stub -Encoding ascii
    $adb=$stub;$AvdName='iptv-google-tv-16k'
    Assert-Launcher ((Find-TvEmulator) -eq 'emulator-5556') 'An offline but identifiable AVD is reused instead of starting a duplicate'
    $message=Invoke-Adb -Arguments @('stderr-success')
    Assert-Launcher ($message -match 'daemon startup message') 'Native stderr with exit code zero does not crash Windows PowerShell'
    $failed=$false
    try {Invoke-Adb -Arguments @('failure') | Out-Null} catch {$failed=$_.Exception.Message -match 'ADB fehlgeschlagen.*synthetic adb failure'}
    Assert-Launcher $failed 'An actual ADB failure is reported with its useful error message'
    Assert-Launcher ($null -eq (Invoke-Adb -Arguments @('failure') -AllowFailure)) 'Expected transient ADB failure can be retried during boot'
} finally {
    # Remove only this test's known file and its unique, now-empty temporary directory.
    Remove-Item -LiteralPath $stub -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $temporary -ErrorAction SilentlyContinue
}
Write-Output "$checks emulator launcher checks passed. No real emulator was changed by these checks."

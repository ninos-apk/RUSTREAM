param([string[]]$Tasks=@('compileDebugKotlin'),[switch]$Online)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/env.ps1"
$settingsPath=Join-Path $tvRoot 'local.properties'
$settingsBefore=[System.IO.File]::ReadAllBytes($settingsPath)
try {
    # Use the existing project SDK for this build; preserve the user's IDE setting.
    [System.IO.File]::WriteAllText($settingsPath,"sdk.dir=$($env:ANDROID_HOME.Replace('\','/').Replace(':','\:'))`n")
    $buildOptions=@('--console=plain')
    if (!$Online) { $buildOptions+='--offline' }
    & "$tvRoot/gradlew.bat" @Tasks @buildOptions
    if($LASTEXITCODE){throw 'Android build failed'}
} finally { [System.IO.File]::WriteAllBytes($settingsPath,$settingsBefore) }

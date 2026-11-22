param([string]$Apk='app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
$ErrorActionPreference='Stop'
$tvRoot=Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[System.IO.Compression.ZipFile]::OpenRead((Join-Path $tvRoot $Apk))
try {
    $assets=@($archive.Entries | Where-Object { $_.FullName -like 'assets/*' -and $_.Length -gt 0 })
    $expected = @('assets/fixture.mp4', 'assets/vod-pts-jump.mkv')
    if (Compare-Object $expected @($assets | ForEach-Object FullName)) { throw 'Test APK assets differ from the synthetic fixture allowlist' }
    Write-Host 'Test APK contains both allowlisted synthetic video assets.'
} finally { $archive.Dispose() }

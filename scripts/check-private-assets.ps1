param([string]$Apk='app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
$ErrorActionPreference='Stop'
$tvRoot=Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[System.IO.Compression.ZipFile]::OpenRead((Join-Path $tvRoot $Apk))
try {
    $assets=@($archive.Entries | Where-Object { $_.FullName -like 'assets/*' -and $_.Length -gt 0 })
    if ($assets | Where-Object FullName -ne 'assets/fixture.mp4') { throw 'Unexpected private data in test APK assets' }
    if (!($assets | Where-Object FullName -eq 'assets/fixture.mp4')) { throw 'Synthetic test fixture missing' }
    Write-Host 'Test APK contains only the synthetic video asset; private provider lists excluded.'
} finally { $archive.Dispose() }

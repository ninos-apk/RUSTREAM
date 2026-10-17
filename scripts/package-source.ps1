$ErrorActionPreference = 'Stop'
$tvRoot = Split-Path -Parent $PSScriptRoot
$stage = Join-Path $tvRoot ('.tools/source-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $stage, "$tvRoot/artifacts" | Out-Null
$files = @('Cargo.toml','Cargo.lock','rust-toolchain.toml','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','gradlew.bat','README.md','THIRD_PARTY.md','LICENSE','.gitignore','app/build.gradle.kts','app/proguard-rules.pro')
foreach ($folder in @('crates','docs','scripts','gradle','app/src')) {
    $files += Get-ChildItem -LiteralPath (Join-Path $tvRoot $folder) -File -Recurse |
        Where-Object { $_.FullName -notmatch '[\\/]jniLibs[\\/]' -and
            ($_.FullName -notmatch '[\\/]assets[\\/]' -or $_.Name -eq 'fixture.mp4' -or $_.FullName -eq (Join-Path $tvRoot 'app/src/assets/logo/logo.png')) } |
        ForEach-Object { [IO.Path]::GetRelativePath($tvRoot, $_.FullName) }
}
foreach ($relative in $files) {
    $destination = Join-Path $stage $relative
    New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
    Copy-Item -LiteralPath (Join-Path $tvRoot $relative) -Destination $destination
}
$appVersion=[regex]::Match((Get-Content -Raw "$tvRoot/app/build.gradle.kts"),'versionName\s*=\s*"([0-9.]+)"').Groups[1].Value
if (!$appVersion) { throw 'App version missing' }
Compress-Archive -Path "$stage/*" -DestinationPath "$tvRoot/artifacts/iptv-tv-source-$appVersion.zip" -Force
Copy-Item -LiteralPath "$tvRoot/artifacts/iptv-tv-source-$appVersion.zip" -Destination "$tvRoot/artifacts/RUSTREAM-IPTV-source-$appVersion.zip" -Force
Write-Host 'Source archive created without local credentials, signing keys, SDKs or build caches.'


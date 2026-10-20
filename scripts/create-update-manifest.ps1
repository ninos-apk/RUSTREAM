param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$Keystore,
    [string]$Alias = 'androiddebugkey',
    [string]$Output = 'artifacts/update.json'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
$root = Split-Path -Parent $PSScriptRoot
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$signer = "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat"
$verified = & $signer verify --print-certs $apkPath
if ($LASTEXITCODE) { throw 'APK signature verification failed' }
$metadata = & "$env:ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump badging $apkPath
if ($LASTEXITCODE) { throw 'Cannot read APK metadata' }
$package = [regex]::Match($metadata[0], "name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'")
if (!$package.Success -or $package.Groups[1].Value -cne 'de.iptv.tv') { throw 'Unexpected APK package' }
$version = $package.Groups[3].Value
$release = & "$PSScriptRoot/check-release-tag.ps1" -Tag "v$version"
if ($release.VersionCode -ne [long]$package.Groups[2].Value) { throw 'APK version code differs from source' }
& "$PSScriptRoot/generate-release-notes.ps1" -Version $version
$notes = Get-Content -Raw -Encoding utf8 "$root/docs/releases/$version.json" | ConvertFrom-Json
$fingerprint = & "$env:JAVA_HOME/bin/java.exe" "$PSScriptRoot/UpdateManifestSigner.java" fingerprint $Keystore $Alias
if ($LASTEXITCODE -or $fingerprint -cnotmatch '^[0-9a-f]{64}$') { throw 'Cannot read signing certificate' }
$apkFingerprint = [regex]::Match(($verified -join "`n"), '(?m)^Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$').Groups[1].Value.ToLowerInvariant()
if ($apkFingerprint -cne $fingerprint) { throw 'Manifest and APK must use the same signing certificate' }
$sdk = [regex]::Match(($metadata -join "`n"), "(?m)^sdkVersion:'([0-9]+)'$")
$abiLine = ($metadata | Where-Object { $_ -match '^native-code:' }) -join ''
$abis = @([regex]::Matches($abiLine, "'([^']+)'") | ForEach-Object { $_.Groups[1].Value })
if (!$sdk.Success -or $abis.Count -ne 3 -or (@($abis | Where-Object { $_ -notin @('arm64-v8a','armeabi-v7a','x86_64') })).Count) { throw 'Unexpected APK compatibility' }
$name = [IO.Path]::GetFileName($apkPath)
if ($name -cne "RUSTREAM-IPTV-PLAYER-$version.apk") { throw 'APK asset filename does not match the release contract' }
$manifest = [ordered]@{
    schema=1; packageId='de.iptv.tv'; channel='development'; tag="v$version"; versionName=$version
    versionCode=$release.VersionCode; minSdk=[int]$sdk.Groups[1].Value; abis=$abis
    apk=[ordered]@{name=$name;size=(Get-Item -LiteralPath $apkPath).Length;sha256=(Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()}
    signingCertificateSha256=$fingerprint; notes=[ordered]@{de=@($notes.de);en=@($notes.en)}
}
$outputPath = if ([IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path $root $Output }
New-Item -ItemType Directory -Force (Split-Path -Parent $outputPath) | Out-Null
[IO.File]::WriteAllText($outputPath, ($manifest | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
& "$env:JAVA_HOME/bin/java.exe" "$PSScriptRoot/UpdateManifestSigner.java" sign $Keystore $Alias $outputPath
if ($LASTEXITCODE) { throw 'Update manifest signing failed' }
Write-Output 'Update manifest generated and signature verified.'

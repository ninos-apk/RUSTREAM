param(
    [Parameter(Mandatory)][string]$Tag,
    [string]$BuildFile = (Join-Path (Split-Path -Parent $PSScriptRoot) 'app/build.gradle.kts')
)
$ErrorActionPreference = 'Stop'
if ($Tag -cnotmatch '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)\.(0|[1-9][0-9]*))?$') {
    throw 'Use a version tag such as v0.5.0 or v0.6.0-beta.1'
}
$buildText = Get-Content -Raw -LiteralPath $BuildFile
$version = [regex]::Match($buildText, '(?m)^\s*versionName\s*=\s*"([^"\r\n]+)"\s*$').Groups[1].Value
$codeText = [regex]::Match($buildText, '(?m)^\s*versionCode\s*=\s*([0-9]+)\s*$').Groups[1].Value
[int]$code = 0
if (!$version -or ![int]::TryParse($codeText, [ref]$code) -or $code -lt 1 -or $code -gt 2100000000) {
    throw 'Set a literal versionName and a valid versionCode in app/build.gradle.kts'
}
if ($Tag.Substring(1) -cne $version) {
    throw "Tag $Tag does not match versionName $version in the tagged source"
}
[pscustomobject]@{ Tag = $Tag; Version = $version; VersionCode = $code }

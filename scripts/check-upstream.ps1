$ErrorActionPreference = 'Stop'
$tvRoot = Split-Path -Parent $PSScriptRoot
$manifest = Get-Content -Raw "$tvRoot/docs/upstream-sha256.json" | ConvertFrom-Json
foreach ($entry in $manifest) {
    $modulePath = Join-Path "$tvRoot/crates/iptv-core" $entry.path
    if ((Get-FileHash -LiteralPath $modulePath -Algorithm SHA256).Hash -ne $entry.sha256) { throw "Upstream module changed: $($entry.path)" }
}
Write-Host 'Core hashes match the recorded upstream/adapted module manifest.'


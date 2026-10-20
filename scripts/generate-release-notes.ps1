param(
    [Parameter(Mandatory)][string]$Version,
    [string]$Output = 'artifacts/release-notes.md',
    [string]$PreviousTag,
    [switch]$CreateDraft
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$notesFile = Join-Path $root "docs/releases/$Version.json"
if ($CreateDraft) {
    if (Test-Path -LiteralPath $notesFile) { throw 'Release notes already exist; review the existing file.' }
    if (!$PreviousTag) { throw 'Provide -PreviousTag to explicitly select the commit range.' }
    $messages = @(& git -C $root log --format=%B "$PreviousTag..HEAD")
    if ($LASTEXITCODE) { throw 'Cannot read release commit range' }
    $de = @($messages | Where-Object { $_ -match '^Changelog-de:\s*' } | ForEach-Object { $_ -replace '^Changelog-de:\s*','' } | Select-Object -Unique)
    $en = @($messages | Where-Object { $_ -match '^Changelog-en:\s*' } | ForEach-Object { $_ -replace '^Changelog-en:\s*','' } | Select-Object -Unique)
    # Technical commit subjects are review candidates, never silently shipped as user-facing notes.
    $candidates = @(& git -C $root log --format=%s "$PreviousTag..HEAD" | Where-Object { $_ -match '^(feat|fix|perf)(\([^)]*\))?:' })
    New-Item -ItemType Directory -Force (Split-Path -Parent $notesFile) | Out-Null
    [ordered]@{ version=$Version; reviewed=$false; de=$de; en=$en; candidates=$candidates } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $notesFile -Encoding utf8
    Write-Output "Draft created: $notesFile. Review both languages and set reviewed=true before tagging."
    return
}
if (!(Test-Path -LiteralPath $notesFile)) { throw "Missing reviewed release notes: $notesFile" }
$notes = Get-Content -Raw -Encoding utf8 -LiteralPath $notesFile | ConvertFrom-Json
if ($notes.version -cne $Version -or $notes.reviewed -ne $true) { throw 'Release notes must match the version and be reviewed.' }
foreach ($language in @('de','en')) {
    $lines = @($notes.$language)
    if ($lines.Count -lt 1 -or $lines.Count -gt 12) { throw "Provide 1-12 release notes for $language" }
    foreach ($line in $lines) {
        if ($line -isnot [string] -or [string]::IsNullOrWhiteSpace($line) -or $line.Length -gt 500 -or $line -match '[\x00-\x1f\x7f]') { throw "Invalid release note in $language" }
    }
}
$body = @("# RUSTREAM IPTV PLAYER $Version", '', 'Development build. Android asks you to confirm installation.', '', '## Deutsch', '')
$body += @($notes.de | ForEach-Object { "- $_" })
$body += @('', '## Changes', '')
$body += @($notes.en | ForEach-Object { "- $_" })
$absoluteOutput = if ([IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path $root $Output }
New-Item -ItemType Directory -Force (Split-Path -Parent $absoluteOutput) | Out-Null
[IO.File]::WriteAllText($absoluteOutput, ($body -join "`n") + "`n", [Text.UTF8Encoding]::new($false))

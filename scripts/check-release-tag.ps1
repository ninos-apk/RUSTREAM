param(
    [Parameter(Mandatory)][string]$Tag,
    [string]$BuildFile,
    [switch]$RequireMain,
    [switch]$CheckHistory
)
$ErrorActionPreference = 'Stop'
if (!$BuildFile) { $BuildFile = Join-Path (Split-Path -Parent $PSScriptRoot) 'app/build.gradle.kts' }
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
if ($RequireMain) {
    & git merge-base --is-ancestor HEAD origin/main
    if ($LASTEXITCODE) { throw 'Release tags must point to a commit reachable from origin/main.' }
}
if ($CheckHistory) {
    function Compare-ReleaseVersion([string]$Left, [string]$Right) {
        $pattern = '^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?$'
        $leftMatch = [regex]::Match($Left, $pattern)
        $rightMatch = [regex]::Match($Right, $pattern)
        if (!$leftMatch.Success -or !$rightMatch.Success) { throw 'Invalid historical version' }
        foreach ($index in @(1,2,3)) {
            $comparison = ([System.Numerics.BigInteger]::Parse($leftMatch.Groups[$index].Value)).CompareTo([System.Numerics.BigInteger]::Parse($rightMatch.Groups[$index].Value))
            if ($comparison) { return $comparison }
        }
        $stages = @('alpha','beta','rc','')
        $comparison = [array]::IndexOf($stages,$leftMatch.Groups[4].Value).CompareTo([array]::IndexOf($stages,$rightMatch.Groups[4].Value))
        if ($comparison) { return $comparison }
        $leftSequence = if ($leftMatch.Groups[5].Success) { [System.Numerics.BigInteger]::Parse($leftMatch.Groups[5].Value) } else { [System.Numerics.BigInteger]::Zero }
        $rightSequence = if ($rightMatch.Groups[5].Success) { [System.Numerics.BigInteger]::Parse($rightMatch.Groups[5].Value) } else { [System.Numerics.BigInteger]::Zero }
        return $leftSequence.CompareTo($rightSequence)
    }
    $tags = @(& git tag --list 'v[0-9]*')
    if ($LASTEXITCODE) { throw 'Cannot read version history; checkout full history first.' }
    foreach ($previous in $tags) {
        if ($previous -ceq $Tag) { continue }
        if ((Compare-ReleaseVersion $version $previous.Substring(1)) -le 0) { throw "versionName must exceed every earlier tagged build ($previous)." }
        $previousSource = & git show "${previous}:app/build.gradle.kts"
        if ($LASTEXITCODE) { throw "Cannot inspect previous version $previous" }
        $previousCode = [regex]::Match(($previousSource -join "`n"), '(?m)^\s*versionCode\s*=\s*([0-9]+)\s*$').Groups[1].Value
        if (!$previousCode -or $code -le [long]$previousCode) { throw "versionCode must exceed every earlier tagged build ($previous)." }
    }
}
[pscustomobject]@{ Tag = $Tag; Version = $version; VersionCode = $code }

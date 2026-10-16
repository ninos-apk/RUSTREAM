param([string]$Playlist, [string]$Serial = 'emulator-5554', [switch]$SkipBuild)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/env.ps1"
if (!$Playlist) {
    $candidates=@(Get-ChildItem -LiteralPath "$tvRoot/app/src/androidTest/assets" -File | Where-Object Extension -in @('.m3u','.m3u8'))
    if ($candidates.Count -ne 1) { throw 'Specify exactly one private playlist using -Playlist.' }
    $Playlist=$candidates[0].FullName
}
if ($Serial -notmatch '^emulator-\d+$') { throw 'Private review currently targets disposable emulator instances.' }
New-Item -ItemType Directory -Force "$tvRoot/artifacts/playlist-review" | Out-Null
Push-Location $tvRoot
try {
    & cargo run --locked --release -p iptv-core --example playlist_audit -- $Playlist "$tvRoot/artifacts/playlist-review/host-after.json"
    if ($LASTEXITCODE) { throw 'Private playlist audit failed' }
    if (!$SkipBuild) {
        & ./gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain
        if ($LASTEXITCODE) { throw 'Review APK build failed' }
    }
    & "$PSScriptRoot/check-private-assets.ps1"
    # Credentials never enter the APK, host arguments, or review report.
    & adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE) { throw 'App install failed' }
    & adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE) { throw 'Test install failed' }
    $deviceFile='/sdcard/Android/data/de.iptv.tv/files/private-playlist.m3u'
    & adb -s $Serial shell mkdir -p /sdcard/Android/data/de.iptv.tv/files
    & adb -s $Serial push $Playlist $deviceFile
    if ($LASTEXITCODE) { throw 'Private test data transfer failed' }
    $audit=Get-Content -Raw "$tvRoot/artifacts/playlist-review/host-after.json" | ConvertFrom-Json
    $sampleRows=[System.Collections.Generic.List[object]]::new()
    $used=[System.Collections.Generic.HashSet[int]]::new()
    foreach ($bucket in @(@('de_free',3),@('de_hd',2),@('de_fhd',1),@('de_uhd',2),@('de_live',1),@('de_vod',2),@('de_series',1),@('other_live',1))) {
        $taken=0
        foreach ($id in $audit.sample_ids.($bucket[0])) {
            if ($taken -ge $bucket[1]) { break }
            if ($used.Add([int]$id)) { $sampleRows.Add(@{id=[int]$id;kind=$bucket[0]}); $taken++ }
        }
    }
    $jsonSamples=ConvertTo-Json -InputObject @($sampleRows.ToArray()) -Compress
    # Single quotes are shell quoting on Android; these DTOs contain integers and fixed labels only.
    $quotedSamples="'$jsonSamples'"
    & adb -s $Serial shell am instrument -w -e class de.iptv.tv.PrivatePlaylistReviewTest -e privatePlaylist $deviceFile -e expectedCount $audit.channels -e reviewSamples $quotedSamples de.iptv.tv.test/androidx.test.runner.AndroidJUnitRunner | Tee-Object "$tvRoot/artifacts/playlist-review/instrumentation.txt"
    & adb -s $Serial pull /sdcard/Android/data/de.iptv.tv/files/private-review.json "$tvRoot/artifacts/playlist-review/device-review.json"
    if ($LASTEXITCODE) { throw 'No private review result found' }
    $result=Get-Content -Raw "$tvRoot/artifacts/playlist-review/device-review.json" | ConvertFrom-Json
    if (!$result.complete) { throw 'Private review did not complete' }
    $result | ConvertTo-Json -Depth 8
} finally { Pop-Location }


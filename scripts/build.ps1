param([switch]$Release, [switch]$ConnectedTests)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
Push-Location $tvRoot
try {
    & cargo fmt --all -- --check
    if ($LASTEXITCODE) { throw 'Rust format check failed' }
    & cargo clippy --locked --workspace --all-targets -- -D warnings
    if ($LASTEXITCODE) { throw 'Clippy failed' }
    & cargo test --locked --workspace
    if ($LASTEXITCODE) { throw 'Rust tests failed' }
    $tasks = @('testDebugUnitTest','lintDebug','assembleDebug','assembleDebugAndroidTest')
    if ($Release) { $tasks += @('assembleRelease','bundleRelease') }
    if ($ConnectedTests) { $tasks += 'connectedDebugAndroidTest' }
    & ./gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE) { throw 'Android build/tests failed' }
    & "$PSScriptRoot/check-upstream.ps1"
    & "$PSScriptRoot/check-private-assets.ps1"
    & "$PSScriptRoot/verify-package.ps1"
    New-Item -ItemType Directory -Force artifacts | Out-Null
    Copy-Item app/build/outputs/apk/debug/app-debug.apk artifacts/iptv-tv-debug.apk
    Copy-Item app/build/outputs/apk/debug/app-debug.apk artifacts/RUSTREAM-IPTV-debug.apk
    if ($Release) {
        Copy-Item app/build/outputs/bundle/release/app-release.aab artifacts/iptv-tv-release-unsigned.aab
        Copy-Item app/build/outputs/bundle/release/app-release.aab artifacts/RUSTREAM-IPTV-release-unsigned.aab
    }
} finally { Pop-Location }


# Portable project-local tools. No system PATH or registry changes.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
. "$PSScriptRoot/env.ps1"
New-Item -ItemType Directory -Force "$tvRoot/.tools/downloads" | Out-Null
function Get-VerifiedZip($Url, $Hash, $Name, $Destination) {
    $archive = "$tvRoot/.tools/downloads/$Name.zip"
    if (!(Test-Path $archive)) { Invoke-WebRequest -Uri $Url -OutFile $archive }
    if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne $Hash) { throw "SHA256 mismatch: $Name" }
    if (!(Test-Path $Destination)) { Expand-Archive -LiteralPath $archive -DestinationPath $Destination }
}
if (!(Test-Path "$env:CARGO_HOME/bin/rustup.exe")) {
    $rustupUrl = 'https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-gnu/rustup-init.exe'
    $rustupExe = "$tvRoot/.tools/downloads/rustup-init.exe"
    $rustupChecksum = "$tvRoot/.tools/downloads/rustup-init.sha256"
    Invoke-WebRequest $rustupUrl -OutFile $rustupExe
    Invoke-WebRequest "$rustupUrl.sha256" -OutFile $rustupChecksum
    if ((Get-FileHash $rustupExe -Algorithm SHA256).Hash -ne ((Get-Content -Raw $rustupChecksum).Trim() -split '\s+')[0]) { throw 'Rustup checksum mismatch' }
    $env:RUSTUP_INIT_SKIP_PATH_CHECK = 'yes'
    & $rustupExe -y --no-modify-path --default-host x86_64-pc-windows-gnullvm --default-toolchain none --profile minimal
    if ($LASTEXITCODE) { throw 'Rustup installation failed' }
}
& rustup toolchain install 1.99.0-x86_64-pc-windows-gnullvm --profile minimal --component rustfmt --component clippy
if ($LASTEXITCODE) { throw 'Rust toolchain installation failed' }
if (!(Test-Path "$rustTools/llvm-mingw-20261006-ucrt-x86_64/bin/clang.exe")) {
    Get-VerifiedZip 'https://github.com/mstorsjo/llvm-mingw/releases/download/20261006/llvm-mingw-20261006-ucrt-x86_64.zip' '317492c456aa27ee607a5919f1d2d38dcdc1112516a24d0bf4b00d078f52d17a' 'llvm-mingw' "$tvRoot/.tools/llvm-unpack"
    Move-Item -LiteralPath "$tvRoot/.tools/llvm-unpack/llvm-mingw-20261006-ucrt-x86_64" -Destination "$rustTools/llvm-mingw-20261006-ucrt-x86_64"
}
. "$PSScriptRoot/env.ps1"
if (!(Test-Path "$env:JAVA_HOME/bin/java.exe")) {
    $package = [pscustomobject]@{
        link = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
        checksum = 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
    }
    Get-VerifiedZip $package.link $package.checksum 'jdk17' "$tvRoot/.tools/jdk-unpack"
    $jdkSource = Get-ChildItem "$tvRoot/.tools/jdk-unpack" -Directory | Select-Object -First 1
    # Both paths are fixed children of this workspace; no recursive deletion.
    Move-Item -LiteralPath $jdkSource.FullName -Destination $env:JAVA_HOME
    $package | ConvertTo-Json | Set-Content "$tvRoot/.tools/jdk-source.json"
}
if (!(Test-Path "$tvRoot/.tools/gradle-8.13/bin/gradle.bat")) {
    $checksumFile = "$tvRoot/.tools/downloads/gradle.sha256"
    Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256' -OutFile $checksumFile
    $hash = (Get-Content -Raw $checksumFile).Trim()
    Get-VerifiedZip 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' $hash 'gradle-8.13' "$tvRoot/.tools/gradle-unpack"
    Move-Item -LiteralPath "$tvRoot/.tools/gradle-unpack/gradle-8.13" -Destination "$tvRoot/.tools/gradle-8.13"
}
if (!(Test-Path "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat")) {
    Get-VerifiedZip 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a' 'android-cli' "$tvRoot/.tools/android-cli-unpack"
    New-Item -ItemType Directory -Force "$env:ANDROID_HOME/cmdline-tools" | Out-Null
    Move-Item -LiteralPath "$tvRoot/.tools/android-cli-unpack/cmdline-tools" -Destination "$env:ANDROID_HOME/cmdline-tools/latest"
}
$sdkmanager = "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat"
# Installing the user-requested Android SDK includes its SDK package licenses.
1..100 | ForEach-Object { 'y' } | & $sdkmanager "--sdk_root=$env:ANDROID_HOME" --licenses
& $sdkmanager "--sdk_root=$env:ANDROID_HOME" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358'
if ($LASTEXITCODE) { throw 'Android SDK installation failed' }
& rustup target add --toolchain 1.99.0-x86_64-pc-windows-gnullvm aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
if ($LASTEXITCODE) { throw 'Rust target installation failed' }
$sdkPath = $env:ANDROID_HOME.Replace('\','/').Replace(':','\:')
"sdk.dir=$sdkPath" | Set-Content -Encoding utf8NoBOM "$tvRoot/local.properties"
Write-Host 'Project-local Android toolchain ready.'


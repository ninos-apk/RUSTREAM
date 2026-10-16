param([string[]]$Abis = @('arm64-v8a','armeabi-v7a','x86_64'))
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
$ndkBin = "$env:ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin"
if (!(Test-Path $ndkBin)) { throw 'NDK fehlt: scripts/setup.ps1 ausführen.' }
$targets = @{
    'arm64-v8a' = @('aarch64-linux-android','aarch64-linux-android')
    'armeabi-v7a' = @('armv7-linux-androideabi','armv7a-linux-androideabi')
    'x86_64' = @('x86_64-linux-android','x86_64-linux-android')
}
Push-Location $tvRoot
try {
    foreach ($abi in $Abis) {
        if (!$targets.ContainsKey($abi)) { throw "Unsupported ABI $abi" }
        $target, $clang = $targets[$abi]
        $key = 'CARGO_TARGET_' + $target.ToUpper().Replace('-','_') + '_LINKER'
        [Environment]::SetEnvironmentVariable($key, "$ndkBin/${clang}23-clang.cmd", 'Process')
        [Environment]::SetEnvironmentVariable('CC_' + $target.Replace('-','_'), "$ndkBin/${clang}23-clang.cmd", 'Process')
        [Environment]::SetEnvironmentVariable('AR_' + $target.Replace('-','_'), "$ndkBin/llvm-ar.exe", 'Process')
        # Explicit alignment also covers Rust's precompiled standard library.
        $env:RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384'
        & cargo build --locked --release -p iptv-jni --target $target
        if ($LASTEXITCODE) { throw "Rust build failed: $target" }
        $out = "$tvRoot/app/src/main/jniLibs/$abi"
        New-Item -ItemType Directory -Force $out | Out-Null
        Copy-Item -LiteralPath "$tvRoot/target/$target/release/libiptv_jni.so" -Destination "$out/libiptv_jni.so"
    }
} finally { Pop-Location }


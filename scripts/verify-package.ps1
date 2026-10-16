param([string]$Apk = 'app/build/outputs/apk/debug/app-debug.apk')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
$apkPath = (Resolve-Path (Join-Path $tvRoot $Apk)).Path
& "$env:ANDROID_HOME/build-tools/36.0.0/zipalign.exe" -c -P 16 4 $apkPath
if ($LASTEXITCODE) { throw 'APK ZIP alignment failed' }
$readelf = "$env:ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
foreach ($abi in @('arm64-v8a','armeabi-v7a','x86_64')) {
    $native = "$tvRoot/app/src/main/jniLibs/$abi/libiptv_jni.so"
    $headers = & $readelf --program-headers --wide $native
    if ($LASTEXITCODE) { throw "ELF read failed: $abi" }
    $loads = $headers | Where-Object { $_ -match '^\s*LOAD\s' }
    if (!$loads) { throw "No ELF LOAD segments: $abi" }
    foreach ($line in $loads) {
        $alignment = ($line.Trim() -split '\s+')[-1]
        if ([Convert]::ToInt64($alignment.Replace('0x',''), 16) -lt 16384) { throw "Invalid ELF alignment: $abi $alignment" }
    }
    $symbols = & $readelf --dyn-syms $native
    foreach ($method in @('nativeCreate','nativeRequest','nativeDestroy','nativeCancel')) {
        if (!($symbols -match "Java_de_iptv_tv_NativeBridge_$method")) { throw "JNI export missing: $method $abi" }
    }
    Write-Host "$abi : ELF LOAD alignment >=16 KiB, JNI exports present"
}
Write-Host 'APK and native libraries verified.'


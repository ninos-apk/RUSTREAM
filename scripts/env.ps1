$ErrorActionPreference = 'Stop'
Remove-Item Env:\ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
$tvRoot = Split-Path -Parent $PSScriptRoot
# Hosted CI supplies Java, Rust and the Android SDK. Keep those runner-managed paths.
if ($env:GITHUB_ACTIONS -eq 'true') {
    if (!$env:ANDROID_HOME -and $env:ANDROID_SDK_ROOT) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
    if (!$env:ANDROID_HOME -or !$env:JAVA_HOME) { throw 'CI requires ANDROID_HOME and JAVA_HOME' }
    $env:ANDROID_NDK_HOME = "$env:ANDROID_HOME/ndk/28.2.13676358"
    $env:ANDROID_USER_HOME = "$tvRoot/.tools/android-user"
    $env:RUSTUP_TOOLCHAIN = '1.99.0-x86_64-pc-windows-msvc'
    $env:PATH = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:PATH"
    return
}
$windowsRoot = Join-Path (Split-Path $tvRoot) 'IPTV Player'
$rustTools = "$tvRoot/.tools"
if (Test-Path "$windowsRoot/.tools/cargo/bin/rustup.exe") { $rustTools = "$windowsRoot/.tools" }
$env:CARGO_HOME = "$rustTools/cargo"
$env:RUSTUP_HOME = "$rustTools/rustup"
$env:RUSTUP_TOOLCHAIN = '1.99.0-x86_64-pc-windows-gnullvm'
$compiler = Get-ChildItem $rustTools -Directory -Filter 'llvm-mingw-*-ucrt-x86_64' -ErrorAction SilentlyContinue | Select-Object -First 1
$env:PATH = "$env:CARGO_HOME/bin;$($compiler.FullName)/bin;$env:PATH"
$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNULLVM_LINKER = "$($compiler.FullName)/bin/x86_64-w64-mingw32-clang.exe"
$env:CC_x86_64_pc_windows_gnullvm = "$($compiler.FullName)/bin/x86_64-w64-mingw32-clang.exe"
$env:AR_x86_64_pc_windows_gnullvm = "$($compiler.FullName)/bin/llvm-ar.exe"
$env:JAVA_HOME = "$tvRoot/.tools/jdk"
# Follow Android Studio's selected JDK when available, including the AGP 9 upgrade.
$ideJavaConfig = Join-Path $tvRoot '.gradle/config.properties'
if (Test-Path -LiteralPath $ideJavaConfig) {
    $ideJavaSetting = Get-Content -LiteralPath $ideJavaConfig | Where-Object { $_ -match '^java\.home=' } | Select-Object -First 1
    if ($ideJavaSetting) {
        $ideJavaPath = $ideJavaSetting.Substring(10).Replace('\:', ':').Replace('\\', '\')
        if (Test-Path -LiteralPath (Join-Path $ideJavaPath 'bin/java.exe')) { $env:JAVA_HOME = $ideJavaPath }
    }
}
$env:ANDROID_HOME = "$tvRoot/.tools/android-sdk"
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME/ndk/28.2.13676358"
$env:GRADLE_USER_HOME = "$tvRoot/.tools/gradle-home"
$env:ANDROID_USER_HOME = "$tvRoot/.tools/android-user"
$env:ANDROID_AVD_HOME = "$tvRoot/.tools/android-user/avd"
$env:PATH = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:PATH"


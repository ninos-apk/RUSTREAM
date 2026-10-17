import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val safeTestAssets by tasks.registering(Sync::class) {
    from("src/androidTest/assets") { include("fixture.mp4") }
    into(layout.buildDirectory.dir("generated/safeTestAssets"))
}
android {
    namespace = "de.iptv.tv"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    val projectNdk=rootProject.file(".tools/android-sdk/ndk/28.2.13676358")
    if(projectNdk.isDirectory)ndkPath=projectNdk.absolutePath
    defaultConfig {
        applicationId = "de.iptv.tv"
        minSdk = 23
        targetSdk = 36
        versionCode = 9
        versionName = "0.4.1"
        val digest = MessageDigest.getInstance("SHA-256")
        rootProject.fileTree("crates").matching { include("**/*.rs", "**/Cargo.toml") }.files.sortedBy { it.path }.forEach { digest.update(it.readBytes()) }
        buildConfigField("String", "CORE_BUILD_ID", "\"" + digest.digest().joinToString("") { "%02x".format(it) }.take(16) + "\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = false } }
    // Provider lists can contain credentials. Only the synthetic video belongs in test APKs.
    sourceSets.getByName("androidTest").assets.setSrcDirs(listOf(layout.buildDirectory.dir("generated/safeTestAssets")))
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
val buildRust by tasks.registering(Exec::class) {
    workingDir(rootDir)
    commandLine("powershell.exe", "-NoProfile", "-File", "scripts/build-rust.ps1")
    inputs.files(rootProject.fileTree("crates") { include("**/*.rs", "**/Cargo.toml") }, rootProject.file("Cargo.lock"), rootProject.file("scripts/build-rust.ps1"))
    outputs.dir("src/main/jniLibs")
}
tasks.named("preBuild") { dependsOn(buildRust) }
// AGP flattens source directory providers; both packaging and lint need this dependency.
tasks.matching { it.name.contains("AndroidTest") }.configureEach { dependsOn(safeTestAssets) }
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}


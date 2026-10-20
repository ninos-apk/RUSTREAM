package de.iptv.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in fixture test. Verifies staging + the real OS confirmation callback, then abandons it. */
@RunWith(AndroidJUnit4::class)
class UpdateInstallerTest {
    @get:org.junit.Rule val remoteUi = RemoteUiRule()
    @Test fun sameKeyUpgradeReachesSystemConfirmationWithoutReplacingTheApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val fixture = File(app.filesDir, "update-fixture.apk")
        assumeTrue("Run scripts/test-update-installer.ps1 to supply a signed higher-version fixture", fixture.isFile)
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageArchiveInfo(fixture.path, flags)!!
        val version = checkNotNull(info.versionName)
        val release = UpdateRelease("v$version", version, UpdateInstaller.code(info), 23,
            Build.SUPPORTED_ABIS.toList().take(1), "RUSTREAM-IPTV-PLAYER-$version.apk", fixture.length(),
            UpdateTrust.digest(fixture.readBytes()), UpdateTrust.digest(UpdateInstaller.certificate(info).encoded), listOf("Test"), listOf("Test"))
        assertTrue(release.versionCode > UpdateInstaller.code(UpdateInstaller.installed(app)))
        UpdateInstaller.validate(app, fixture, release)
        assertTrue("Test harness grants the install source app-op", UpdateInstaller.permitted(app))
        val preferences = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val saved = preferences.all.toMap()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: UpdateController
        val pending = CountDownLatch(1)
        var confirmation: Intent? = null
        try {
            withTvActivity { scenario ->
                scenario.onTvActivity { activity ->
                    preferences.edit().putLong("lastChecked", System.currentTimeMillis()).remove("session").remove("installResult").commit()
                    controller = UpdateController(activity, scope, {}, { confirmation = it; pending.countDown() })
                    controller.start()
                }
                UpdateInstaller.install(app, fixture, release)
                assertTrue("Android requests explicit user confirmation", pending.await(15, TimeUnit.SECONDS))
                assertNotNull(confirmation)
                val id = preferences.getInt("session", -1)
                assertTrue(id >= 0)
                assertNotNull(app.packageManager.packageInstaller.getSessionInfo(id))
                assertEquals("Staging cannot silently replace the running app", BuildConfig.VERSION_CODE.toLong(), UpdateInstaller.code(UpdateInstaller.installed(app)))
                app.packageManager.packageInstaller.abandonSession(id)
            }
        } finally {
            instrumentation.runOnMainSync { UpdateController.current?.close() }
            scope.cancel(); fixture.delete()
            // This is a disposable test session; restore prior update cache/throttle without clearing app data.
            val editor = preferences.edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
            } }
            editor.commit()
        }
    }
}

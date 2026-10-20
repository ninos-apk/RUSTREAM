package de.iptv.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

object UpdateInstaller {
    @Suppress("DEPRECATION")
    private fun flags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    fun certificate(info: PackageInfo): X509Certificate {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        if (signatures?.size != 1) throw UpdateException(UpdateProblem.INVALID)
        return CertificateFactory.getInstance("X.509").generateCertificate(signatures.single().toByteArray().inputStream()) as X509Certificate
    }
    @Suppress("DEPRECATION")
    fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags())
    @Suppress("DEPRECATION")
    fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    fun validate(context: Context, file: File, release: UpdateRelease) {
        if (!file.isFile || file.length() != release.size) throw UpdateException(UpdateProblem.INVALID)
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
            val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n)
        } }
        if (hash.digest().joinToString("") { "%02x".format(it) } != release.sha256) throw UpdateException(UpdateProblem.INVALID)
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags()) ?: throw UpdateException(UpdateProblem.INVALID)
        val installed = installed(context)
        val expected = UpdateTrust.digest(certificate(installed).encoded)
        if (archive.packageName != context.packageName || archive.versionName != release.versionName ||
            code(archive) != release.versionCode || code(archive) <= code(installed) || release.certificate != expected ||
            UpdateTrust.digest(certificate(archive).encoded) != expected) throw UpdateException(UpdateProblem.INVALID)
    }

    fun permitted(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun install(context: Context, file: File, release: UpdateRelease) {
        if (!permitted(context)) throw UpdateException(UpdateProblem.PERMISSION)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName); setSize(release.size)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input -> session.openWrite("base.apk", 0, release.size).use { output ->
                    input.copyTo(output); session.fsync(output)
                } }
                val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
                check(preferences.edit().putInt("session", id).putLong("installTarget", release.versionCode)
                    .remove("installResult").commit())
                val intent = Intent(context, UpdateInstallReceiver::class.java).setAction("${context.packageName}.UPDATE_RESULT")
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val sender = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
                session.commit(sender.intentSender)
            }
        } catch (error: Exception) {
            installer.abandonSession(id)
            context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().remove("session").remove("installTarget").apply()
            throw error
        }
    }
}

/** Explicit, non-exported callback. Never launches UI while the app is in the background. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "${context.packageName}.UPDATE_RESULT") return
        val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != preferences.getInt("session", -1)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            UpdateController.confirmation = confirmation
        } else {
            preferences.edit().putInt("installResult", status).remove("session").apply()
        }
        UpdateController.current?.installerChanged()
    }
}

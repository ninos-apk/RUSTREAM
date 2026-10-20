package de.iptv.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, INSTALLING, PAUSED, ERROR }
data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val release: UpdateRelease? = null,
                       val percent: Int = 0, val problem: UpdateProblem? = null, val checked: Long = 0)

object UpdateSchedule {
    fun due(now: Long, checked: Long, automaticAfter: Long, manualAfter: Long, manual: Boolean): Boolean {
        if (now < manualAfter) return false
        if (manual) return true
        return now >= automaticAfter && (checked == 0L || now < checked || now - checked >= UpdateController.CHECK_INTERVAL)
    }
}

/** One owner for network work; private persisted files survive recreation and self replacement. */
class UpdateController(context: Context, private val scope: CoroutineScope,
    private val changed: (UpdateState) -> Unit, private val confirm: (Intent) -> Unit) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val identity = UpdateInstaller.installed(app)
    private val certificate = UpdateInstaller.certificate(identity)
    private val fingerprint = UpdateTrust.digest(certificate.encoded)
    private val http = UpdateHttp()
    private val folder = File(app.noBackupFilesDir, "updates").apply { mkdirs() }
    private val apk = File(folder, "update.apk")
    private val partial = File(folder, "update.part")
    private var work: Job? = null
    private var installWork: Job? = null
    private var ticker: Job? = null
    private var foreground = false
    private var nextAllowed = preferences.getLong("nextAllowed", 0)
    private var manualAfter = maxOf(preferences.getLong("lastAttempt", 0) + 60_000, preferences.getLong("rateUntil", 0))
    var state = UpdateState(checked = preferences.getLong("lastChecked", 0)); private set

    init {
        // Verify the original signed bytes before trusting any persisted version or notes.
        try {
            val raw = preferences.getString("manifest", null)
            val sig = preferences.getString("manifestSignature", null)
            if (raw != null && sig != null && UpdateTrust.verify(raw.toByteArray(Charsets.UTF_8), Base64.decode(sig, Base64.NO_WRAP), certificate.publicKey)) {
                val release = UpdateRelease.parse(JSONObject(raw))
                if (release.versionCode > UpdateInstaller.code(identity) && release.certificate == fingerprint &&
                    release.minSdk <= Build.VERSION.SDK_INT && release.abis.any { it in Build.SUPPORTED_ABIS })
                    state = state.copy(phase = UpdatePhase.AVAILABLE, release = release)
            }
        } catch (_: Exception) { preferences.edit().remove("manifest").remove("manifestSignature").apply() }
        if (state.release == null) {
            apk.delete(); partial.delete()
            preferences.edit().remove("manifest").remove("manifestSignature").apply()
        }
        if (preferences.getLong("installTarget", Long.MAX_VALUE) <= UpdateInstaller.code(identity)) {
            preferences.edit().remove("installTarget").remove("installResult").remove("session").apply()
        }
    }

    private fun publish(value: UpdateState) { state = value; changed(value) }
    fun start() {
        foreground = true; current = this
        publish(state); installerChanged()
        if (ticker?.isActive != true) ticker = scope.launch {
            while (isActive) { check(false); delay(60_000) }
        }
    }
    fun stop() {
        foreground = false; if (current === this) current = null
        ticker?.cancel(); ticker = null
        if (state.phase != UpdatePhase.INSTALLING) {
            work?.cancel(); http.cancel()
            if (state.phase in listOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.CHECKING))
                publish(state.copy(phase = if (state.release == null) UpdatePhase.IDLE else UpdatePhase.PAUSED))
        }
    }
    fun close() { stop(); work?.cancel(); installWork?.cancel(); http.cancel() }

    fun check(manual: Boolean) {
        if (work?.isActive == true || installWork?.isActive == true || state.phase in listOf(UpdatePhase.READY, UpdatePhase.INSTALLING)) return
        val now = System.currentTimeMillis()
        if (!UpdateSchedule.due(now, state.checked, nextAllowed, manualAfter, manual)) return
        nextAllowed = now + 60_000
        manualAfter = nextAllowed
        preferences.edit().putLong("lastAttempt", now).apply()
        val previous = work
        work = scope.launch {
            previous?.join()
            publish(state.copy(phase = UpdatePhase.CHECKING, problem = null))
            try {
                val result = withContext(Dispatchers.IO) {
                    UpdateRepository(certificate.publicKey, fingerprint, http::get,
                        { url -> preferences.getString("api:$url", null)?.let { preferences.getString("etag:$url", null) to it } },
                        { url, etag, raw -> preferences.edit().putString("api:$url", raw).putString("etag:$url", etag).apply() },
                        { bytes, signature -> preferences.edit().putString("manifest", bytes.toString(Charsets.UTF_8))
                            .putString("manifestSignature", Base64.encodeToString(signature, Base64.NO_WRAP)).apply() }
                    ).check(identity.versionName ?: "", UpdateInstaller.code(identity), Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())
                }
                val time = System.currentTimeMillis()
                preferences.edit().putLong("lastChecked", time).putInt("failures", 0).remove("nextAllowed").remove("rateUntil").apply()
                if (result == null) {
                    preferences.edit().remove("manifest").remove("manifestSignature").apply(); apk.delete()
                }
                nextAllowed = time + 60_000
                publish(UpdateState(if (result == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE, result, checked = time))
            } catch (cancel: CancellationException) { throw cancel }
              catch (error: Exception) { failed(error) }
        }
    }

    fun download() {
        val release = state.release ?: return
        if (work?.isActive == true || installWork?.isActive == true || state.phase == UpdatePhase.INSTALLING) return
        val previous = work
        work = scope.launch {
            previous?.join()
            try {
                publish(state.copy(phase = UpdatePhase.DOWNLOADING, percent = 0, problem = null))
                withContext(Dispatchers.IO) {
                    if (apk.isFile) {
                        try { UpdateInstaller.validate(app, apk, release); return@withContext }
                        catch (_: Exception) { apk.delete() }
                    }
                    if (folder.usableSpace < release.size * 3 + 16 * 1024 * 1024) throw UpdateException(UpdateProblem.STORAGE)
                    var previous = -1
                    val ioContext = coroutineContext
                    http.download(release, partial, { bytes ->
                        val percent = ((bytes * 100) / release.size).toInt()
                        if (percent != previous) { previous = percent; scope.launch {
                            if (state.phase == UpdatePhase.DOWNLOADING) publish(state.copy(percent = percent))
                        } }
                    }, { ioContext.ensureActive() })
                    withContext(Dispatchers.Main) { publish(state.copy(phase = UpdatePhase.VERIFYING)) }
                    UpdateInstaller.validate(app, partial, release)
                    if (!partial.renameTo(apk)) throw UpdateException(UpdateProblem.STORAGE)
                }
                publish(state.copy(phase = UpdatePhase.READY, percent = 100))
                install()
            } catch (cancel: CancellationException) { throw cancel }
              catch (error: Exception) { failed(error) }
        }
    }

    /** Called only after an explicit update action, or its system permission round trip. */
    fun install() {
        if (!foreground || !UpdateInstaller.permitted(app) || installWork?.isActive == true || state.phase == UpdatePhase.INSTALLING) return
        val pendingSession = preferences.getInt("session", -1)
        if (pendingSession >= 0 && app.packageManager.packageInstaller.getSessionInfo(pendingSession) != null) {
            publish(state.copy(phase = UpdatePhase.INSTALLING)); resumeInstallation(); return
        }
        val release = state.release ?: return
        installWork = scope.launch {
            try {
                publish(state.copy(phase = UpdatePhase.INSTALLING, problem = null))
                withContext(Dispatchers.IO) {
                    UpdateInstaller.validate(app, apk, release)
                    UpdateInstaller.install(app, apk, release)
                }
            } catch (cancel: CancellationException) { throw cancel }
              catch (error: Exception) { failed(error) }
        }
    }

    fun cancelDownload() {
        if (state.phase == UpdatePhase.INSTALLING) return
        work?.cancel(); http.cancel()
        publish(state.copy(phase = if (state.release == null) UpdatePhase.IDLE else UpdatePhase.PAUSED, problem = null))
    }
    fun permissionDenied() { publish(state.copy(phase = UpdatePhase.ERROR, problem = UpdateProblem.PERMISSION)) }
    fun resumeInstallation() {
        if (!foreground || installWork?.isActive == true) return
        val id = preferences.getInt("session", -1)
        if (id < 0) { publish(state.copy(phase = UpdatePhase.READY)); return }
        try { confirm(Intent(PackageInstaller.ACTION_SESSION_DETAILS).putExtra(PackageInstaller.EXTRA_SESSION_ID, id)) }
        catch (_: Exception) { failed(UpdateException(UpdateProblem.INSTALL)) }
    }
    fun installerChanged() {
        if (!foreground) return
        confirmation?.let { pending ->
            confirmation = null
            try { confirm(pending) } catch (_: Exception) { failed(UpdateException(UpdateProblem.INSTALL)) }
        }
        if (preferences.contains("installResult")) {
            val status = preferences.getInt("installResult", PackageInstaller.STATUS_FAILURE)
            preferences.edit().remove("installResult").apply()
            if (status != PackageInstaller.STATUS_SUCCESS) {
                val problem = when (status) {
                    PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateProblem.STORAGE
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateProblem.PERMISSION
                    PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateProblem.INVALID
                    else -> UpdateProblem.INSTALL
                }
                publish(state.copy(phase = UpdatePhase.ERROR, problem = problem))
            }
        }
        // A process may have died after session commit. Recover the system confirmation from its session.
        val id = preferences.getInt("session", -1)
        if (id >= 0 && state.phase != UpdatePhase.INSTALLING) {
            val info = app.packageManager.packageInstaller.getSessionInfo(id)
            if (info != null) {
                publish(state.copy(phase = UpdatePhase.INSTALLING))
                try { confirm(Intent(PackageInstaller.ACTION_SESSION_DETAILS).putExtra(PackageInstaller.EXTRA_SESSION_ID, id)) }
                catch (_: Exception) { failed(UpdateException(UpdateProblem.INSTALL)) }
            } else preferences.edit().remove("session").apply()
        }
    }
    private fun failed(error: Exception) {
        val typed = error as? UpdateException
        val problem = typed?.problem ?: if (error is java.io.IOException && folder.usableSpace < 1024 * 1024)
            UpdateProblem.STORAGE else UpdateProblem.NETWORK
        val failures = (preferences.getInt("failures", 0) + 1).coerceAtMost(6)
        nextAllowed = maxOf(System.currentTimeMillis() + (15 * 60_000L * (1L shl (failures - 1))).coerceAtMost(CHECK_INTERVAL), typed?.retryAt ?: 0)
        preferences.edit().putInt("failures", failures).putLong("nextAllowed", nextAllowed).apply()
        if (typed?.problem == UpdateProblem.RATE_LIMIT) {
            manualAfter = nextAllowed
            preferences.edit().putLong("rateUntil", manualAfter).apply()
        }
        publish(state.copy(phase = UpdatePhase.ERROR, problem = problem))
    }
    companion object {
        const val CHECK_INTERVAL = 12 * 60 * 60_000L
        internal var confirmation: Intent? = null
        private var active = java.lang.ref.WeakReference<UpdateController>(null)
        internal var current: UpdateController?
            get() = active.get()
            set(value) { active = java.lang.ref.WeakReference(value) }
    }
}

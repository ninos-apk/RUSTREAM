package de.iptv.tv

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * History, favourites and progress in the existing Keystore-sealed AtomicFile format.
 * One writer coalesces changes; nothing is written from Media3 callbacks or once per second.
 */
class UserLibraryStore(context: Context) {
    companion object {
        const val FILE = "user-library.enc"
        private const val COALESCE_MS = 2_000L
    }
    private val file = SourceStore(context, FILE)
    private val primary = File(context.noBackupFilesDir, FILE)
    private val unreadable = File(context.noBackupFilesDir, "$FILE.unreadable")
    // Not tied to the Activity scope: a pending write still completes while the Activity closes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var pending: UserLibrary.Snapshot? = null
    @Volatile var lastError: String? = null; private set
    @Volatile var writeCount = 0; private set

    init {
        scope.launch {
            for (request in requests) {
                delay(COALESCE_MS)
                write()
            }
        }
    }

    /** Restores the last complete snapshot. An unreadable file is kept aside once for diagnosis. */
    suspend fun load(into: UserLibrary) {
        val json = withContext(Dispatchers.IO) {
            try { file.load().firstOrNull() } catch (error: Exception) {
                lastError = "load_failed:${error.javaClass.simpleName}"
                runCatching { if (primary.isFile) primary.copyTo(unreadable, overwrite = true) }
                null
            }
        }
        if (json != null) try { into.restore(json) } catch (error: Exception) {
            lastError = "restore_failed:${error.javaClass.simpleName}"
        }
    }

    /** Main thread, after a change. Copying is cheap; JSON and encryption run on the writer. */
    fun schedule(library: UserLibrary) {
        pending = library.snapshot()
        requests.trySend(Unit)
    }

    /** Writes the newest state now (onStop, source removal). */
    fun flush(library: UserLibrary? = null): Job {
        if (library != null) pending = library.snapshot()
        return scope.launch { write() }
    }

    private suspend fun write() = writeLock.withLock {
        val snapshot = pending ?: return@withLock
        pending = null
        try {
            file.save(listOf(snapshot.toJson()))
            writeCount++
            lastError = null
        } catch (error: Exception) {
            // AtomicFile keeps the previous file intact; retry this state with the next change.
            if (pending == null) pending = snapshot
            lastError = "write_failed:${error.javaClass.simpleName}"
        }
    }

    fun diagnostics(library: UserLibrary): JSONObject = JSONObject().put("writes", writeCount)
        .put("last_error", lastError ?: JSONObject.NULL).put("history_limit", library.historyLimit)
}

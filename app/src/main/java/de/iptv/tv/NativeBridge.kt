package de.iptv.tv

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NativeBridge {
    init { System.loadLibrary("iptv_jni") }
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeImportProgress(handle: Long): Long
    @JvmStatic external fun nativeRequest(handle: Long, request: String): String
}

class Core : Closeable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "iptv-core") }
    @Volatile private var handle = NativeBridge.nativeCreate().also { check(it != 0L) { "Rust-Core nicht verfügbar" } }
    fun importProgress(): Long = NativeBridge.nativeImportProgress(handle)
    suspend fun call(op: String, vararg fields: Pair<String, Any?>): JSONObject = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { if (handle != 0L) NativeBridge.nativeCancel(handle) }
        executor.execute {
            try {
                check(handle != 0L)
                if (!continuation.isActive) return@execute
                val request = JSONObject().put("op", op)
                fields.forEach { (key, value) -> request.put(key, value ?: JSONObject.NULL) }
                val result = JSONObject(NativeBridge.nativeRequest(handle, request.toString()))
                if (!result.getBoolean("ok")) throw CoreException(result.getString("error"), result.optJSONObject("details"))
                if (continuation.isActive) continuation.resume(result.getJSONObject("data"))
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
    }
    override fun close() {
        // Queued work completes before destruction; no Activity or Surface enters Rust.
        if (handle != 0L) NativeBridge.nativeCancel(handle)
        executor.execute { NativeBridge.nativeDestroy(handle); handle = 0 }
        executor.shutdown()
    }
}
class CoreException(val code: String, val details: JSONObject? = null) : Exception(code)


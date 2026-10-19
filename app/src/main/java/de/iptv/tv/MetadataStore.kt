package de.iptv.tv

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Tokens, source-scoped mappings and provider text use the existing Keystore/AtomicFile store. */
class MetadataStore(context:Context) {
    private val file=SourceStore(context,"metadata.enc")
    private val settings=SourceStore(context,"metadata-settings.enc")
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val writes=Channel<Unit>(Channel.CONFLATED)
    private val writeLock=Mutex()
    val cache=MetadataCache{writes.trySend(Unit)}
    @Volatile var token:String?=null;private set
    @Volatile var saveFailed=false;private set
    private val writer=scope.launch{for(request in writes){delay(500);runCatching{persist()}}}
    /** Drain queued AtomicFile writes, then release the activity's cache and worker. */
    fun close(){writes.close();writer.invokeOnCompletion{scope.cancel()}}
    suspend fun persist()=withContext(Dispatchers.IO){writeLock.withLock{
        try{file.save(listOf(cache.json()));saveFailed=false}catch(error:Exception){saveFailed=true;throw error}
    }}
    suspend fun load()=withContext(Dispatchers.IO){
        runCatching{file.load().firstOrNull()?.let(cache::restore)}
        token=runCatching{settings.load().firstOrNull()?.clean("token")}.getOrNull()
    }
    suspend fun setToken(value:String)=withContext(Dispatchers.IO){
        val clean=value.trim();require(clean.length<=4096&&!clean.contains(Regex("\\s")))
        settings.save(listOf(JSONObject().put("token",clean)))
        token=clean.takeIf{it.isNotEmpty()}
        // Previously token-free summaries must be refreshed on the next visible pass.
        cache.restore(JSONObject().put("version",1).put("mappings",cache.json().getJSONObject("mappings")))
        writes.trySend(Unit)
    }
}

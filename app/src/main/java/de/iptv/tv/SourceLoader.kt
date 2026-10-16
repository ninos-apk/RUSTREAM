package de.iptv.tv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object BoundedCopy {
    const val LIMIT = 512L * 1024 * 1024
    fun copy(input: InputStream, output: OutputStream, limit: Long = LIMIT, checkActive: () -> Unit = {}) : Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            require(total <= limit) { "import_too_large" }
            output.write(buffer,0,read)
        }
    }
}

class SourceLoader(private val context: Context) : java.io.Closeable {
    private val client = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS).callTimeout(90,TimeUnit.SECONDS).followSslRedirects(false).build()
    private val catalogs by lazy { CatalogStore(context) }
    @Volatile var lastReport = JSONObject(); private set
    var progress: (String) -> Unit = {}
    suspend fun identity(source:JSONObject):String=withContext(Dispatchers.IO){catalogs.id(source)}
    suspend fun restore(source: JSONObject, core: Core): JSONObject? = withContext(Dispatchers.IO) {
        val started=System.nanoTime()
        val record = catalogs.entry(source) ?: return@withContext null
        core.call("open_catalog","path" to catalogs.safeFile(record.getString("file"))!!.absolutePath,"key" to record.getString("key"))
            .put("cached",true).put("updated",record.getLong("updated")).put("warnings",record.optJSONArray("warnings")?:JSONArray())
            .also{ lastReport=JSONObject().put("cached",true).put("source_kind",source.optString("kind")).put("count",it.getInt("count")).put("restore_ms",(System.nanoTime()-started)/1e6).put("updated",record.getLong("updated")).put("warnings",record.optJSONArray("warnings")?:JSONArray()) }
    }
    suspend fun shouldRefresh(source: JSONObject): Boolean = withContext(Dispatchers.IO) { catalogs.entry(source)?.let {
        val changed=source.optString("kind")=="file"&&fileSignature(source).let{current->current.length()>0&&current.toString()!=(it.optJSONObject("validators")?:JSONObject()).toString()}
        changed||System.currentTimeMillis()-it.optLong("updated") > if(it.optBoolean("partial")) 60_000L else 15*60_000L
    } ?: true }
    suspend fun remove(source: JSONObject) = withContext(Dispatchers.IO) { catalogs.remove(source) }

    suspend fun load(source: JSONObject, core: Core, epg: Boolean = false, activate: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val report = JSONObject().put("source_kind",source.optString("kind")).put("started",System.currentTimeMillis())
        val files = mutableListOf<File>()
        var target: File? = null
        var stage = "open_source"
        fun temp(): File = File.createTempFile("import-",".tmp",context.cacheDir).also { files += it }
        fun elapsed() = (System.nanoTime()-started)/1e6
        val coroutine = currentCoroutineContext()
        try {
            val temporary = temp()
            var base: String? = null
            var validators = JSONObject()
            val warnings = JSONArray()
            val previous = if(epg) null else catalogs.entry(source)
            when(source.getString("kind")) {
                "file" -> {
                    stage = "copy_file"; progress("Datei wird gelesen …")
                    val before = elapsed()
                    validators=fileSignature(source)
                    val input=context.contentResolver.openInputStream(Uri.parse(source.getString("uri"))) ?: throw ImportFailure("file_not_found",stage)
                    input.use { from -> temporary.outputStream().use { to -> report.put("bytes",BoundedCopy.copy(from,to,if(epg)128L*1024*1024 else BoundedCopy.LIMIT){coroutine.ensureActive()}) } }
                    report.put("copy_ms",elapsed()-before)
                }
                "url" -> {
                    stage = "download"; progress("Playlist wird heruntergeladen …")
                    val before=elapsed()
                    val downloaded=fetch(source.getString("url"),temporary,if(epg)128L*1024*1024 else BoundedCopy.LIMIT,previous?.optJSONObject("validators"))
                    if(downloaded.optBoolean("not_modified")) {
                        val restored=if(activate)restore(source,core) ?: throw ImportFailure("catalog_invalid","restore") else JSONObject().put("unchanged",true)
                        report.put("not_modified",true).put("total_ms",elapsed())
                        lastReport=report
                        catalogs.commit(source,catalogs.safeFile(previous!!.getString("file"))!!,previous.getString("key"),previous.optJSONObject("validators")?:JSONObject(),previous.optBoolean("partial"),previous.optJSONArray("warnings")?:JSONArray())
                        return@withContext restored.put("unchanged",true).put("diagnostics",report)
                    }
                    base=downloaded.getString("final_url")
                    validators=downloaded.getJSONObject("validators")
                    report.put("download_ms",elapsed()-before).put("bytes",temporary.length())
                }
                "xtream" -> {
                    stage="xtream_account"; progress("Anmeldung wird geprüft …")
                    val server=server(source)
                    var remaining=BoundedCopy.LIMIT
                    suspend fun api(action:String?,limit:Long=remaining):File {
                        coroutine.ensureActive()
                        val url=(server+"player_api.php").toHttpUrl().newBuilder()
                            .addQueryParameter("username",source.getString("username")).addQueryParameter("password",source.getString("password"))
                        if(action!=null)url.addQueryParameter("action",action)
                        val out=temp()
                        fetch(url.build().toString(),out,minOf(remaining,limit)); remaining-=out.length()
                        return out
                    }
                    val account=JSONObject(api(null,1024*1024).readText())
                    val user=account.optJSONObject("user_info") ?: throw ImportFailure("invalid_xtream",stage)
                    if(user.optString("auth") !in setOf("1","true")) throw ImportFailure("xtream_auth_failed",stage)
                    if(!user.optString("status","Active").equals("active",true)) throw ImportFailure("xtream_account_inactive",stage)
                    val manifest=JSONObject().put("server",server).put("username",source.getString("username"))
                        .put("password",source.getString("password")).put("account",account)
                    val before=elapsed()
                    for((field,categoryAction,streamAction) in listOf(
                        Triple("live","get_live_categories","get_live_streams"),
                        Triple("movies","get_vod_categories","get_vod_streams"),
                        Triple("series","get_series_categories","get_series"))) {
                        if(field=="movies"&&!source.optBoolean("vod",true))continue
                        stage="xtream_$field";progress(when(field){"live"->"Live-Katalog wird geladen …";"movies"->"Filmkatalog wird geladen …";else->"Serienkatalog wird geladen …"})
                        try {
                            val categories=api(categoryAction,8L*1024*1024)
                            val streams=api(streamAction)
                            manifest.put("${field}_categories",categories.absolutePath).put(field,streams.absolutePath)
                        } catch(error:Exception) {
                            if(error is kotlinx.coroutines.CancellationException)throw error
                            val detail=ImportErrors.describe(error,stage)
                            if(field=="live"||detail.http !in setOf(404,405,501))throw error
                            warnings.put(JSONObject().put("section",field).put("error",detail.json()))
                        }
                    }
                    temporary.writeText(manifest.toString())
                    report.put("download_ms",elapsed()-before).put("bytes",BoundedCopy.LIMIT-remaining)
                }
                else -> throw ImportFailure("invalid_source",stage)
            }
            coroutine.ensureActive()
            stage="parse_catalog";progress("Einträge werden verarbeitet …")
            val result:JSONObject
            if(epg) {
                result=core.call("epg_import","path" to temporary.absolutePath)
            } else {
                val file=catalogs.newFile();target=file
                val key=catalogs.newKey()
                val parsed=Core().use { worker -> worker.call("build_catalog","path" to temporary.absolutePath,"base" to base,
                    "target" to file.absolutePath,"key" to key,"xtream" to (source.getString("kind")=="xtream")) }
                report.put("parse_ms",parsed.getDouble("import_ms"))
                stage="commit_catalog";progress("Katalog wird geöffnet …")
                result=if(activate)core.call("open_catalog","path" to file.absolutePath,"key" to key)
                    .put("import_ms",parsed.getDouble("import_ms")) else parsed
                if(activate)target=null
                try { catalogs.commit(source,file,key,validators,warnings.length()>0,warnings) }
                catch(error:Exception){if(!activate)throw error;warnings.put(JSONObject().put("section","cache").put("error",ImportErrors.describe(error,"save_cache").json()))}
                target=null
            }
            report.put("total_ms",elapsed()).put("count",result.optInt("count")).put("skipped",result.optInt("skipped"))
                .put("activated",activate)
                .put("warnings",warnings).put("available_storage_mib",context.noBackupFilesDir.usableSpace/1048576)
            if(!epg)lastReport=report
            result.put("diagnostics",report).put("warnings",warnings)
        } catch(error:Exception) {
            if(error is kotlinx.coroutines.CancellationException)throw error
            val details=ImportErrors.describe(error,stage)
            if(!epg)lastReport=report.put("total_ms",elapsed()).put("failure",details.json())
            throw ImportFailure(details.code,details.stage,details.http)
        } finally {
            files.forEach { it.delete() }
            target?.let { it.delete(); File(it.absolutePath+"-journal").delete() }
        }
    }
    private fun fileSignature(source:JSONObject):JSONObject {
        val result=JSONObject()
        try{
            val uri=Uri.parse(source.getString("uri"))
            if(uri.scheme=="file"){
                val file=File(uri.path!!);if(file.isFile)result.put("size",file.length()).put("modified",file.lastModified())
            }else{
                context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.SIZE,"last_modified"),null,null,null)?.use{cursor->
                    if(cursor.moveToFirst())for(column in listOf(android.provider.OpenableColumns.SIZE,"last_modified")){
                        val index=cursor.getColumnIndex(column);if(index>=0&&!cursor.isNull(index))result.put(column,cursor.getLong(index))
                    }
                }
            }
        }catch(_:Exception){ /* Some document providers expose no stable modification metadata. */ }
        return result
    }
    private fun server(source:JSONObject):String {
        val url=source.getString("server").toHttpUrl()
        require(url.query==null && url.fragment==null && url.username.isEmpty() && url.password.isEmpty())
        var address=url.toString().trimEnd('/')
        if(address.endsWith("/player_api.php")||address.endsWith("/get.php"))address=address.substringBeforeLast('/')
        return "$address/"
    }
    suspend fun episodes(source:JSONObject,core:Core,revision:Long,id:Long,seriesId:String):JSONObject=withContext(Dispatchers.IO){
        require(seriesId.isNotEmpty()&&seriesId.all { it.isDigit() })
        val temporary=File.createTempFile("series-",".json",context.cacheDir)
        try {
            val url=(server(source)+"player_api.php").toHttpUrl().newBuilder().addQueryParameter("username",source.getString("username"))
                .addQueryParameter("password",source.getString("password")).addQueryParameter("action","get_series_info").addQueryParameter("series_id",seriesId).build()
            fetch(url.toString(),temporary,8L*1024*1024)
            core.call("resolve_series","revision" to revision,"id" to id,"path" to temporary.absolutePath)
        }finally{temporary.delete()}
    }
    private suspend fun fetch(url:String,file:File,limit:Long,validators:JSONObject?=null):JSONObject {
        val address=url.toHttpUrl()
        require(address.username.isEmpty()&&address.password.isEmpty()){ "use_query_credentials" }
        val request=Request.Builder().url(address)
        validators?.optString("etag")?.takeIf{it.isNotEmpty()}?.let{request.header("If-None-Match",it)}
        validators?.optString("last_modified")?.takeIf{it.isNotEmpty()}?.let{request.header("If-Modified-Since",it)}
        val call=client.newCall(request.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {call.cancel()}
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(e)}
                override fun onResponse(call:Call,response:Response){
                    try {
                        val result=response.use { reply ->
                            if(reply.code==304&&validators!=null)JSONObject().put("not_modified",true)
                            else {
                                if(!reply.isSuccessful)throw ImportFailure("http_${reply.code}","download",reply.code)
                                val body=reply.body ?: throw ImportFailure("empty_response","download")
                                require(body.contentLength()<=limit){"import_too_large"}
                                body.byteStream().use { from -> file.outputStream().use { to -> BoundedCopy.copy(from,to,limit){if(!continuation.isActive)throw kotlinx.coroutines.CancellationException()} } }
                                JSONObject().put("final_url",reply.request.url.toString()).put("validators",JSONObject()
                                    .put("etag",reply.header("ETag")?:"").put("last_modified",reply.header("Last-Modified")?:""))
                            }
                        }
                        if(continuation.isActive)continuation.resume(result)
                    }catch(error:Exception){if(continuation.isActive)continuation.resumeWithException(error)}
                }
            })
        }
    }
    internal fun download(url:String,file:File,limit:Long=BoundedCopy.LIMIT):String = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
        fetch(url,file,limit).getString("final_url")
    }
    override fun close(){client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
}

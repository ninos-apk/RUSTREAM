package de.iptv.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No URLs or exception messages escape this adapter. Redirects never carry credentials elsewhere. */
class MetadataHttp(private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS)
    .readTimeout(8,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {
    private val slots=Semaphore(2)
    private val pauses=java.util.concurrent.ConcurrentHashMap<String,Long>()
    suspend fun json(url:HttpUrl,token:String?=null):JSONObject = withContext(Dispatchers.IO){JSONObject(text(url,token))}
    suspend fun text(url:HttpUrl,token:String?=null,publicRedirects:Boolean=false):String = withContext(Dispatchers.IO){slots.withPermit {
        var address=url;var redirects=0;var retried=false
        repeat(6){
            val origin="${url.scheme}://${url.host}:${url.port}"
            val remaining=(pauses[origin]?:0)-System.currentTimeMillis()
            if(remaining>0)throw IOException("metadata_rate_limited")
            val request=Request.Builder().url(address).apply{if(token!=null)header("Authorization","Bearer $token")}.build()
            val response=suspendCancellableCoroutine<okhttp3.Response>{continuation->
                val call=client.newCall(request)
                continuation.invokeOnCancellation{call.cancel()}
                call.enqueue(object:okhttp3.Callback{
                    override fun onFailure(call:okhttp3.Call,e:IOException){if(!continuation.isCancelled)continuation.resumeWithException(IOException("metadata_network"))}
                    override fun onResponse(call:okhttp3.Call,response:okhttp3.Response){
                        continuation.resume(response){_,value,_->
                            call.cancel()
                            // Cancellation may be delivered on Android's main dispatcher.
                            // Response.close can drain the socket; never do that on the UI thread.
                            client.dispatcher.executorService.execute{runCatching{value.close()}}
                        }
                    }
                })
            }
            val retry=response.use{r->
                if(r.isRedirect){
                    val next=r.header("Location")?.let(address::resolve)
                    if(!publicRedirects||token!=null||next==null||next.scheme!=url.scheme||next.host!=url.host||next.port!=url.port||
                        next.username.isNotEmpty()||next.password.isNotEmpty()||++redirects>3)throw IOException("metadata_redirect")
                    address=next
                    return@use Pair(null as String?,0L as Long?)
                }
                if(r.code==429){
                    val seconds=retrySeconds(r.header("Retry-After"))
                    pauses[origin]=System.currentTimeMillis()+seconds*1000
                    return@use Pair(null as String?,seconds as Long?)
                }
                if(!r.isSuccessful)throw IOException("metadata_http_${r.code}")
                val body=r.body?:throw IOException("metadata_empty")
                if(body.contentLength()>MAX_BYTES)throw IOException("metadata_too_large")
                val bytes=ByteArrayOutputStream()
                body.byteStream().use{input->
                    val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;if(bytes.size()+n>MAX_BYTES)throw IOException("metadata_too_large");bytes.write(buffer,0,n)}
                }
                Pair(bytes.toString("UTF-8"),null as Long?)
            }
            if(retry.first!=null)return@withPermit retry.first!!
            val seconds=retry.second!!
            if(seconds==0L)return@repeat
            if(!retried&&seconds<=5){retried=true;delay(seconds*1000)} else throw IOException("metadata_rate_limited")
        }
        error("metadata_failed")
    }}
    companion object {
        const val MAX_BYTES=2*1024*1024
        internal fun retrySeconds(value:String?):Long {
            val seconds=value?.toLongOrNull()?:runCatching{
                java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().epochSecond-java.time.Instant.now().epochSecond
            }.getOrNull()?:2L
            return seconds.coerceIn(1,7*24*60*60L)
        }
    }
}

object ProviderMetadata {
    /** Parse the route, including base path/port and percent-encoded account segments. TV URLs
     * identify episodes and are deliberately never used to derive a show identifier. */
    fun movieEndpoint(address:String):HttpUrl? {
        val url=address.toHttpUrlOrNull()?:return null
        if(url.username.isNotEmpty()||url.password.isNotEmpty()||url.query!=null||url.fragment!=null)return null
        val segments=url.pathSegments;val route=segments.lastIndexOf("movie")
        if(route<0||segments.size!=route+4)return null
        val id=segments.last().substringBeforeLast('.').toLongOrNull()?.takeIf{it>0}?:return null
        val base=url.newBuilder().encodedPath("/"+url.encodedPathSegments.take(route).joinToString("/")+if(route>0)"/player_api.php" else "player_api.php")
        return base.addQueryParameter("username",segments[route+1]).addQueryParameter("password",segments[route+2])
            .addQueryParameter("action","get_vod_info").addQueryParameter("vod_id",id.toString()).build()
    }
    fun normalize(json:JSONObject):JSONObject {
        val info=json.optJSONObject("info")?:json
        val value=JSONObject()
        for(key in listOf("plot","description","genre","duration","duration_secs","releasedate","release_date","director","rating","rating_5based","tmdb_id","tmdb"))
            info.clean(key)?.let{value.put(key,it.take(30_000))}
        val people=info.optJSONArray("cast")?:info.optJSONArray("actors")?:info.optJSONObject("credits")?.optJSONArray("cast")
        if(people!=null)value.put("cast",org.json.JSONArray((0 until minOf(people.length(),100)).mapNotNull{index->
            val person=people.optJSONObject(index)?:people.optString(index).trim().takeIf{it.isNotEmpty()&&it!="null"}?.let{JSONObject().put("name",it)}?:return@mapNotNull null
            if(person.clean("name")==null)return@mapNotNull null
            JSONObject().put("id",person.positiveId("id")?:-index-2).apply{
                for(key in listOf("name","character","profile_path"))person.clean(key)?.let{put(key,it.take(1000))}
                listOf("profile_url","image","photo","picture").firstNotNullOfOrNull{person.clean(it)}?.let{put("profile_url",it.take(4096))}
            }
        })) else (info.clean("cast")?:info.clean("actors"))?.let{value.put("cast",it.take(30_000))}
        return value
    }
}

class MetadataService(val cache:MetadataCache,private val token:()->String?,private val http:MetadataHttp=MetadataHttp(),
    private val base:HttpUrl="https://api.themoviedb.org/3/".toHttpUrl(),private val clock:()->Long=System::currentTimeMillis,
    private val publicBase:HttpUrl="https://www.themoviedb.org/".toHttpUrl()) {
    // Fixed striped locks bound memory while coalescing identical requests, including grid/detail.
    private val locks=Array(32){Mutex()}
    private val tmdbLocks=Array(32){Mutex()}
    private fun lock(key:String)=locks[(key.hashCode() and Int.MAX_VALUE)%locks.size]
    private fun itemKey(item:MetadataItem,language:String)="${item.identity}:$language:v2"
    private fun sharedRating(item:MetadataItem,result:MetadataResult):MetadataResult {
        val id=result.tmdbId?:return result
        val shared=cache.get("tmdb:rating:${item.type.api}:$id")?.value?:return result
        return result.copy(tmdb=JSONObject(result.tmdb.toString()).apply{
            for(field in listOf("vote_average","vote_count"))if(shared.has(field))put(field,shared.get(field))
        })
    }
    fun peek(item:MetadataItem,language:String)=cache.get(itemKey(item,language))?.let{sharedRating(item,MetadataResult.from(it.value))}
    suspend fun load(item:MetadataItem,language:String,full:Boolean=false,input:suspend ()->JSONObject):MetadataResult = lock(item.identity).withLock {
        val key=itemKey(item,language);val stored=cache.get(key);val old=stored?.let{MetadataResult.from(it.value)}
        val ttl=if(old?.state in listOf("unmapped","ambiguous"))NEGATIVE_TTL else RATING_TTL
        if(stored!=null&&clock()-stored.at<ttl&&(!full||stored.full))return@withLock sharedRating(item,old!!)
        var provider=old?.provider?:JSONObject()
        var document=old?.tmdb?:JSONObject()
        var id=cache.overrideFor(item)
        var candidates=old?.candidates?:emptyList()
        try {
            val seed=input()
            val isShowRoot=item.type==MediaType.TV&&seed.clean("series_id")!=null
            // M3U episode attributes are never used as a series-wide rating or external ID.
            if(item.type==MediaType.MOVIE||isShowRoot){
                val attributes=seed.optJSONObject("attributes")?:JSONObject()
                provider=ProviderMetadata.normalize(attributes)
                if(!seed.optBoolean("provider_metadata")){provider.remove("rating");provider.remove("rating_5based")}
            }
            // No per-item provider details for grids: existing catalog ratings suffice.
            if(full&&item.type==MediaType.MOVIE){
                val providerKey="${item.identity}:provider:v2"
                val saved=cache.get(providerKey)
                provider=if(saved!=null&&clock()-saved.at<RATING_TTL)saved.value else {
                    val endpoint=seed.clean("url")?.let(ProviderMetadata::movieEndpoint)
                    if(endpoint!=null)try{ProviderMetadata.normalize(http.json(endpoint)).also{cache.put(providerKey,it,clock())}}
                    catch(e:CancellationException){throw e}catch(_:Exception){provider} else provider
                }
            }
            val access=token()?.takeIf{it.isNotBlank()}
            if(access==null){
                // Delivery URLs and public cast pages do not require a personal API token.
                // Only open the known movie page on the details route; never crawl the grid.
                val suggested=id?:provider.positiveId("tmdb_id")?:provider.positiveId("tmdb")
                if(full&&item.type==MediaType.MOVIE&&suggested!=null&&MetadataResult(provider,document).cast.none{Portraits.address(it)!=null}){
                    val publicKey="public:movie:$suggested"
                    val saved=cache.get(publicKey)
                    val page=if(saved!=null&&clock()-saved.at<NEGATIVE_TTL)saved.value else {
                        try{val html=http.text(publicBase.newBuilder().addPathSegments("movie/$suggested/cast").build(),publicRedirects=true)
                            withContext(Dispatchers.IO){PublicMovieCast.parse(html,suggested)}.also{cache.put(publicKey,it,clock())}}
                        catch(e:CancellationException){throw e}catch(_:Exception){JSONObject().also{cache.put(publicKey,it,clock())}}
                    }
                    if(MetadataMatch.agrees(item,page)||PublicMovieCast.overlaps(provider,page)){
                        document=JSONObject(document.toString()).put("credits",page.optJSONObject("credits"));id=suggested
                    }
                }
                val result=MetadataResult(provider,document,id,"token_missing")
                cache.put(key,result.json(),clock(),full);return@withLock result
            }
            if(id==null){
                val suggested=provider.positiveId("tmdb_id")?:provider.positiveId("tmdb")
                if(suggested!=null){
                    val proposed=try{details(item.type,suggested,language,false,access)}
                        catch(error:IOException){if(error.message=="metadata_http_404")null else throw error}
                    if(proposed!=null&&MetadataMatch.agrees(item,proposed))id=suggested
                }
                if(id==null){
                    val search=url("search/${item.type.api}").addQueryParameter("query",MetadataMatch.searchTitle(item.title))
                        .addQueryParameter("language",language).apply{item.year?.let{addQueryParameter(if(item.type==MediaType.MOVIE)"year" else "first_air_date_year",it.toString())}}.build()
                    val found=http.json(search,access)
                    candidates=MetadataMatch.candidates(item.type,found)
                    id=MetadataMatch.select(item,found)
                }
            }
            if(id==null){
                val result=MetadataResult(provider,JSONObject(),null,if(candidates.isEmpty())"unmapped" else "ambiguous",candidates)
                cache.put(key,result.json(),clock(),full);return@withLock result
            }
            document=details(item.type,id!!,language,full,access)
            // A missing translation gets an explicit English text fallback, preserving ratings/credits.
            if(full&&document.clean("overview")==null&&language!="en-US"){
                val fallback=details(item.type,id!!,"en-US",true,access)
                fallback.clean("overview")?.let{document=JSONObject(document.toString()).put("overview",it).put("overview_language","en-US")}
            }
            val result=MetadataResult(provider,document,id,"ready",candidates)
            cache.put(key,result.json(),clock(),full);result
        }catch(e:CancellationException){throw e}catch(_:Exception){
            // Stale usable values survive network/JSON/rate-limit errors. A failed attempt has
            // a short retry window rather than replacing a valid rating by a fake zero.
            val result=MetadataResult(provider,document,id?:old?.tmdbId,"error",candidates)
            cache.put(key,result.json(),clock()-RATING_TTL+60_000,full&&stored?.full==true);result
        }
    }
    private fun url(path:String)=base.newBuilder().addPathSegments(path)
    private suspend fun details(type:MediaType,id:Long,language:String,full:Boolean,access:String):JSONObject {
        val key="tmdb:${type.api}:$id:$language"
        return tmdbLocks[(key.hashCode() and Int.MAX_VALUE)%tmdbLocks.size].withLock {
            val saved=cache.get(key)
            if(saved!=null&&clock()-saved.at<RATING_TTL&&(!full||saved.full))return@withLock saved.value
            val request=url("${type.api}/$id").addQueryParameter("language",language)
                .apply{if(full&&type==MediaType.MOVIE)addQueryParameter("append_to_response","credits,release_dates,videos")}.build()
            val result=http.json(request,access)
            require(result.positiveId("id")==id){"metadata_id_mismatch"}
            cache.put("tmdb:rating:${type.api}:$id",JSONObject().apply{
                for(field in listOf("vote_average","vote_count"))if(result.has(field))put(field,result.get(field))
            },clock())
            // Never downgrade a complete credits entry when refreshing its summary.
            val merged=if(saved?.full==true&&!full)JSONObject(saved.value.toString()).apply{result.keys().forEach{k->put(k,result.get(k))}} else result
            cache.put(key,merged,clock(),full||saved?.full==true);merged
        }
    }
    suspend fun imageConfiguration():JSONObject? {
        val key="tmdb:images";val saved=cache.get(key)
        if(saved!=null&&clock()-saved.at<TEXT_TTL)return saved.value
        val access=token()?.takeIf{it.isNotBlank()}?:return null
        return try{http.json(url("configuration").build(),access).optJSONObject("images")?.also{cache.put(key,it,clock())}}
        catch(e:CancellationException){throw e}catch(_:Exception){saved?.value}
    }
    companion object {const val RATING_TTL=24*60*60_000L;const val TEXT_TTL=7*RATING_TTL;const val NEGATIVE_TTL=6*60*60_000L}
}

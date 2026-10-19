package de.iptv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

enum class MediaType(val api:String) { MOVIE("movie"), TV("tv") }
enum class RatingStatus { AVAILABLE, UNRATED, MISSING }
data class Rating(val source:String,val value:Double?,val scale:Double,val votes:Long?,val status:RatingStatus) {
    fun text(locale:Locale):String = if(status==RatingStatus.AVAILABLE) "%s %.1f/%s".format(locale,source,value,if(scale%1.0==0.0)scale.toInt().toString() else scale.toString()) else ""
    companion object {
        fun read(json:JSONObject,source:String,valueKey:String,scale:Double,voteKey:String?=null):Rating? {
            val votes=voteKey?.let{json.number(it)?.takeIf{n->n>=0&&n%1==0.0}?.toLong()}
            val value=json.number(valueKey)?.takeIf{it in 0.0..scale}
            if(votes==0L)return Rating(source,null,scale,0,RatingStatus.UNRATED)
            // A zero without a known vote count is an undocumented provider sentinel.
            if(value==null||value==0.0&&votes==null)return null
            return Rating(source,value,scale,votes,RatingStatus.AVAILABLE)
        }
    }
}
internal fun JSONObject.clean(key:String):String? = optString(key).trim().takeIf{it.isNotEmpty()&&it!="null"}
internal fun JSONObject.number(key:String):Double? = clean(key)?.toDoubleOrNull()?.takeIf{it.isFinite()}
internal fun JSONObject.positiveId(key:String):Long? = clean(key)?.toLongOrNull()?.takeIf{it>0&&it<=Int.MAX_VALUE}
internal fun JSONArray.objects() = (0 until length()).mapNotNull{optJSONObject(it)}

/** Only stable, source-scoped local keys enter navigation and persistent mapping keys. */
data class MetadataItem(val source:String,val key:String,val type:MediaType,val title:String,val year:Int?=null) {
    val identity get()="$source:${type.api}:$key"
}
data class MetadataResult(val provider:JSONObject=JSONObject(),val tmdb:JSONObject=JSONObject(),val tmdbId:Long?=null,
                          val state:String="missing",val candidates:List<MetadataCandidate> = emptyList()) {
    val ratings:List<Rating> get()=listOfNotNull(
        Rating.read(tmdb,"TMDB","vote_average",10.0,"vote_count"),
        // Xtream documents rating on ten points and rating_5based on five points.
        Rating.read(provider,"Provider","rating",10.0) ?: Rating.read(provider,"Provider","rating_5based",5.0))
    val primary get()=ratings.firstOrNull()
    val overview get()=tmdb.clean("overview") ?: provider.clean("plot") ?: provider.clean("description")
    val cast get()=tmdb.optJSONObject("credits")?.optJSONArray("cast")?.objects()?.sortedBy{it.optInt("order")}
        ?.takeIf{it.isNotEmpty()} ?: provider.optJSONArray("cast")?.objects()
        ?: provider.clean("cast")?.split(',')?.mapIndexedNotNull{index,name->name.trim().takeIf{it.isNotEmpty()}?.let{
            JSONObject().put("id",-index-2).put("name",it)
        }}.orEmpty()
    fun json()=JSONObject().put("provider",provider).put("tmdb",tmdb).put("tmdbId",tmdbId).put("state",state)
        .put("candidates",JSONArray(candidates.map{it.json()}))
    companion object {
        fun from(json:JSONObject)=MetadataResult(json.optJSONObject("provider")?:JSONObject(),json.optJSONObject("tmdb")?:JSONObject(),
            json.positiveId("tmdbId"),json.optString("state","missing"),json.optJSONArray("candidates")?.objects()?.map{MetadataCandidate.from(it)}?:emptyList())
    }
}
data class MetadataCandidate(val id:Long,val title:String,val year:Int?) {
    fun json()=JSONObject().put("id",id).put("title",title).put("year",year)
    companion object { fun from(j:JSONObject)=MetadataCandidate(j.getLong("id"),j.getString("title"),j.number("year")?.toInt()) }
}
object MetadataMatch {
    fun searchTitle(title:String)=title.replace(Regex("(?i)\\b(S\\d+\\s*E\\d+|2160p|1080p|720p|4K|UHD|FHD|HD|HEVC|H265|H264)\\b")," ")
        .replace(Regex("[\\[(][^\\])]*[\\])]")," ").replace(Regex("\\b(?:19|20)\\d{2}\\b")," ").trim()
    private fun normalized(title:String)=Normalizer.normalize(searchTitle(title),Normalizer.Form.NFD).replace(Regex("\\p{M}"),"")
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    fun agrees(item:MetadataItem,json:JSONObject):Boolean {
        val names=if(item.type==MediaType.MOVIE)listOf("title","original_title") else listOf("name","original_name")
        val year=json.clean(if(item.type==MediaType.MOVIE)"release_date" else "first_air_date")?.take(4)?.toIntOrNull()
        return names.any{json.clean(it)?.let{title->normalized(title)==normalized(item.title)}==true} && (item.year==null||year==item.year)
    }
    fun candidates(type:MediaType,json:JSONObject)=json.optJSONArray("results")?.objects()?.mapNotNull { row ->
        val id=row.positiveId("id")?:return@mapNotNull null
        MetadataCandidate(id,row.clean(if(type==MediaType.MOVIE)"title" else "name")?:return@mapNotNull null,
            row.clean(if(type==MediaType.MOVIE)"release_date" else "first_air_date")?.take(4)?.toIntOrNull())
    } ?: emptyList()
    fun select(item:MetadataItem,json:JSONObject):Long? = json.optJSONArray("results")?.objects()?.filter{agrees(item,it)}?.singleOrNull()?.positiveId("id")
}

/** Bounded persisted cache. External identities always include media type and text language. */
class MetadataCache(private val maxBytes:Int=16*1024*1024,private val changed:()->Unit={}) {
    data class Entry(val at:Long,val full:Boolean,val value:JSONObject)
    private val records=LinkedHashMap<String,Entry>(16,.75f,true)
    private val mappings=LinkedHashMap<String,Long>()
    private var bytes=0L
    private fun size(key:String,entry:Entry)=(key.length+entry.value.toString().length)*2L
    private fun prune(){while(records.size>1000||bytes>maxBytes){val first=records.entries.first();bytes-=size(first.key,first.value);records.remove(first.key)}}
    @Synchronized fun get(key:String)=records[key]
    @Synchronized fun expire(key:String){records[key]?.let{records[key]=it.copy(at=0)}}
    @Synchronized fun put(key:String,value:JSONObject,at:Long,full:Boolean=false){
        records.remove(key)?.let{bytes-=size(key,it)}
        val entry=Entry(at,full,JSONObject(value.toString()));records[key]=entry;bytes+=size(key,entry);prune();changed()
    }
    @Synchronized fun override(item:MetadataItem,id:Long?){
        if(id==null)mappings.remove(item.identity) else {require(id>0&&id<=Int.MAX_VALUE);mappings[item.identity]=id}
        records.keys.filter{it.startsWith(item.identity+":")}.forEach{key->records.remove(key)?.let{bytes-=size(key,it)}};changed()
    }
    @Synchronized fun overrideFor(item:MetadataItem)=mappings[item.identity]
    @Synchronized fun json()=JSONObject().put("version",1).put("records",JSONArray(records.map{(k,v)->JSONObject().put("key",k).put("at",v.at).put("full",v.full).put("value",v.value)}))
        .put("mappings",JSONObject(mappings as Map<*,*>))
    @Synchronized fun restore(json:JSONObject){
        if(json.optInt("version")!=1)return
        records.clear();mappings.clear();bytes=0
        json.optJSONArray("records")?.objects()?.takeLast(1000)?.forEach{j->val key=j.getString("key");val entry=Entry(j.getLong("at"),j.optBoolean("full"),j.getJSONObject("value"));records[key]=entry;bytes+=size(key,entry);prune()}
        json.optJSONObject("mappings")?.let{j->j.keys().forEach{k->j.positiveId(k)?.let{mappings[k]=it}}}
    }
}

package de.iptv.tv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.Locale

/** Public, token-free delivery; never treat a name as an image address. */
internal object Portraits {
    fun address(person:JSONObject):String? {
        for(key in listOf("profile_url","profile_path"))person.clean(key)?.toHttpUrlOrNull()
            ?.takeIf{it.username.isEmpty()&&it.password.isEmpty()}?.let{return it.toString()}
        val path=person.clean("profile_path")?.takeIf{it.matches(Regex("/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp)"))}?:return null
        return "https://image.tmdb.org/t/p/w185$path"
    }
}

/** Parse only the first cast list, excluding crew. Missing/changed HTML yields no invented people. */
internal object PublicMovieCast {
    fun parse(html:String,id:Long):JSONObject {
        val page=Jsoup.parse(html,"https://www.themoviedb.org/")
        val heading=page.select("h2 a[href]").firstOrNull{Regex("^/movie/$id(?:-|/|$)").containsMatchIn(it.attr("href"))}?:return JSONObject()
        val year=page.selectFirst("h2 .release_date")?.text()?.filter{it.isDigit()}?.takeIf{it.length==4}
        val cast=page.selectFirst("ol.people.credits")?.select("li[data-order]")?.take(100)?.mapNotNull{row->
            val link=row.selectFirst(".info a[href^=/person/]")?:return@mapNotNull null
            val personId=Regex("/person/(\\d+)").find(link.attr("href"))?.groupValues?.get(1)?.toLongOrNull()?:return@mapNotNull null
            JSONObject().put("id",personId).put("name",link.text()).put("character",row.selectFirst(".character")?.text().orEmpty())
                .put("order",row.attr("data-order").toIntOrNull()?:0).apply{
                    val src=row.selectFirst("img.profile")?.attr("src")?.toHttpUrlOrNull()
                    if(src!=null&&src.host in listOf("media.themoviedb.org","image.tmdb.org")&&src.pathSegments.take(2)==listOf("t","p")){
                        val path="/"+src.pathSegments.last();put("profile_path",path)
                        if(Portraits.address(this)==null)remove("profile_path")
                    }
                }
        }.orEmpty()
        return JSONObject().put("title",heading.text()).put("release_date",year?.let{"$it-01-01"})
            .put("credits",JSONObject().put("cast",JSONArray(cast)))
    }
    fun overlaps(provider:JSONObject,page:JSONObject):Boolean {
        fun names(cast:List<JSONObject>)=cast.mapNotNull{it.clean("name")?.lowercase(Locale.ROOT)}.toSet()
        val given=names(MetadataResult(provider).cast)
        return given.intersect(names(MetadataResult(tmdb=page).cast)).size>=2
    }
}

package de.iptv.tv

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

class MetadataTest {
    private fun publicCast(title:String="Arrival",year:Int=2016)="""<h2><a href="/movie/10-arrival">$title</a><span class="release_date">($year)</span></h2>
        <ol class="people credits"><li data-order="0"><a><img class="profile" src="https://media.themoviedb.org/t/p/w66_and_h66_face/portrait.jpg"></a>
        <div class="info"><a href="/person/99-name">Actor One</a><p class="character">First role</p></div></li></ol>"""
    @Test fun publicCastParsingKeepsIdentityRoleAndPublicPortraitButExcludesCrew(){
        val parsed=PublicMovieCast.parse(publicCast()+"<ol class='people credits'><li data-order='0'><div class='info'><a href='/person/2'>Crew</a></div></li></ol>",10)
        assertTrue(MetadataMatch.agrees(movie(),parsed))
        val cast=MetadataResult(tmdb=parsed).cast
        assertEquals(1,cast.size);assertEquals("Actor One",cast.single().getString("name"));assertEquals("First role",cast.single().getString("character"))
        assertEquals("https://image.tmdb.org/t/p/w185/portrait.jpg",Portraits.address(cast.single()))
        assertEquals(0,PublicMovieCast.parse("<html>unavailable</html>",10).length())
        assertNull(Portraits.address(JSONObject().put("profile_path","/../account")))
        assertNull(Portraits.address(JSONObject().put("profile_url","file:///private/image")))
    }
    @Test fun structuredProviderCastRetainsTokenFreePhotosAndNamesFallback(){
        val info=JSONObject().put("actors",JSONArray().put(JSONObject().put("name","Actor One").put("photo","https://images.test/actor.jpg").put("character","Role")))
        val normalized=ProviderMetadata.normalize(JSONObject().put("info",info))
        val person=MetadataResult(normalized).cast.single()
        assertEquals("Actor One",person.getString("name"));assertEquals("https://images.test/actor.jpg",Portraits.address(person))
        assertEquals(2,MetadataResult(ProviderMetadata.normalize(JSONObject().put("cast","One, Two"))).cast.size)
        assertEquals(2,MetadataResult(ProviderMetadata.normalize(JSONObject().put("cast",JSONArray(listOf("One","Two"))))).cast.size)
        assertTrue(MetadataResult(ProviderMetadata.normalize(JSONObject().put("cast",JSONArray()))).cast.isEmpty())
    }
    @Test fun tokenFreeDetailsUseKnownMoviePageWithoutAuthorizationAndCacheIt()=runBlocking {
        MockWebServer().use{server->server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/movie/10-arrival/cast"))
            server.enqueue(MockResponse().setBody(publicCast()))
            val service=MetadataService(MetadataCache(),{null},publicBase=server.url("/"))
            val result=service.load(movie(),"de-DE",true){seed()}
            assertEquals("Actor One",result.cast.single().getString("name"));assertNotNull(Portraits.address(result.cast.single()))
            val request=server.takeRequest();assertEquals("/movie/10/cast",request.path);assertNull(request.getHeader("Authorization"))
            val canonical=server.takeRequest();assertEquals("/movie/10-arrival/cast",canonical.path);assertNull(canonical.getHeader("Authorization"))
            service.load(movie("variant"),"de-DE",true){seed()};assertEquals(2,server.requestCount)
        }
    }
    @Test fun publicCastRedirectCannotLeaveItsOriginOrForwardAnApiToken()=runBlocking {
        MockWebServer().use{server->server.start();server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://other.test/cast"))
            try{MetadataHttp().text(server.url("/cast"),publicRedirects=true);fail("Cross-origin redirect must stop")}
            catch(error:java.io.IOException){assertEquals("metadata_redirect",error.message)}
            assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/canonical"))
            try{MetadataHttp().text(server.url("/cast"),"token",publicRedirects=true);fail("Authenticated redirect must stop")}
            catch(error:java.io.IOException){assertEquals("metadata_redirect",error.message)}
            assertEquals(2,server.requestCount)
        }
    }
    @Test fun wrongPublicMovieAndDeniedPageKeepProviderCastWithoutInventedPhotos()=runBlocking {
        MockWebServer().use{server->server.start();server.enqueue(MockResponse().setBody(publicCast("Wrong film",2020)))
            val service=MetadataService(MetadataCache(),{null},publicBase=server.url("/"))
            val result=service.load(movie(),"de-DE",true){seed().apply{getJSONObject("attributes").put("cast","Provider actor")}}
            assertEquals("Provider actor",result.cast.single().getString("name"));assertNull(Portraits.address(result.cast.single()))
        }
        MockWebServer().use{server->server.start();server.enqueue(MockResponse().setResponseCode(403))
            val service=MetadataService(MetadataCache(),{null},publicBase=server.url("/"))
            assertTrue(service.load(movie(),"de-DE",true){seed()}.cast.isEmpty())
            service.load(movie("other"),"de-DE",true){seed()};assertEquals(1,server.requestCount)
        }
    }
    private fun movie(key:String="local")=MetadataItem("source",key,MediaType.MOVIE,"Arrival [2016-HD]",2016)
    private fun document(id:Int=10,rating:Double=7.456)=JSONObject().put("id",id).put("title","Arrival").put("original_title","Arrival")
        .put("release_date","2016-11-01").put("vote_average",rating).put("vote_count",12).put("overview","Description")
    private fun search(vararg ids:Int)=JSONObject().put("results",JSONArray(ids.map{document(it)}))
    private fun seed()=JSONObject().put("attributes",JSONObject().put("tmdb_id",10).put("rating_5based","3.5")).put("provider_metadata",true)
    private fun MockWebServer.reply(json:JSONObject)=enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(json.toString()))

    @Test fun ratingsKeepPrecisionScaleSourceAndGermanFormatting(){
        val rating=Rating.read(document(),"TMDB","vote_average",10.0,"vote_count")!!
        assertEquals(7.456,rating.value!!,0.000001)
        assertEquals("TMDB 7,5/10",rating.text(Locale.GERMAN))
        assertEquals(12L,rating.votes)
        val provider=MetadataResult(seed().getJSONObject("attributes")).primary!!
        assertEquals(5.0,provider.scale,0.0);assertEquals(3.5,provider.value!!,0.0)
    }
    @Test fun missingInvalidAndUnratedAreDifferentFromRealZero(){
        for(value in listOf("", "null", "NaN", "Infinity", "-1", "11", "0"))assertNull(value,Rating.read(JSONObject().put("rating",value),"Provider","rating",10.0))
        val none=Rating.read(JSONObject().put("vote_average",0).put("vote_count",0),"TMDB","vote_average",10.0,"vote_count")!!
        assertEquals(RatingStatus.UNRATED,none.status);assertNull(none.value)
        val zero=Rating.read(JSONObject().put("vote_average",0).put("vote_count",3),"TMDB","vote_average",10.0,"vote_count")!!
        assertEquals(RatingStatus.AVAILABLE,zero.status);assertEquals(0.0,zero.value!!,0.0)
    }
    @Test fun mainRatingPrefersTmdbWithoutAveraging(){
        val result=MetadataResult(JSONObject().put("rating",4),document())
        assertEquals("TMDB",result.primary!!.source);assertEquals(2,result.ratings.size)
        assertEquals(7.456,result.primary!!.value!!,0.0)
    }
    @Test fun matchingRequiresUniqueTitleYearAndMediaType(){
        assertEquals(10L,MetadataMatch.select(movie(),search(10)))
        assertNull(MetadataMatch.select(movie(),search(10,11)))
        assertNull(MetadataMatch.select(movie().copy(year=2015),search(10)))
        assertNull(MetadataMatch.select(movie().copy(type=MediaType.TV),search(10)))
        assertEquals("Arrival",MetadataMatch.searchTitle("Arrival [2016-HD]"))
    }
    @Test fun movieAndTvCachesAndLanguageNeverCollide(){
        val cache=MetadataCache();cache.put("tmdb:movie:10:de-DE",document(),1);cache.put("tmdb:tv:10:de-DE",JSONObject().put("name","Show"),2)
        assertEquals("Arrival",cache.get("tmdb:movie:10:de-DE")!!.value.getString("title"))
        assertEquals("Show",cache.get("tmdb:tv:10:de-DE")!!.value.getString("name"))
        assertNull(cache.get("tmdb:movie:10:en-US"))
    }
    @Test fun manualMappingsPersistIndependentlyOfBoundedDetailsAndSource(){
        val cache=MetadataCache();cache.override(movie(),33)
        repeat(1005){cache.put("entry$it",JSONObject(),it.toLong())}
        assertNull(cache.get("entry0"));assertEquals(1000,cache.json().getJSONArray("records").length())
        val restored=MetadataCache();restored.restore(JSONObject(cache.json().toString()))
        assertEquals(33L,restored.overrideFor(movie()));assertNull(restored.overrideFor(movie().copy(source="other")))
        assertNull(restored.overrideFor(movie().copy(type=MediaType.TV)))
    }
    @Test fun byteBudgetAlsoBoundsLargeCreditDocuments(){
        val cache=MetadataCache(1500)
        repeat(10){cache.put("large$it",JSONObject().put("overview","x".repeat(300)),it.toLong())}
        assertTrue(cache.json().getJSONArray("records").length()<=2)
        assertNotNull(cache.get("large9"))
    }
    @Test fun refreshedSharedRatingUpdatesOtherLocalCardsAndLanguages()=runBlocking {
        MockWebServer().use{server->server.start();server.reply(document());server.reply(document(rating=8.7))
            val cache=MetadataCache();val service=MetadataService(cache,{"token"},base=server.url("/3/"))
            service.load(movie(),"de-DE"){seed()}
            service.load(movie("variant"),"en-US"){seed()}
            assertEquals(8.7,service.peek(movie(),"de-DE")!!.primary!!.value!!,0.0)
        }
    }
    @Test fun providerParserKeepsBasePathPortAndEncodedCredentialsButRejectsEpisodeRoute(){
        val url=ProviderMetadata.movieEndpoint("https://provider.test:8080/base/movie/u%40ser/p%2Fass/275972.mp4")!!
        assertEquals("/base/player_api.php",url.encodedPath);assertEquals(8080,url.port)
        assertEquals("u@ser",url.queryParameter("username"));assertEquals("p/ass",url.queryParameter("password"));assertEquals("275972",url.queryParameter("vod_id"))
        assertEquals("/player_api.php",ProviderMetadata.movieEndpoint("http://provider.test/movie/u/p/1.mp4")!!.encodedPath)
        assertNull(ProviderMetadata.movieEndpoint("https://provider.test/series/u/p/123.mp4"))
        assertNull(ProviderMetadata.movieEndpoint("https://provider.test/movie/u/p/x.mp4"))
        assertNull(ProviderMetadata.movieEndpoint("https://user:secret@provider.test/movie/u/p/1.mp4"))
    }
    @Test fun tokenFreeGridUsesListRatingWithoutAnyNetworkRequest()=runBlocking {
        MockWebServer().use{server->server.start()
            val service=MetadataService(MetadataCache(),{null},base=server.url("/3/"))
            val result=service.load(movie(),"de-DE"){seed().put("url",server.url("/movie/u/p/1.mp4").toString())}
            assertEquals(3.5,result.primary!!.value!!,0.0);assertEquals("token_missing",result.state);assertEquals(0,server.requestCount)
        }
    }
    @Test fun undocumentedM3uRatingAndEpisodeMetadataAreNotUsedAsShowRatings()=runBlocking {
        val service=MetadataService(MetadataCache(),{null})
        val m3u=service.load(movie(),"de-DE"){seed().put("provider_metadata",false)};assertNull(m3u.primary)
        val show=movie().copy(type=MediaType.TV)
        val series=service.load(show,"de-DE"){seed().put("url","https://host/series/u/p/3.mp4")}
        assertNull(series.tmdbId);assertNull(series.primary)
    }
    @Test fun neverOpenedGridLoadsRatingWithoutCreditsOrProviderDetailFetch()=runBlocking {
        MockWebServer().use{server->server.start();server.reply(document())
            val service=MetadataService(MetadataCache(),{"test-token"},base=server.url("/3/"))
            val result=service.load(movie(),"de-DE"){seed().put("url",server.url("/movie/u/p/1.mp4").toString())}
            assertEquals("TMDB",result.primary!!.source);assertEquals(7.456,result.primary!!.value!!,0.0)
            val request=server.takeRequest(1,TimeUnit.SECONDS)!!
            assertEquals("/3/movie/10?language=de-DE",request.path);assertEquals("Bearer test-token",request.getHeader("Authorization"));assertEquals(1,server.requestCount)
            assertEquals(result.primary,service.peek(movie(),"de-DE")!!.primary)
        }
    }
    @Test fun tvUsesWholeShowEndpointAndPreservesItsOwnIdNamespace()=runBlocking {
        MockWebServer().use{server->server.start()
            server.reply(JSONObject().put("id",10).put("name","Arrival").put("first_air_date","2016-01-01").put("vote_average",8.2).put("vote_count",10))
            val service=MetadataService(MetadataCache(),{"token"},base=server.url("/3/"))
            val result=service.load(movie().copy(type=MediaType.TV),"de-DE"){seed().put("series_id",123)}
            assertEquals(8.2,result.primary!!.value!!,0.0);assertTrue(server.takeRequest().path!!.startsWith("/3/tv/10?"))
        }
    }
    @Test fun sameRequestsAreCoalescedAndDifferentLocalItemsShareTmdbCache()=runBlocking {
        MockWebServer().use{server->server.start();server.reply(document())
            val service=MetadataService(MetadataCache(),{"token"},base=server.url("/3/"))
            (1..8).map{async(DispatchersForTests.io){service.load(movie(),"de-DE"){seed()}}}.awaitAll()
            service.load(movie("second-local"),"de-DE"){seed()}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun providerIdMismatchSearchesAndAmbiguousMatchesStayUnresolved()=runBlocking {
        MockWebServer().use{server->server.start();server.reply(document().put("title","Different").put("original_title","Different"));server.reply(search(20,21))
            val service=MetadataService(MetadataCache(),{"token"},base=server.url("/3/"))
            val result=service.load(movie(),"de-DE"){seed()}
            assertNull(result.tmdbId);assertEquals("ambiguous",result.state);assertEquals(2,result.candidates.size)
            service.load(movie(),"de-DE"){error("negative cache must be used")};assertEquals(2,server.requestCount)
        }
    }
    @Test fun deletedProviderTmdbIdFallsBackToVerifiedTitleSearch()=runBlocking {
        MockWebServer().use{server->server.start();server.enqueue(MockResponse().setResponseCode(404));server.reply(search(20));server.reply(document(20))
            val result=MetadataService(MetadataCache(),{"token"},base=server.url("/3/")).load(movie(),"de-DE"){seed()}
            assertEquals("ready",result.state);assertEquals(20L,result.tmdbId);assertEquals(3,server.requestCount)
        }
    }
    @Test fun manualCorrectionSkipsSearchAndSurvivesCacheRestore()=runBlocking {
        MockWebServer().use{server->server.start();server.reply(document(99))
            val initial=MetadataCache();initial.override(movie(),99)
            val restored=MetadataCache().also{it.restore(initial.json())}
            val service=MetadataService(restored,{"token"},base=server.url("/3/"))
            assertEquals(99L,service.load(movie(),"en-US"){seed()}.tmdbId)
            assertTrue(server.takeRequest().path!!.startsWith("/3/movie/99?"));assertEquals(1,server.requestCount)
        }
    }
    @Test fun fullDetailsAppendCreditsAndUseEnglishDescriptionFallback()=runBlocking {
        MockWebServer().use{server->server.start();server.reply(document());server.reply(document().put("overview","").put("credits",JSONObject().put("cast",JSONArray())))
            server.reply(document().put("overview","English fallback"))
            val service=MetadataService(MetadataCache(),{"token"},base=server.url("/3/"))
            val result=service.load(movie(),"de-DE",true){seed()}
            assertEquals("English fallback",result.overview);assertEquals("en-US",result.tmdb.getString("overview_language"))
            server.takeRequest();assertTrue(server.takeRequest().path!!.contains("append_to_response=credits%2Crelease_dates%2Cvideos"))
        }
    }
    @Test fun malformedJsonTimeoutAndWrongReturnedIdKeepProviderFallback()=runBlocking {
        for(response in listOf(MockResponse().setBody("{broken"),MockResponse().setBody(document(999).toString()),MockResponse().setResponseCode(503))){
            MockWebServer().use{server->server.start();server.enqueue(response)
                val service=MetadataService(MetadataCache(),{"token"},base=server.url("/3/"))
                val result=service.load(movie(),"de-DE"){seed()}
                assertEquals("error",result.state);assertEquals("Provider",result.primary!!.source);assertEquals(1,server.requestCount)
            }
        }
    }
    @Test fun oversizedResponseAndRedirectCannotLeakBearerToken()=runBlocking {
        MockWebServer().use{server->server.start();server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/stolen")))
            val service=MetadataService(MetadataCache(),{"secret-token"},base=server.url("/3/"))
            assertEquals("error",service.load(movie(),"de-DE"){seed()}.state);assertEquals(1,server.requestCount)
        }
        MockWebServer().use{server->server.start();server.enqueue(MockResponse().setBody("x".repeat(MetadataHttp.MAX_BYTES+1)))
            assertEquals("error",MetadataService(MetadataCache(),{"token"},base=server.url("/3/")).load(movie(),"de-DE"){seed()}.state)
        }
    }
    @Test fun retryAfterLongPauseDoesNotLoopOrBlockOtherPlaybackWork()=runBlocking {
        MockWebServer().use{server->server.start();server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","3600"))
            val service=MetadataService(MetadataCache(),{"token"},base=server.url("/3/"))
            assertEquals("error",service.load(movie(),"de-DE"){seed()}.state)
            assertEquals("error",service.load(movie("second"),"de-DE"){seed()}.state)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun staleTmdbRatingSurvivesFailedRefresh()=runBlocking {
        var now=1000L
        MockWebServer().use{server->server.start();server.reply(document());server.enqueue(MockResponse().setResponseCode(503))
            val service=MetadataService(MetadataCache(),{"token"},base=server.url("/3/"),clock={now})
            val old=service.load(movie(),"de-DE"){seed()};now+=MetadataService.RATING_TTL+1
            val updated=service.load(movie(),"de-DE"){seed()}
            assertEquals("error",updated.state);assertEquals(old.primary,updated.primary)
        }
    }
    @Test fun desiredFavoriteStateIsIdempotentAndIndependentOfMetadata(){
        val library=UserLibrary();var changes=0;library.listener={changes++}
        repeat(2){library.setFavorite("source",LibrarySection.MOVIES,"local","Arrival",true)}
        assertEquals(1,changes);assertEquals(1,library.favoriteCount("source",LibrarySection.MOVIES))
        val cache=MetadataCache();cache.override(movie(),99)
        assertTrue(library.isFavorite("source",LibrarySection.MOVIES,"local"))
        repeat(2){library.setFavorite("source",LibrarySection.MOVIES,"local","Arrival",false)}
        assertEquals(2,changes);assertFalse(library.isFavorite("source",LibrarySection.MOVIES,"local"))
    }
    @Test fun readTimeoutLeavesProviderRatingAvailable()=runBlocking {
        MockWebServer().use{server->server.start();server.enqueue(MockResponse().setBody(document().toString()).setBodyDelay(1,TimeUnit.SECONDS))
            val client=okhttp3.OkHttpClient.Builder().readTimeout(100,TimeUnit.MILLISECONDS).callTimeout(300,TimeUnit.MILLISECONDS).followRedirects(false).build()
            val service=MetadataService(MetadataCache(),{"token"},MetadataHttp(client),server.url("/3/"))
            val result=service.load(movie(),"de-DE"){seed()}
            assertEquals("error",result.state);assertEquals("Provider",result.primary!!.source)
        }
    }
    @Test fun shortRetryAfterIsRetriedOnlyOnce()=runBlocking {
        MockWebServer().use{server->server.start();server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","1"));server.reply(document())
            val result=MetadataService(MetadataCache(),{"token"},base=server.url("/3/")).load(movie(),"de-DE"){seed()}
            assertEquals("ready",result.state);assertEquals(2,server.requestCount)
        }
    }
    @Test fun httpParallelismIsBoundedEvenForManyVisibleItems()=runBlocking {
        MockWebServer().use{server->server.start()
            val active=java.util.concurrent.atomic.AtomicInteger();val maximum=java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse{
                val count=active.incrementAndGet();maximum.updateAndGet{maxOf(it,count)}
                Thread.sleep(40);active.decrementAndGet();return MockResponse().setBody("{}")
            }}
            val http=MetadataHttp()
            (1..10).map{async(DispatchersForTests.io){http.json(server.url("/test/$it"))}}.awaitAll()
            assertTrue(maximum.get()<=2);assertEquals(10,server.requestCount)
        }
    }
    private object DispatchersForTests {val io=kotlinx.coroutines.Dispatchers.IO}
}

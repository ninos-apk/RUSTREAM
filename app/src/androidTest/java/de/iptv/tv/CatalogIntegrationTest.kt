package de.iptv.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogIntegrationTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun commitsRetainActivePreviousGenerationAndNeverDeleteAnotherWorkerStagingFile() {
        val catalogs=CatalogStore(context)
        val source=JSONObject().put("kind","url").put("url","https://catalog.test/${java.util.UUID.randomUUID()}")
        val a=catalogs.newFile().apply{writeText("synthetic")};val b=catalogs.newFile().apply{writeText("synthetic")}
        val c=catalogs.newFile().apply{writeText("synthetic")};val staging=catalogs.newFile().apply{writeText("other worker")}
        val key=catalogs.newKey()
        try{
            catalogs.commit(source,a,key,JSONObject(),false)
            catalogs.commit(source,b,key,JSONObject(),false)
            assertTrue(a.exists());assertTrue(staging.exists())
            catalogs.commit(source,b,key,JSONObject(),false)
            assertTrue("304 must retain the active older generation",a.exists())
            catalogs.commit(source,c,key,JSONObject(),false)
            assertFalse(a.exists());assertTrue(b.exists());assertTrue(staging.exists())
        }finally{catalogs.remove(source);listOf(a,b,c,staging).forEach{it.delete()}}
    }
    @Test fun uiPagingKeepsBoundedWindowAndSupportsPreviousPages():Unit=runBlocking {
        val store=SourceStore(context);val old=store.load()
        val file=java.io.File(context.filesDir,"window-${System.nanoTime()}.m3u").apply{
            writeText(buildString{append("#EXTM3U\n");repeat(1200){append("#EXTINF:-1,Window $it\nhttps://example.test/live/u/p/$it.ts\n")}})
        }
        val source=JSONObject().put("kind","file").put("uri",android.net.Uri.fromFile(file).toString())
        try{
            store.save(listOf(source))
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use{scenario->
                var rows=0;var offset=0
                var position=""
                val deadline=android.os.SystemClock.elapsedRealtime()+15000
                while(offset==0&&android.os.SystemClock.elapsedRealtime()<deadline){
                    scenario.onActivity{activity->
                        val view=MainActivity::class.java.getDeclaredField("channels").apply{isAccessible=true}.get(activity) as androidx.recyclerview.widget.RecyclerView
                        rows=view.adapter!!.itemCount
                        if(rows>0){
                            val settings=MainActivity::class.java.getDeclaredField("settingsButton").apply{isAccessible=true}.get(activity) as android.view.View
                            settings.isFocusableInTouchMode=true;settings.requestFocus()
                            view.scrollToPosition(rows-1)
                        }
                        offset=MainActivity::class.java.getDeclaredField("offset").apply{isAccessible=true}.getInt(activity)
                        val layout=view.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                        position="offset=$offset first=${layout.findFirstVisibleItemPosition()} rows=$rows"
                    }
                    kotlinx.coroutines.delay(25)
                }
                assertTrue("Window did not advance: $position",offset>0);assertTrue(rows<=512)
                // Let the last forward prefetch/layout settle before measuring a reverse move.
                kotlinx.coroutines.delay(250)
                scenario.onActivity{activity->offset=MainActivity::class.java.getDeclaredField("offset").apply{isAccessible=true}.getInt(activity)}
                val previousOffset=offset
                scenario.onActivity{activity->
                    val view=MainActivity::class.java.getDeclaredField("channels").apply{isAccessible=true}.get(activity) as androidx.recyclerview.widget.RecyclerView
                    view.scrollToPosition(0)
                }
                val until=android.os.SystemClock.elapsedRealtime()+5000
                while(offset>=previousOffset&&android.os.SystemClock.elapsedRealtime()<until){
                    kotlinx.coroutines.delay(25)
                    scenario.onActivity{activity->
                        offset=MainActivity::class.java.getDeclaredField("offset").apply{isAccessible=true}.getInt(activity)
                        val view=MainActivity::class.java.getDeclaredField("channels").apply{isAccessible=true}.get(activity) as androidx.recyclerview.widget.RecyclerView
                        val layout=view.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                        position="offset=$offset first=${layout.findFirstVisibleItemPosition()} rows=${view.adapter!!.itemCount}"
                        if(offset>=previousOffset&&layout.findFirstVisibleItemPosition()>12)view.scrollToPosition(0)
                    }
                }
                assertTrue("Previous page did not load: start=$previousOffset $position",offset<previousOffset)
            }
        }finally{store.save(old);SourceLoader(context).use{it.remove(source)};file.delete()}
    }
    @Test fun conditionalDownloadAndOfflineCachePreserveFields():Unit=runBlocking {
        val source=JSONObject()
        SourceLoader(context).use{loader->
            FixtureServer(byteArrayOf()).use{server->
                source.put("kind","url").put("url","${server.base}/cached.m3u")
                Core().use{core->
                    val first=loader.load(source,core)
                    assertEquals(1,first.getInt("count"))
                    val next=loader.load(source,core)
                    assertTrue(next.getBoolean("unchanged"));assertEquals(1,server.conditionalRequests.get())
                    val command=core.call("select","revision" to next.getLong("revision"),"id" to 0)
                    assertEquals("${server.base}/vod.mp4",command.getString("url"))
                }
            }
            // Server is closed: restoration must not attempt any HTTP request.
            Core().use{cold->
                val restored=loader.restore(source,cold)!!
                assertTrue(restored.getBoolean("cached"));assertEquals(1,restored.getInt("count"))
            }
            loader.remove(source)
        }
    }
    @Test fun xtreamSeriesResolveOnDemandAndKeepDistinctCategories():Unit=runBlocking {
        SourceLoader(context).use{loader->
            FixtureServer(byteArrayOf(),includeSeries=true).use{server->
                val source=JSONObject().put("kind","xtream").put("server",server.base).put("username","fixture-user").put("password","fixture-pass")
                Core().use{core->
                    val imported=loader.load(source,core);val revision=imported.getLong("revision")
                    assertEquals(3,imported.getInt("count"))
                    assertEquals(3,core.call("categories").getJSONArray("categories").length())
                    assertEquals(0,core.call("episodes","revision" to revision,"id" to 2,"offset" to 0).getJSONArray("items").length())
                    val episodes=loader.episodes(source,core,revision,2,"7")
                    assertEquals(2,episodes.getJSONArray("seasons").length())
                    val episodeId=episodes.getJSONArray("items").getJSONObject(0).getLong("id")
                    val play=core.call("select","revision" to revision,"id" to episodeId)
                    assertEquals("${server.base}/series/fixture-user/fixture-pass/11.mp4",play.getString("url"))
                    assertFalse(play.getBoolean("live"))
                    assertEquals(3,core.call("categories").getInt("count"))
                }
                loader.remove(source)
            }
        }
    }
}

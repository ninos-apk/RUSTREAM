@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.os.SystemClock
import android.view.ViewGroup
import androidx.core.view.descendants
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise the public TV navigation and real Media3 using synthetic local streams. */
@RunWith(AndroidJUnit4::class)
class ContentLibraryTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private lateinit var activeActivity:MainActivity
    private fun onMain(block:(MainActivity)->Unit)=instrumentation.runOnMainSync{block(activeActivity)}
    private fun field(activity:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
    private fun waitFor(message:String,scenario:ActivityScenario<MainActivity>,test:(MainActivity)->Boolean){
        val deadline=SystemClock.elapsedRealtime()+15_000;var ok=false
        while(!ok&&SystemClock.elapsedRealtime()<deadline){onMain{ok=test(it)};if(!ok)SystemClock.sleep(40)}
        assertTrue(message,ok)
    }
    private fun pressCard(title:String){
        val card=device.wait(Until.findObject(By.desc(title)),10_000);assertNotNull(title,card)
        onMain{activity->
            val root=activity.findViewById<ViewGroup>(android.R.id.content)
            assertTrue(root.descendants.first{it.contentDescription==title}.requestFocus())
        }
        device.pressDPadCenter()
    }
    private fun assertVisible(text:String,timeout:Long=5_000){
        val visible=device.wait(Until.hasObject(By.text(text)),timeout)
        if(!visible){
            val directory=context.getExternalFilesDir(null)!!
            device.takeScreenshot(File(directory,"content-failure.png"))
            device.dumpWindowHierarchy(File(directory,"content-failure.xml"))
        }
        assertTrue("Visible: $text",visible)
    }
    @Test fun groupedM3uSeriesMoviesFullscreenAndPlaybackContext(){
        val store=SourceStore(context);val old=store.load()
        val bytes=instrumentation.context.assets.open("fixture.mp4").use{it.readBytes()}
        FixtureServer(bytes).use{server->
            val file=File(context.filesDir,"content-library-${System.nanoTime()}.m3u").apply{writeText("""
                #EXTM3U
                #EXTINF:-1 group-title=Demo,Library Live
                ${server.base}/live.ts
                #EXTINF:-1 group-title='عربي',أبواب القدر [SUB] S1 E10
                ${server.base}/series/u/p/10.mp4
                #EXTINF:-1 group-title='عربي',أبواب القدر [SUB] S1 E2 [HD]
                ${server.base}/series/u/p/2.mp4
                #EXTINF:-1 group-title='عربي',أبواب القدر [SUB] S2 E1
                ${server.base}/series/u/p/21.mp4
                #EXTINF:-1 group-title='عربي',أبواب القدر [SUB]
                ${server.base}/series/u/p/unknown.mp4
                #EXTINF:-1 group-title=Films,Film [2025-HD]
                ${server.base}/movie/u/p/1.mp4
                #EXTINF:-1 group-title=Films,Film [2025-4K]
                ${server.base}/movie/u/p/2.mp4
                #EXTINF:-1,Unclear item
                ${server.base}/unknown.mp4
            """.trimIndent())}
            try{
                store.save(listOf(JSONObject().put("kind","file").put("name","Content fixture").put("uri",android.net.Uri.fromFile(file).toString())))
                ActivityScenario.launch(MainActivity::class.java).use{scenario->
                    scenario.onActivity{activeActivity=it}
                    try{
                    assertTrue(device.wait(Until.hasObject(By.desc("Library Live").focused(true)),20_000))
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Library Live")),20_000))
                    lateinit var livePlayer:androidx.media3.exoplayer.ExoPlayer
                    onMain{livePlayer=(field(it,"playback") as PlaybackController).player!!}
                    device.findObject(By.text("Series (1)")).click()
                    pressCard("أبواب القدر [SUB]")
                    assertVisible("Staffel 1 ▾")
                    onMain{assertSame("Opening a show preserves existing playback",livePlayer,(field(it,"playback") as PlaybackController).player)}
                    pressCard("أبواب القدر [SUB] S1 E2 [HD]")
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · أبواب القدر [SUB] S1 E2 [HD]")),15_000))
                    lateinit var episodePlayer:androidx.media3.exoplayer.ExoPlayer
                    onMain{activity->
                        episodePlayer=(field(activity,"playback") as PlaybackController).player!!
                        val pane=field(activity,"playerPane") as PlayerPane;pane.showControls(true)
                        assertTrue(pane.buttons.values.first{it.contentDescription=="Vollbild"}.requestFocus())
                    }
                    device.pressDPadCenter()
                    waitFor("Entered fullscreen",scenario){field(it,"fullScreen")==true}
                    device.pressBack()
                    waitFor("Fullscreen restored without reload",scenario){field(it,"fullScreen")==false&&(field(it,"playback") as PlaybackController).player===episodePlayer}
                    assertTrue(device.hasObject(By.text("Staffel 1 ▾")))
                    // Browse another section while the episode keeps playing, then request next episode.
                    device.findObject(By.text("Movies (2)")).click()
                    waitFor("Movie grid",scenario){(field(it,"channels") as RecyclerView).layoutManager is GridLayoutManager}
                    onMain{assertEquals(android.view.View.GONE,(field(it,"mediaColumn") as android.view.View).visibility)}
                    device.pressKeyCode(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
                    waitFor("Playback context remains the episode list while player is hidden",scenario){(field(it,"status") as android.widget.TextView).text.toString()=="Wiedergabe · أبواب القدر [SUB] S1 E10"}
                    lateinit var previousPlayer:androidx.media3.exoplayer.ExoPlayer
                    onMain{previousPlayer=(field(it,"playback") as PlaybackController).player!!}
                    pressCard("Film [2025-4K]")
                    assertVisible("Abspielen")
                    onMain{assertSame("Opening a movie does not start playback",previousPlayer,(field(it,"playback") as PlaybackController).player)}
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Film [2025-4K]")),15_000))
                    device.pressBack()
                    assertTrue(device.wait(Until.hasObject(By.desc("Film [2025-4K]").focused(true)),5_000))
                    onMain{assertEquals(android.view.View.GONE,(field(it,"mediaColumn") as android.view.View).visibility)}
                    device.findObject(By.text("Series (1)")).click();pressCard("أبواب القدر [SUB]")
                    assertVisible("Staffel 1 ▾")
                    device.findObject(By.text("Staffel 1 ▾")).click()
                    device.findObject(By.text("Staffel 2 (1)")).click()
                    pressCard("أبواب القدر [SUB] S2 E1")
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · أبواب القدر [SUB] S2 E1")),15_000))
                    device.findObject(By.text("← Serienbibliothek")).click()
                    assertTrue(device.wait(Until.hasObject(By.desc("أبواب القدر [SUB]").focused(true)),5_000))
                    pressCard("أبواب القدر [SUB]")
                    pressCard("Alle Kategorien")
                    assertTrue(device.wait(Until.hasObject(By.desc("أبواب القدر [SUB]")),5_000))
                    assertFalse(device.hasObject(By.text("← Serienbibliothek")))
                    device.findObject(By.text("Unzugeordnet (1)")).click()
                    pressCard("Unclear item")
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Unclear item")),15_000))
                    device.executeShellCommand("screencap -p /sdcard/Download/content-library.png")
                    }finally{finishTvActivity(activeActivity)}
                }
            }finally{store.save(old);file.delete()}
        }
    }
    @Test fun xtreamUsesTheSameSeriesViewAndLoadsEpisodesOnDemand(){
        val store=SourceStore(context);val old=store.load()
        val bytes=instrumentation.context.assets.open("fixture.mp4").use{it.readBytes()}
        FixtureServer(bytes,includeSeries=true).use{server->try{
            store.save(listOf(JSONObject().put("kind","xtream").put("name","Series fixture").put("server",server.base).put("username","fixture-user").put("password","fixture-pass").put("vod",true)))
            ActivityScenario.launch(MainActivity::class.java).use{scenario->
                scenario.onActivity{activeActivity=it}
                try{
                assertTrue(device.wait(Until.hasObject(By.text("Series (1)")),20_000))
                device.findObject(By.text("Series (1)")).click();pressCard("Synthetic show")
                assertVisible("Staffel 1 ▾",10_000)
                assertEquals("Opening series does not request a media stream",0,server.mediaRequests.get())
                pressCard("First episode")
                assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · First episode")),15_000))
                }finally{finishTvActivity(activeActivity)}
            }
        }finally{store.save(old)}}
    }
}

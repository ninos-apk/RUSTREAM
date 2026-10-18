@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.core.view.descendants
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

/** History, favourites and resume with real Media3 playback, a real restart and remote keys. */
@RunWith(AndroidJUnit4::class)
class UserLibraryUiTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private lateinit var activeActivity:MainActivity
    private fun onMain(block:(MainActivity)->Unit)=instrumentation.runOnMainSync{block(activeActivity)}
    private fun field(activity:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
    private fun library(activity:MainActivity)=field(activity,"userLibrary") as UserLibrary
    private fun source(activity:MainActivity)=field(activity,"activeSourceId") as String?
    private fun playback(activity:MainActivity)=field(activity,"playback") as PlaybackController
    private fun waitFor(message:String,timeout:Long=15_000,test:(MainActivity)->Boolean){
        val deadline=SystemClock.elapsedRealtime()+timeout;var ok=false
        while(!ok&&SystemClock.elapsedRealtime()<deadline){onMain{ok=test(it)};if(!ok)SystemClock.sleep(50)}
        if(!ok)failure("user-library")
        assertTrue(message,ok)
    }
    private fun failure(name:String){
        val directory=context.getExternalFilesDir(null)!!
        device.takeScreenshot(File(directory,"$name-failure.png"));device.dumpWindowHierarchy(File(directory,"$name-failure.xml"))
    }
    private fun focus(description:String){
        assertNotNull(description,device.wait(Until.findObject(By.desc(description)),10_000))
        onMain{activity->
            val root=activity.findViewById<ViewGroup>(android.R.id.content)
            assertTrue(root.descendants.first{it.contentDescription==description&&it.isShown}.requestFocus())
        }
    }
    private fun pressCard(description:String){focus(description);device.pressDPadCenter()}
    private fun click(text:String){
        val target=device.wait(Until.findObject(By.text(text)),10_000)
        if(target==null)failure("user-library-click")
        assertNotNull("Visible: $text",target);target.click()
    }
    /** A real remote long press: the repeated DOWN carries FLAG_LONG_PRESS. */
    private fun longPressCenter(){
        val down=SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,0))
        SystemClock.sleep(550)
        instrumentation.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,1,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_LONG_PRESS))
        instrumentation.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0))
    }
    private fun waitPlaying(title:String){
        val playing=device.wait(Until.hasObject(By.text("Wiedergabe · $title")),20_000)
        if(!playing)failure("user-library-play")
        assertTrue("Playing $title",playing)
    }

    @Test fun historyFavouritesAndResumeSurviveARestart(){
        val store=SourceStore(context);val old=store.load()
        val userFile=File(context.noBackupFilesDir,UserLibraryStore.FILE)
        userFile.delete();File(userFile.path+".bak").delete()
        val bytes=instrumentation.context.assets.open("fixture.mp4").use{it.readBytes()}
        FixtureServer(bytes).use{server->
            val playlist=File(context.filesDir,"user-library-${System.nanoTime()}.m3u").apply{writeText("""
                #EXTM3U
                #EXTINF:-1 group-title=News,Recent Live
                ${server.base}/live.ts
                #EXTINF:-1 group-title=News,Other Live
                ${server.base}/live2.ts
                #EXTINF:-1 group-title=Films,Resume Film
                ${server.base}/movie/u/p/1.mp4
                #EXTINF:-1 group-title=Shows,Demo Show S1 E1
                ${server.base}/series/u/p/1.mp4
                #EXTINF:-1 group-title=Shows,Demo Show S1 E2
                ${server.base}/series/u/p/2.mp4
            """.trimIndent())}
            try{
                store.save(listOf(JSONObject().put("kind","file").put("name","History fixture").put("uri",android.net.Uri.fromFile(playlist).toString())))
                ActivityScenario.launch(MainActivity::class.java).use{scenario->
                    scenario.onActivity{activeActivity=it}
                    try{
                        // Empty lists explain themselves; there is no placeholder content.
                        val started=device.wait(Until.hasObject(By.desc("Recent Live").focused(true)),20_000)
                        if(!started)failure("user-library-start")
                        assertTrue("First channel focused after start",started)
                        pressCard("Zuletzt gesehen (0)")
                        assertTrue(device.wait(Until.hasObject(By.textStartsWith("Noch nichts angesehen")),5_000))
                        pressCard("Alle Kategorien")
                        // Live: only the channel, no position.
                        pressCard("Recent Live")
                        waitPlaying("Recent Live")
                        waitFor("Live channel remembered"){a->library(a).recent(source(a)!!,LibrarySection.LIVE).map{it.name}==listOf("Recent Live")}
                        SystemClock.sleep(1_500)
                        onMain{a->
                            val key=library(a).recent(source(a)!!,LibrarySection.LIVE)[0].key
                            assertNull("Live never stores a position",library(a).progress(source(a)!!,key))
                        }
                        // Favourite a channel with a long OK press on its row.
                        focus("Recent Live");longPressCenter()
                        click("☆ Zu Favoriten hinzufügen")
                        waitFor("Live favourite"){a->library(a).favoriteCount(source(a)!!,LibrarySection.LIVE)==1}
                        assertTrue(device.wait(Until.hasObject(By.text("★ Favoriten (1)")),5_000))

                        // Movie: details do not count as watched; playback does, with its position.
                        click("Movies (1)")
                        pressCard("Resume Film")
                        assertTrue(device.wait(Until.hasObject(By.text("Abspielen")),5_000))
                        onMain{a->assertEquals("Opening details is not history",0,library(a).recentCount(source(a)!!,LibrarySection.MOVIES))}
                        click("☆ Zu Favoriten hinzufügen")
                        assertTrue(device.wait(Until.hasObject(By.text("★ Aus Favoriten entfernen")),5_000))
                        click("Abspielen")
                        waitPlaying("Resume Film")
                        waitFor("Movie playing"){a->playback(a).player?.isPlaying==true}
                        onMain{a->playback(a).player!!.seekTo(14_000)}
                        waitFor("Position after the seek"){a->(playback(a).player?.currentPosition?:0)>=14_000}
                        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
                        waitFor("Pause stores the position"){a->
                            val key=library(a).recent(source(a)!!,LibrarySection.MOVIES).firstOrNull()?.key
                            key!=null&&(library(a).progress(source(a)!!,key)?.positionSec?:0.0)>=14.0
                        }

                        // Series: one entry for the show, the last episode as its fields.
                        device.pressBack()
                        click("Series (1)")
                        pressCard("Demo Show")
                        assertTrue(device.wait(Until.hasObject(By.text("Staffel 1 ▾")),10_000))
                        pressCard("Demo Show S1 E1");waitPlaying("Demo Show S1 E1")
                        waitFor("First episode remembered"){a->library(a).recent(source(a)!!,LibrarySection.SERIES).firstOrNull()?.episode==1L}
                        pressCard("Demo Show S1 E2");waitPlaying("Demo Show S1 E2")
                        waitFor("Show stays one entry with episode 2"){a->
                            val recent=library(a).recent(source(a)!!,LibrarySection.SERIES)
                            recent.size==1&&recent[0].name=="Demo Show"&&recent[0].episode==2L&&recent[0].season==1L
                        }
                        onMain{playback(it).player!!.seekTo(12_000)}
                        waitFor("Episode seek completes"){(playback(it).player?.currentPosition?:0)>=12_000}
                        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
                        waitFor("Episode pause stores its resume position"){a->
                            val last=library(a).recent(source(a)!!,LibrarySection.SERIES).first()
                            (library(a).progress(source(a)!!,last.episodeKey!!)?.positionSec?:0.0)>=12.0
                        }
                        click("☆")
                        waitFor("Show favourite, never an episode"){a->
                            val favorites=library(a).favorites(source(a)!!,LibrarySection.SERIES)
                            favorites.size==1&&favorites[0].key.startsWith("s:")
                        }
                        device.executeShellCommand("screencap -p /sdcard/Download/user-library-series.png")
                        lateinit var pausedEpisodePlayer:androidx.media3.exoplayer.ExoPlayer
                        onMain{pausedEpisodePlayer=playback(it).player!!}
                        pressCard("Demo Show S1 E2")
                        waitFor("Clicking the already paused episode resumes without reloading"){a->
                            playback(a).player===pausedEpisodePlayer&&pausedEpisodePlayer.isPlaying&&field(a,"fullScreen")==true
                        }
                        device.pressBack()
                        waitFor("Preview restored after resuming the episode"){field(it,"fullScreen")==false}
                        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
                        waitFor("Episode paused again before restart"){playback(it).player?.playWhenReady==false}
                    }finally{finishTvActivity(activeActivity)}
                }
                // finishTvActivity destroys the Activity; the store writes outside its scope.
                val deadline=SystemClock.elapsedRealtime()+10_000
                while(!userFile.isFile&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50)
                SystemClock.sleep(500)
                assertTrue("History is written without waiting for app exit",userFile.isFile)

                // A new Activity reads the file again: nothing is shared in memory.
                ActivityScenario.launch(MainActivity::class.java).use{scenario->
                    scenario.onActivity{activeActivity=it}
                    try{
                        waitFor("Restored after restart",20_000){a->
                            val source=source(a)
                            source!=null&&library(a).recentCount(source,LibrarySection.LIVE)==1&&library(a).recentCount(source,LibrarySection.MOVIES)==1&&
                                library(a).recentCount(source,LibrarySection.SERIES)==1&&library(a).favoriteCount(source,LibrarySection.LIVE)==1&&
                                library(a).favoriteCount(source,LibrarySection.MOVIES)==1&&library(a).favoriteCount(source,LibrarySection.SERIES)==1
                        }
                        click("Movies (1)")
                        pressCard("Zuletzt gesehen (1)")
                        assertTrue("Card shows the progress",device.wait(Until.hasObject(By.textStartsWith("0:1")),10_000))
                        device.executeShellCommand("screencap -p /sdcard/Download/user-library-recent-movies.png")
                        pressCard("Resume Film")
                        assertTrue(device.wait(Until.hasObject(By.textStartsWith("Fortsetzen bei 0:1")),5_000))
                        assertTrue(device.hasObject(By.text("Von Anfang an")))
                        device.pressDPadCenter()
                        waitPlaying("Resume Film")
                        waitFor("Resumed at the saved position"){a->(playback(a).player?.currentPosition?:0)>=12_000}

                        // Removing the favourite (still in the details) keeps history and progress.
                        click("★ Aus Favoriten entfernen")
                        waitFor("Favourite removed, progress kept"){a->
                            val source=source(a)!!
                            val key=library(a).recent(source,LibrarySection.MOVIES)[0].key
                            library(a).favoriteCount(source,LibrarySection.MOVIES)==0&&library(a).progress(source,key)!=null&&library(a).recentCount(source,LibrarySection.MOVIES)==1
                        }
                        // The episode row replaces the redundant header Resume action.
                        device.pressBack()
                        click("Series (1)")
                        pressCard("Demo Show")
                        waitFor("The paused episode is highlighted with its saved time"){a->
                            val row=a.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<ViewGroup>().firstOrNull{it.contentDescription=="Demo Show S1 E2"}
                            row?.isActivated==true&&row.descendants.filterIsInstance<android.widget.TextView>().any{it.isShown&&it.text.toString().startsWith("0:1")}
                        }
                        device.executeShellCommand("screencap -p /sdcard/Download/user-library-series-resume.png")
                        pressCard("Demo Show S1 E2")
                        waitFor("Selecting the episode resumes its saved position"){playback(it).title=="Demo Show S1 E2"&&(playback(it).player?.currentPosition?:0)>=12_000}
                        pressCard("★ Favoriten (1)")
                        assertTrue(device.wait(Until.hasObject(By.desc("Demo Show")),5_000))
                    }finally{finishTvActivity(activeActivity)}
                }
            }finally{store.save(old);playlist.delete();userFile.delete()}
        }
    }
}

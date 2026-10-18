@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.descendants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SeriesPlaybackControlsTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private fun field(activity:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
    private fun pane(activity:MainActivity)=field(activity,"playerPane") as PlayerPane
    private fun playback(activity:MainActivity)=field(activity,"playback") as PlaybackController
    private fun episode(activity:MainActivity)=(field(activity,"tracker") as WatchTracker).current?.episode

    @Test fun episodeControlsFollowPlaybackAndRespectSeasonBoundaries(){
        val store=SourceStore(context);val old=store.load()
        val oldScale=device.executeShellCommand("settings get system font_scale").trim()
        val bytes=instrumentation.context.assets.open("fixture.mp4").use{it.readBytes()}
        FixtureServer(bytes).use{server->
            val file=File(context.filesDir,"series-player-${System.nanoTime()}.m3u").apply{writeText(buildString{
                append("#EXTM3U\n#EXTINF:-1 group-title=Demo,Control Live\n${server.base}/live.ts\n")
                append("#EXTINF:-1 group-title=Movies,Control Film\n${server.base}/movie/u/p/1.mp4\n")
                for((season,episode) in listOf(1 to 1,1 to 2,1 to 3,2 to 1))
                    append("#EXTINF:-1 group-title=Shows,Control Show S$season E$episode\n${server.base}/series/u/p/$season-$episode.mp4\n")
                append("#EXTINF:-1 group-title=Shows,Other Show S1 E1\n${server.base}/series/u/p/other.mp4\n")
            })}
            try{
                store.save(listOf(JSONObject().put("kind","file").put("name","Episode controls fixture").put("uri",android.net.Uri.fromFile(file).toString())))
                for(scale in listOf(1f,2f)){
                    device.executeShellCommand("settings put system font_scale $scale")
                    val deadline=SystemClock.elapsedRealtime()+5_000
                    while(kotlin.math.abs(context.resources.configuration.fontScale-scale)>0.05f&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(20)
                    withTvActivity{scenario->
                        fun waitFor(message:String,check:(MainActivity)->Boolean){
                            var ok=false;val deadline=SystemClock.elapsedRealtime()+15_000
                            while(!ok&&SystemClock.elapsedRealtime()<deadline){scenario.onTvActivity{ok=check(it)};if(!ok)SystemClock.sleep(40)}
                            assertTrue("$scale: $message",ok)
                        }
                        fun activate(label:String){
                            waitFor(label){it.findViewById<ViewGroup>(android.R.id.content).descendants.any{view->view.contentDescription==label||(view as? TextView)?.text?.toString()==label}}
                            scenario.onTvActivity{activity->assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).descendants.first{it.contentDescription==label||(it as? TextView)?.text?.toString()==label}.requestFocus())}
                            device.pressDPadCenter()
                        }
                        fun assertHidden(activity:MainActivity){
                            for(key in listOf("previous_episode","next_episode")){
                                val button=pane(activity).buttons.getValue(key)
                                assertEquals(View.GONE,button.visibility);assertFalse(button.isFocusable)
                                assertEquals(View.GONE,(button.parent as View).visibility)
                            }
                        }
                        fun controls(){scenario.onTvActivity{pane(it).showControls(true)}}
                        fun pressControl(key:String){
                            scenario.onTvActivity{assertTrue(pane(it).buttons.getValue(key).requestFocus())}
                            device.pressDPadCenter()
                        }
                        activate("Control Live")
                        waitFor("Live plays"){playback(it).player!=null}
                        scenario.onTvActivity{assertHidden(it)}
                        activate("Series (2)");activate("Control Show")
                        scenario.onTvActivity{assertHidden(it)}
                        activate("Control Show S1 E1")
                        waitFor("First episode has only Next enabled"){
                            episode(it)==1L&&pane(it).buttons.getValue("next_episode").isEnabled&&!pane(it).buttons.getValue("previous_episode").isEnabled&&pane(it).buttons.getValue("back").isEnabled
                        }
                        controls()
                        // Real remote traversal includes the new Next target after +30 seconds.
                        device.pressDPadRight();device.pressDPadRight()
                        waitFor("Next reached by remote"){pane(it).buttons.getValue("next_episode").hasFocus()}
                        device.pressDPadCenter()
                        waitFor("Middle episode retains Next focus"){
                            episode(it)==2L&&pane(it).buttons.getValue("previous_episode").isEnabled&&pane(it).buttons.getValue("next_episode").hasFocus()&&pane(it).buttons.getValue("forward").isEnabled&&playback(it).player?.isPlaying==true
                        }
                        scenario.onTvActivity{activity->
                            assertFalse(pane(activity).isFullscreen)
                            playback(activity).player!!.pause()
                            val bounds=Rect();val occupied=mutableListOf<Rect>()
                            for(button in pane(activity).buttons.values.filter{it.visibility==View.VISIBLE}){
                                assertTrue(button.getGlobalVisibleRect(bounds))
                                assertEquals(button.width,bounds.width());assertEquals(button.height,bounds.height())
                                assertTrue("Remote target remains at least 36dp",button.width>=TvUi.dp(activity,36))
                                assertTrue("Player buttons do not overlap",occupied.none{Rect.intersects(it,bounds)})
                                occupied+=Rect(bounds)
                            }
                        }
                        SystemClock.sleep(150)
                        device.takeScreenshot(File("/sdcard/Download/series-player-${if(scale==1f)"normal" else "double-text"}.png"))
                        pressControl("next_episode")
                        waitFor("Last episode does not advance into another season"){
                            episode(it)==3L&&pane(it).buttons.getValue("previous_episode").isEnabled&&!pane(it).buttons.getValue("next_episode").isEnabled&&pane(it).buttons.getValue("forward").isEnabled
                        }
                        controls()
                        scenario.onTvActivity{assertTrue(pane(it).buttons.getValue("forward").requestFocus())}
                        device.pressDPadRight()
                        waitFor("Disabled Next is skipped"){pane(it).buttons.getValue("stop").hasFocus()}
                        pressControl("previous_episode")
                        waitFor("Previous returns to episode 2"){episode(it)==2L&&pane(it).buttons.getValue("next_episode").isEnabled}
                        controls();pressControl("fullscreen")
                        waitFor("Fullscreen"){pane(it).isFullscreen}
                        controls();pressControl("previous_episode")
                        waitFor("Previous works inside fullscreen"){
                            pane(it).isFullscreen&&episode(it)==1L&&!pane(it).buttons.getValue("previous_episode").isEnabled&&pane(it).buttons.getValue("next_episode").isEnabled
                        }
                        controls();pressControl("next_episode")
                        waitFor("Next preserves fullscreen"){pane(it).isFullscreen&&episode(it)==2L&&pane(it).buttons.getValue("previous_episode").isEnabled}
                        controls()
                        device.takeScreenshot(File("/sdcard/Download/series-player-fullscreen-${if(scale==1f)"normal" else "double-text"}.png"))
                        device.pressBack();device.pressBack()
                        waitFor("Preview restored"){!pane(it).isFullscreen}
                        activate("Movies (1)");activate("Control Film")
                        scenario.onTvActivity{assertEquals("Browsing a film keeps controls for the playing episode",View.VISIBLE,pane(it).buttons.getValue("next_episode").visibility)}
                        activate("Abspielen")
                        waitFor("Movie replaces the episode"){playback(it).title=="Control Film"&&playback(it).player!=null&&pane(it).buttons.getValue("next_episode").visibility==View.GONE}
                        scenario.onTvActivity{assertHidden(it)}
                    }
                }
            }finally{device.executeShellCommand("settings put system font_scale $oldScale");store.save(old);file.delete()}
        }
    }
}

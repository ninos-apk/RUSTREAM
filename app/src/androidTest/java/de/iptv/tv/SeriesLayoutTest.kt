@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.descendants
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real native measurement and remote navigation, including the narrow series panel at 2x text. */
@RunWith(AndroidJUnit4::class)
class SeriesLayoutTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private fun field(activity:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
    private fun visible(view:View){
        val bounds=Rect()
        assertTrue("Visible: ${view.contentDescription}",view.getGlobalVisibleRect(bounds))
        assertEquals("Full width: ${view.contentDescription}",view.width,bounds.width())
        assertEquals("Full height: ${view.contentDescription}",view.height,bounds.height())
    }
    @Test fun seriesPanelKeepsActionsAndEpisodesReachable(){
        val sources=SourceStore(context);val previousSources=sources.load()
        val oldScale=device.executeShellCommand("settings get system font_scale").trim()
        val userFile=File(context.noBackupFilesDir,UserLibraryStore.FILE)
        val oldLibrary=userFile.takeIf{it.exists()}?.readBytes()
        val video=instrumentation.context.assets.open("fixture.mp4").use{it.readBytes()}
        FixtureServer(video).use{server->
            val playlist=File(context.filesDir,"series-layout-${System.nanoTime()}.m3u").apply{writeText(buildString{
                append("#EXTM3U\n#EXTINF:-1 group-title=Demo,Demo Live\n${server.base}/live.ts\n")
                for(episode in 1..30)append("#EXTINF:-1 group-title=Series,الفتوه S01 E${episode.toString().padStart(2,'0')}\n${server.base}/series/u/p/$episode.mp4\n")
                append("#EXTINF:-1 group-title=Series,الفتوه S02 E01\n${server.base}/series/u/p/31.mp4\n")
            })}
            try{
                sources.save(listOf(JSONObject().put("kind","file").put("name","Series layout fixture").put("uri",android.net.Uri.fromFile(playlist).toString())))
                // 320 physical px is deliberately narrower than the standard TV panel.
                for((name,scale,rtl,narrow) in listOf(
                    Case("english",1f,false,false),Case("german",1f,false,false),
                    Case("rtl",1f,true,false),Case("double-text",2f,false,false),Case("narrow-320px",1f,false,true)
                )){
                    AppLanguage.save(context,if(name=="english")"en" else "de")
                    device.executeShellCommand("settings put system font_scale $scale")
                    val configDeadline=SystemClock.elapsedRealtime()+5_000
                    while(kotlin.math.abs(context.resources.configuration.fontScale-scale)>0.05f&&SystemClock.elapsedRealtime()<configDeadline)SystemClock.sleep(20)
                    withTvActivity{scenario->
                        fun waitFor(message:String,check:(MainActivity)->Boolean){
                            var ok=false;val deadline=SystemClock.elapsedRealtime()+15_000
                            while(!ok&&SystemClock.elapsedRealtime()<deadline){scenario.onTvActivity{ok=check(it)};if(!ok)SystemClock.sleep(40)}
                            assertTrue("$name: $message",ok)
                        }
                        fun activate(label:String){
                            waitFor(label){activity->activity.findViewById<ViewGroup>(android.R.id.content).descendants.any{it.contentDescription==label||(it as? TextView)?.text?.toString()==label}}
                            scenario.onTvActivity{activity->assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).descendants.first{it.contentDescription==label||(it as? TextView)?.text?.toString()==label}.requestFocus())}
                            device.pressDPadCenter()
                        }
                        activate("Series (1)");activate("الفتوه")
                        waitFor("Episode list loaded"){(field(it,"channels") as RecyclerView).adapter?.itemCount==30}
                        scenario.onTvActivity{activity->
                            val panel=field(activity,"browserColumn") as ViewGroup
                            if(rtl)panel.layoutDirection=View.LAYOUT_DIRECTION_RTL
                            if(narrow)panel.layoutParams=panel.layoutParams.apply{width=320}
                            if(scale==2f)(field(activity,"seriesTitle") as TextView).text="الفتوه · Eine außergewöhnlich lange Serienbezeichnung mit zusätzlichen Informationen"
                            val list=field(activity,"channels") as RecyclerView
                            val adapter=field(activity,"channelRows")!!
                            val row=adapter.javaClass.getDeclaredField("rows").apply{isAccessible=true}.get(adapter).let{it as List<*>}.first()!!
                            val episodeKey=row.javaClass.getDeclaredField("key").apply{isAccessible=true}.get(row) as String
                            val source=field(activity,"activeSourceId") as String
                            val show=field(activity,"openedSeriesKey") as String
                            val library=field(activity,"userLibrary") as UserLibrary
                            library.recordStart(source,LibrarySection.SERIES,LibraryItem(show,"الفتوه",0,0,episodeKey=episodeKey,season=1,episode=1,episodeTitle="الفتوه S01 E01"))
                            library.updateProgress(source,episodeKey,49.0,2446.0,false)
                            assertTrue(list.getChildAt(0).requestFocus())
                        }
                        waitFor("Resume action appears"){(field(it,"seriesResume") as View).isShown}
                        SystemClock.sleep(200)
                        scenario.onTvActivity{activity->
                            val list=field(activity,"channels") as RecyclerView
                            visible(list.getChildAt(0));visible(field(activity,"seriesScrollHint") as View)
                            assertTrue("Space remains for multiple episodes",list.height>=TvUi.dp(activity,80))
                            val scroll=field(activity,"seriesScroll") as SeriesHeaderScroll
                            if(scale==1f)assertFalse("Compact header needs no scrolling",scroll.canScrollVertically(1))
                            assertTrue((field(activity,"seriesResume") as View).requestFocus())
                        }
                        SystemClock.sleep(200)
                        scenario.onTvActivity{activity->
                            visible(field(activity,"seriesResume") as View)
                            val progress=field(activity,"seriesProgress") as TextView
                            visible(progress)
                            assertEquals("Time values retain their position / duration order in RTL",1,progress.layout.getParagraphDirection(0))
                        }
                        // Capture the scoped layout with its primary action focused.
                        device.takeScreenshot(File("/sdcard/Download/series-layout-$name.png"))
                        scenario.onTvActivity{activity->(field(activity,"channels") as RecyclerView).getChildAt(0).requestFocus()}
                        repeat(35){device.pressDPadDown()}
                        waitFor("Last episode reachable by D-pad"){activity->
                            val list=field(activity,"channels") as RecyclerView
                            list.findContainingViewHolder(activity.currentFocus!!)?.bindingAdapterPosition==29
                        }
                        scenario.onTvActivity{activity->
                            visible(activity.currentFocus!!)
                            assertEquals(activity.getString(R.string.episodes_scroll_previous),(field(activity,"seriesScrollHint") as TextView).text.toString())
                            assertTrue((field(activity,"seasonButton") as View).requestFocus())
                        }
                        SystemClock.sleep(200)
                        scenario.onTvActivity{visible(field(it,"seasonButton") as View)}
                        device.pressDPadCenter()
                        assertTrue("Season selection remains functional",device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(if(name=="english")"Season 2 (1)" else "Staffel 2 (1)")),5_000))
                        device.pressBack()
                    }
                }
            }finally{
                device.executeShellCommand("settings put system font_scale $oldScale")
                sources.save(previousSources);playlist.delete()
                if(oldLibrary!=null)userFile.writeBytes(oldLibrary) else userFile.delete()
            }
        }
    }
    private data class Case(val name:String,val scale:Float,val rtl:Boolean,val narrow:Boolean)
}

package de.iptv.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.descendants
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

/** Remote navigation and cross-section browser state with a synthetic multi-category playlist. */
@RunWith(AndroidJUnit4::class)
class NavigationStateTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private lateinit var activeActivity:MainActivity
    private fun onMain(block:(MainActivity)->Unit)=instrumentation.runOnMainSync{block(activeActivity)}
    private fun field(activity:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
    private fun list(activity:MainActivity,name:String)=field(activity,name) as RecyclerView
    private fun waitFor(message:String,timeout:Long=15_000,test:(MainActivity)->Boolean){
        val deadline=SystemClock.elapsedRealtime()+timeout;var ok=false
        while(!ok&&SystemClock.elapsedRealtime()<deadline){onMain{ok=test(it)};if(!ok)SystemClock.sleep(40)}
        var state=""
        if(!ok){
            capture("navigation-failure")
            onMain{val rows=list(it,"channels");state=" width=${rows.width} span=${(rows.layoutManager as? GridLayoutManager)?.spanCount} titles=${titles(it)} focus=${describe(it.currentFocus)}"}
        }
        assertTrue(message+state,ok)
    }
    private fun capture(name:String){
        val directory=context.getExternalFilesDir(null)!!
        device.takeScreenshot(File(directory,"$name.png"));device.dumpWindowHierarchy(File(directory,"$name.xml"))
    }
    /** Focus a row by its accessible title and activate it with the remote OK key. */
    private fun press(title:String){
        assertNotNull(title,device.wait(Until.findObject(By.desc(title)),10_000))
        onMain{activity->
            val root=activity.findViewById<ViewGroup>(android.R.id.content)
            assertTrue(title,root.descendants.first{it.contentDescription==title&&it.isShown}.requestFocus())
        }
        device.pressDPadCenter()
    }
    private fun titles(activity:MainActivity):List<String>{
        val rows=list(activity,"channels")
        return (0 until rows.childCount).mapNotNull{rows.getChildAt(it).contentDescription?.toString()}
    }
    private fun shows(title:String,activity:MainActivity)=list(activity,"channels").isShown&&title in titles(activity)
    private fun focusedList(activity:MainActivity):RecyclerView?{
        var view:View?=activity.currentFocus
        while(view!=null&&view !is RecyclerView)view=view.parent as? View
        return view as? RecyclerView
    }
    private fun describe(view:View?):String=if(view==null)"nothing" else
        "${view.javaClass.simpleName}('${view.contentDescription ?: (view as? android.widget.TextView)?.text}') in " +
            generateSequence(view.parent as? View){it.parent as? View}.take(3).joinToString(" < "){it.contentDescription?.toString() ?: it.javaClass.simpleName}
    private fun focusedPosition(activity:MainActivity):Int{
        val focused=activity.currentFocus ?: return -1
        val rows=focusedList(activity) ?: return -1
        return rows.findContainingViewHolder(focused)?.bindingAdapterPosition ?: -1
    }
    private fun fixture(base:String):String=buildString {
        appendLine("#EXTM3U")
        for(group in 1..60){
            val name="Live %02d".format(group)
            for(channel in 1..2)appendLine("#EXTINF:-1 group-title=\"$name\",Channel %02d-$channel".format(group)).appendLine("$base/live/u/p/${group*10+channel}.ts")
        }
        for((group,prefix) in listOf("Series Alpha" to "Alpha","Series Beta" to "Beta","Series Empty Search" to "Gamma")){
            for(show in 1..3)for(episode in 1..2)
                appendLine("#EXTINF:-1 group-title=\"$group\",$prefix Show $show S1 E$episode").appendLine("$base/series/u/p/${prefix.hashCode().and(0xffff)}$show$episode.mp4")
        }
        for(group in listOf("Movies One","Movies Two"))for(movie in 1..3)
            appendLine("#EXTINF:-1 group-title=\"$group\",${group.substringAfter(' ')} Film $movie").appendLine("$base/movie/u/p/${group.length}$movie.mp4")
    }
    private fun withFixture(block:(ActivityScenario<MainActivity>)->Unit){
        val store=SourceStore(context);val old=store.load()
        val bytes=instrumentation.context.assets.open("fixture.mp4").use{it.readBytes()}
        FixtureServer(bytes).use{server->
            val file=File(context.filesDir,"navigation-${System.nanoTime()}.m3u").apply{writeText(fixture(server.base))}
            try{
                store.save(listOf(JSONObject().put("kind","file").put("name","Navigation fixture").put("uri",android.net.Uri.fromFile(file).toString())))
                ActivityScenario.launch(MainActivity::class.java).use{scenario->
                    scenario.onActivity{activeActivity=it}
                    try{
                        assertTrue(device.wait(Until.hasObject(By.desc("Channel 01-1").focused(true)),20_000))
                        block(scenario)
                    }finally{finishTvActivity(activeActivity)}
                }
            }finally{store.save(old);file.delete()}
        }
    }
    /** A held key arrives as one ACTION_DOWN followed by rapid repeats, without UP in between. */
    private fun holdKey(code:Int,repeats:Int,intervalMs:Long,check:(Int)->Unit={}){
        val down=SystemClock.uptimeMillis()
        for(repeat in 0..repeats){
            instrumentation.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,code,repeat))
            check(repeat)
            SystemClock.sleep(intervalMs)
        }
        instrumentation.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,code,0))
    }

    @Test fun holdingDownScrollsCategoriesWithoutLeavingTheList(){
        withFixture{
            for(interval in listOf(50L,16L)){
                press("Alle Kategorien")
                waitFor("Category list focused"){focusedList(it)===list(it,"categories")}
                var escaped:String?=null
                // Before the fix the focused row was scrapped mid-hold and focus fell back to the first tab.
                holdKey(KeyEvent.KEYCODE_DPAD_DOWN,40,interval){repeat->
                    onMain{activity->if(escaped==null&&focusedList(activity)!==list(activity,"categories"))
                        escaped="repeat $repeat at ${interval}ms focused ${describe(activity.currentFocus)}"}
                }
                instrumentation.waitForIdleSync()
                assertNull("Focus left the category list while holding DOWN: $escaped",escaped)
                onMain{assertTrue("Holding DOWN advanced through categories",focusedPosition(it)>=20)}
                holdKey(KeyEvent.KEYCODE_DPAD_UP,60,interval){repeat->
                    onMain{activity->if(escaped==null&&focusedList(activity)===list(activity,"channels"))
                        escaped="UP repeat $repeat at ${interval}ms focused ${describe(activity.currentFocus)}"}
                }
                instrumentation.waitForIdleSync()
                assertNull("Focus left the category list while holding UP: $escaped",escaped)
            }
            // The explicit RIGHT key still moves into the channel list.
            press("Live 03 (2)")
            waitFor("Channels of the selected category"){shows("Channel 03-1",it)}
            onMain{assertTrue(list(it,"categories").findFocus()!=null)}
            device.pressDPadRight()
            waitFor("RIGHT enters the channel list"){focusedList(it)===list(it,"channels")}
        }
    }

    @Test fun seriesPlaybackDoesNotBreakOtherSections(){
        withFixture{
            device.findObject(By.textStartsWith("Series (")).click()
            waitFor("Series grid"){shows("Alpha Show 1",it)}
            press("Series Alpha (3)")
            waitFor("Alpha series"){shows("Alpha Show 2",it)&&!shows("Beta Show 1",it)}
            press("Alpha Show 2")
            assertTrue(device.wait(Until.hasObject(By.text("Staffel 1 ▾")),10_000))
            press("Alpha Show 2 S1 E1")
            assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Alpha Show 2 S1 E1")),15_000))
            // Another series category replaces the episode list with that category's library.
            press("Series Beta (3)")
            waitFor("Beta series after playback"){shows("Beta Show 1",it)&&!shows("Alpha Show 1",it)
                &&list(it,"channels").layoutManager is GridLayoutManager}
            press("Series Alpha (3)")
            waitFor("Alpha series again"){shows("Alpha Show 3",it)}
            // Enter fullscreen and return before switching sections.
            press("Alpha Show 1")
            assertTrue(device.wait(Until.hasObject(By.text("Staffel 1 ▾")),10_000))
            press("Alpha Show 1 S1 E2")
            assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Alpha Show 1 S1 E2")),15_000))
            press("Alpha Show 1 S1 E2")
            waitFor("Fullscreen"){field(it,"fullScreen")==true}
            device.pressBack()
            waitFor("Fullscreen closed"){field(it,"fullScreen")==false}
            device.findObject(By.textStartsWith("Movies (")).click()
            waitFor("Movie grid"){shows("One Film 1",it)&&shows("Two Film 3",it)}
            press("Movies Two (3)")
            waitFor("Movie category"){shows("Two Film 2",it)&&!shows("One Film 2",it)}
            device.findObject(By.textStartsWith("Live (")).click()
            waitFor("Live list"){shows("Channel 01-1",it)}
            press("Live 05 (2)")
            waitFor("Live category"){shows("Channel 05-2",it)}
            device.findObject(By.textStartsWith("Series (")).click()
            press("Series Beta (3)")
            waitFor("Series category after visiting other sections"){shows("Beta Show 2",it)}
            press("Beta Show 2")
            assertTrue(device.wait(Until.hasObject(By.text("Staffel 1 ▾")),10_000))
            device.pressBack()
            waitFor("Back restores the Beta library"){shows("Beta Show 2",it)&&!shows("Beta Show 2 S1 E1",it)}
        }
    }
}

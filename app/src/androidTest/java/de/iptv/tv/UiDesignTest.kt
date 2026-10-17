@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.descendants
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiDesignTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private fun pane(activity:MainActivity)=activity.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<PlayerPane>().first()
    private fun controller(activity:MainActivity)=MainActivity::class.java.getDeclaredField("playback").apply { isAccessible=true }.get(activity) as PlaybackController
    private fun waitFor(message:String,check:(MainActivity)->Boolean,scenario:ActivityScenario<MainActivity>) {
        val deadline=SystemClock.elapsedRealtime()+10_000
        var done=false
        while(!done && SystemClock.elapsedRealtime()<deadline){scenario.onTvActivity { done=check(it) };if(!done)SystemClock.sleep(40)}
        assertTrue(message,done)
    }
    @Test fun emptyStartAndInvalidFormKeepLabelsAndInput() {
        val store=SourceStore(context);val old=store.load()
        try {
            store.save(emptyList())
            withTvActivity {
                assertTrue(device.wait(Until.hasObject(By.text("Quelle hinzufügen").focused(true)),10_000))
                device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("URL hinzufügen")),5_000))
                device.findObject(By.text("URL hinzufügen")).click()
                assertTrue(device.wait(Until.hasObject(By.desc("HTTP(S)-URL")),5_000))
                device.findObject(By.desc("Name")).text="UI behalten"
                device.findObject(By.desc("HTTP(S)-URL")).text="ungueltig"
                val submit=device.wait(Until.findObject(By.res("android:id/button1")),5_000)
                assertNotNull("Import dialog keeps its submit action",submit)
                submit.click()
                assertTrue(device.hasObject(By.desc("Name").text("UI behalten")))
                assertTrue(device.hasObject(By.desc("HTTP(S)-URL").text("ungueltig")))
                assertTrue(device.hasObject(By.text("Playlist-Adresse")))
            }
        } finally { store.save(old) }
    }
    @Test fun remoteFocusRepeatOkAndRefreshPreserveTheirOwnResponsibilities() {
        val store=SourceStore(context);val old=store.load()
        val bytes=instrumentation.context.assets.open("fixture.mp4").use { it.readBytes() }
        FixtureServer(bytes).use { server ->
            val file=File(context.filesDir,"ui-focus-${System.nanoTime()}.m3u").apply {
                writeText("#EXTM3U\n#EXTINF:-1 group-title=Demo,UI Live\n${server.base}/live.ts\n#EXTINF:20 group-title=Demo,UI Film\n${server.base}/vod.mp4\n")
            }
            try {
                store.save(listOf(JSONObject().put("kind","file").put("name","UI Demo").put("uri",android.net.Uri.fromFile(file).toString())))
                withTvActivity { scenario ->
                    assertTrue(device.wait(Until.hasObject(By.desc("UI Live").focused(true)),15_000))
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · UI Live")),15_000))
                    device.pressDPadRight()
                    waitFor("Preview has persistent visible focus",{pane(it).video.hasFocus() && pane(it).previewFocusVisible},scenario)
                    SystemClock.sleep(1700)
                    scenario.onTvActivity { assertTrue(pane(it).previewFocusVisible);assertFalse(pane(it).buttons.containsKey("reconnect")) }
                    device.executeShellCommand("screencap -p /sdcard/Download/ui-design-focus.png")
                    device.pressDPadLeft()
                    assertTrue(device.wait(Until.hasObject(By.desc("UI Live").focused(true)),5_000))
                    lateinit var before:ExoPlayer
                    scenario.onTvActivity { before=controller(it).player!! }
                    device.pressDPadCenter()
                    waitFor("Same active channel opens fullscreen",{pane(it).isFullscreen},scenario)
                    scenario.onTvActivity { assertSame(before,controller(it).player) }
                    device.executeShellCommand("screencap -p /sdcard/Download/ui-design-fullscreen.png")
                    device.pressBack()
                    assertTrue(device.wait(Until.hasObject(By.desc("UI Live").focused(true)),5_000))
                    scenario.onTvActivity { activity ->
                        val refresh=activity.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<android.widget.Button>().first { it.text=="Refresh" }
                        assertTrue(refresh.isEnabled);refresh.requestFocus()
                    }
                    device.pressDPadCenter()
                    waitFor("Refresh reconnects selected playback",{controller(it).player!=null && controller(it).player!==before},scenario)
                    device.executeShellCommand("screencap -p /sdcard/Download/ui-design-overview.png")
                    device.findObject(By.text("Movies (1)")).click()
                    assertTrue(device.wait(Until.hasObject(By.desc("UI Film")),5_000))
                    scenario.onTvActivity { activity -> activity.findViewById<ViewGroup>(android.R.id.content).descendants.first { it.contentDescription=="UI Film" }.requestFocus() }
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.hasObject(By.text("Abspielen").focused(true)),5_000))
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · UI Film")),10_000))
                    device.pressDPadRight();device.pressDPadDown()
                    assertTrue(device.wait(Until.hasObject(By.desc("Pause").focused(true)),5_000))
                    device.executeShellCommand("screencap -p /sdcard/Download/ui-design-controls.png")
                }
            } finally { store.save(old);file.delete() }
        }
    }
    @Test fun cancellingSourceRemovalKeepsTheSavedSource() {
        val store=SourceStore(context);val old=store.load()
        val file=File(context.filesDir,"ui-removal-${System.nanoTime()}.m3u").apply {
            writeText("#EXTM3U\n#EXTINF:-1,Removal Demo\nhttps://example.test/live/1.ts\n")
        }
        try {
            store.save(listOf(JSONObject().put("kind","file").put("name","Quelle behalten").put("uri",android.net.Uri.fromFile(file).toString())))
            withTvActivity { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Removal Demo")),10_000))
                scenario.onTvActivity { activity -> activity.findViewById<ViewGroup>(android.R.id.content).descendants.first { it.contentDescription=="Einstellungen" }.requestFocus() }
                device.pressDPadCenter();device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Gespeicherte Playlists")),5_000))
                device.findObject(By.res("android:id/button3")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Quelle entfernen")),5_000))
                device.findObject(By.text("Quelle behalten")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Quelle „Quelle behalten“ entfernen?")),5_000))
                assertTrue(device.wait(Until.hasObject(By.res("android:id/button2").focused(true)),5_000))
                device.pressDPadCenter()
                assertEquals(1,store.load().size)
                assertEquals("Quelle behalten",store.load().first().getString("name"))
            }
        } finally { store.save(old);file.delete() }
    }
    @Test fun keyReleaseStopsAcceleratingSeekAndRepeatEventsDoNotRestartIt() {
        val store=SourceStore(context);val old=store.load()
        val uiScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val positions=ArrayList<Long>()
        var player:ExoPlayer?=null
        var seekPane:PlayerPane?=null
        try {
            store.save(emptyList())
            withTvActivity { scenario ->
                assertTrue(device.wait(Until.hasObject(By.text("Quelle hinzufügen")),10_000))
                scenario.onTvActivity { activity ->
                    player=ExoPlayer.Builder(activity).build()
                    seekPane=PlayerPane(activity,uiScope,{}, { positions.add(it) }).apply {
                        video.player=player;hasSelection=true
                        update(PlaybackStats(seekable=true,durationMs=600_000))
                    }
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(seekPane,FrameLayout.LayoutParams(-1,-1))
                    seekPane!!.setFullscreen(true,false)
                }
                waitFor("Video and window focused for remote seeking",{seekPane!!.video.hasFocus() && seekPane!!.hasWindowFocus()},scenario)
                device.waitForIdle(1_000)
                val down=SystemClock.uptimeMillis()
                instrumentation.sendKeySync(KeyEvent(down,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,0))
                waitFor("Initial key press seeks thirty seconds",{positions.firstOrNull()==30_000L},scenario)
                instrumentation.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,5))
                SystemClock.sleep(1600)
                instrumentation.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT,0))
                var afterRelease=0
                scenario.onTvActivity { assertTrue(positions.last()>60_000);assertFalse(seekPane!!.seeking);afterRelease=positions.size }
                SystemClock.sleep(500)
                scenario.onTvActivity { assertEquals(afterRelease,positions.size) }
                val again=SystemClock.uptimeMillis()
                instrumentation.sendKeySync(KeyEvent(again,again,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,0))
                scenario.onTvActivity { seekPane!!.cancelSeek() }
                val stopped=positions.size
                SystemClock.sleep(650)
                scenario.onTvActivity { assertEquals(stopped,positions.size);player?.release();player=null }
            }
        } finally { uiScope.cancel();instrumentation.runOnMainSync { player?.release() };store.save(old) }
    }
}

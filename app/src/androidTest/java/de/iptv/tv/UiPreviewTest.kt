@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.descendants
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in visual/real-decoder check against a host-side, strictly local synthetic provider. */
@RunWith(AndroidJUnit4::class)
class UiPreviewTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private fun field(activity:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
    private fun controller(activity:MainActivity)=field(activity,"playback") as PlaybackController
    private fun pane(activity:MainActivity)=activity.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<PlayerPane>().first()
    private fun seekable(pane:PlayerPane)=(PlayerPane::class.java.getDeclaredField("lastStats").apply { isAccessible=true }.get(pane) as PlaybackStats).seekable
    private fun waitFor(message:String,check:(MainActivity)->Boolean,scenario:ActivityScenario<MainActivity>) {
        val deadline=SystemClock.elapsedRealtime()+10_000
        var done=false
        while(!done && SystemClock.elapsedRealtime()<deadline){scenario.onTvActivity { done=check(it) };if(!done)SystemClock.sleep(40)}
        assertTrue(message,done)
    }
    @Test fun importLocalDemoAndSeekRealVideo() {
        val url=InstrumentationRegistry.getArguments().getString("uiDemoUrl")
        assumeTrue("No local UI preview provider supplied",url?.startsWith("http://127.0.0.1:")==true)
        // Each run imports into an empty start screen, and the film has no saved progress to resume.
        val store=SourceStore(context);val old=store.load()
        val userFile=File(context.noBackupFilesDir,UserLibraryStore.FILE)
        val oldLibrary=userFile.takeIf { it.isFile }?.readBytes()
        var libraryStore:UserLibraryStore?=null
        try {
            store.save(emptyList());userFile.delete();File(userFile.path+".bak").delete()
            withTvActivity { scenario ->
                scenario.onTvActivity { libraryStore=field(it,"libraryStore") as UserLibraryStore }
                pressFocused(By.text("Quelle hinzufügen"),10_000)
                assertTrue(device.wait(Until.hasObject(By.text("Quellen verwalten").focused(true)),5_000))
                device.pressDPadDown();device.pressDPadDown()
                pressFocused(By.text("URL hinzufügen"))
                assertTrue(device.wait(Until.hasObject(By.desc("HTTP(S)-URL")),5_000))
                device.findObject(By.desc("Name")).text="UI Demo"
                device.findObject(By.desc("HTTP(S)-URL")).text=url
                focusDialogView("android:id/button1")
                pressFocused(By.res("android:id/button1"))
                SystemClock.sleep(300)
                device.executeShellCommand("screencap -p /sdcard/Download/ui-design-import.png")
                // The app focuses the first channel once the list is laid out; move focus only after that.
                assertTrue(device.wait(Until.hasObject(By.desc("Demo Sender 001").focused(true)),15_000))
                assertTrue(device.wait(Until.gone(By.desc("HTTP(S)-URL")),5_000))
                scenario.onTvActivity { activity -> assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<Button>().first { it.text=="Movies (1)" }.requestFocus()) }
                pressFocused(By.text("Movies (1)"))
                val title="Demo Film fuer die neue TV-Oberflaeche"
                assertTrue(device.wait(Until.hasObject(By.desc(title)),5_000))
                scenario.onTvActivity { activity -> assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).descendants.first { it.contentDescription==title }.requestFocus()) }
                pressFocused(By.desc(title))
                // The card opens the film's details; their play action starts playback.
                pressFocused(By.text("Abspielen"))
                assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · $title")),10_000))
                device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
                assertTrue(device.wait(Until.hasObject(By.text("Pausiert · $title")),5_000))
                // The play action keeps focus and opens the running film in fullscreen.
                pressFocused(By.text("Wiedergabe öffnen"))
                waitFor("Paused film is fullscreen and seekable by remote",{ pane(it).let { pane -> pane.isFullscreen && pane.video.hasFocus() && seekable(pane) } },scenario)
                fun position():Long { var value=0L;scenario.onTvActivity { value=controller(it).player!!.currentPosition };return value }
                val before=position()
                device.pressDPadRight()
                val tapDeadline=SystemClock.elapsedRealtime()+3_000
                while(position()<before+29_000 && SystemClock.elapsedRealtime()<tapDeadline)SystemClock.sleep(25)
                val afterTap=position()
                assertTrue("Short press moves thirty seconds",afterTap-before in 29_000..31_000)
                device.executeShellCommand("screencap -p /sdcard/Download/ui-design-real-tap.png")
                val down=SystemClock.uptimeMillis()
                instrumentation.sendKeySync(KeyEvent(down,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,0))
                SystemClock.sleep(2500)
                instrumentation.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT,0))
                val released=position()
                assertTrue("Hold advances beyond a single extra tap",released-afterTap>60_000)
                SystemClock.sleep(450)
                assertTrue("Release stops seeking",kotlin.math.abs(position()-released)<500)
                scenario.onTvActivity { assertFalse(controller(it).player!!.playWhenReady) }
                android.util.Log.i("IptvUiSeek","tap_delta_ms=${afterTap-before} hold_delta_ms=${released-afterTap} released_position_ms=$released")
                device.executeShellCommand("screencap -p /sdcard/Download/ui-design-real-hold.png")
                device.pressDPadDown()
                device.executeShellCommand("screencap -p /sdcard/Download/ui-design-real-controls.png")
            }
        } finally {
            // onDestroy writes the film's progress asynchronously; restore the previous library after that write.
            libraryStore?.let { runBlocking { it.flush().join() } }
            if(oldLibrary!=null)userFile.writeBytes(oldLibrary) else userFile.delete()
            store.save(old)
        }
    }
}
